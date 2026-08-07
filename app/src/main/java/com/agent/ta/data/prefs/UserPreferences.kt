package com.agent.ta.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agent.ta.data.model.ModelEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 用户设置存储
 * - API Key 用 EncryptedSharedPreferences 加密存储
 * - 其他设置用普通 SharedPreferences
 * - LLM / TTS 支持多模型：列表以 JSON 存储，每个模型项独立保存 baseUrl/apiKey/model；
 *   对外仍暴露 `llmBaseUrl` / `llmApiKey` / `llmModel` / `tts*` 等便捷属性，
 *   内部读取/写入"当前激活模型"，从而不破坏既有调用方（LlmClient/TtsClient 等）。
 */
class UserPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        SECURE_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    // ===== 用户称呼 =====
    var userNickname: String
        get() = prefs.getString(KEY_USER_NICKNAME, "你") ?: "你"
        set(value) = prefs.edit().putString(KEY_USER_NICKNAME, value).apply()

    // ===== 用户头像（本地文件绝对路径，空表示未设置，回退首字符）=====
    var userAvatarPath: String
        get() = prefs.getString(KEY_USER_AVATAR_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_AVATAR_PATH, value).apply()

    // ===== LLM 多模型列表 / 激活 id =====
    /** LLM 模型列表（始终非空，至少包含一个 entry） */
    var llmModels: List<ModelEntry>
        get() {
            val raw = prefs.getString(KEY_LLM_MODELS_JSON, null)
            val list = if (raw.isNullOrBlank()) emptyList()
                       else try { json.decodeFromString(ListSerializer(ModelEntry.serializer()), raw) }
                       catch (e: Exception) { emptyList() }
            if (list.isNotEmpty()) {
                val legacySecrets = ModelSecretPolicy.extractSecrets(list)
                if (legacySecrets.isNotEmpty()) {
                    persistModelSecrets(legacySecrets)
                    prefs.edit().putString(
                        KEY_LLM_MODELS_JSON,
                        json.encodeToString(
                            ListSerializer(ModelEntry.serializer()),
                            ModelSecretPolicy.stripSecrets(list)
                        )
                    ).apply()
                }
                return ModelSecretPolicy.restoreSecrets(
                    ModelSecretPolicy.stripSecrets(list),
                    readModelSecrets(list.map { it.id })
                )
            }
            // 首次启动迁移：若列表为空但旧字段非空/非默认，则把旧配置迁移成默认 entry
            return if (list.isEmpty()) {
                val migrated = migrateFromLegacyLlm()
                if (migrated != null) {
                    llmModels = listOf(migrated)
                    llmActiveId = migrated.id
                    ModelSecretPolicy.restoreSecrets(
                        listOf(migrated),
                        readModelSecrets(listOf(migrated.id))
                    )
                } else {
                    // 首次启动：预置「日日新」和「DeepSeek」两个模型，默认激活日日新
                    val senseNova = ModelEntry(
                        id = generateId(),
                        name = PRESET_LLM_SENSENOVA_NAME,
                        baseUrl = PRESET_LLM_SENSENOVA_URL,
                        apiKey = "",
                        model = DEFAULT_LLM_MODEL
                    )
                    val deepSeek = ModelEntry(
                        id = generateId(),
                        name = PRESET_LLM_DEEPSEEK_NAME,
                        baseUrl = PRESET_LLM_DEEPSEEK_URL,
                        apiKey = "",
                        model = DEFAULT_LLM_MODEL
                    )
                    val defaults = listOf(senseNova, deepSeek)
                    llmModels = defaults
                    llmActiveId = senseNova.id
                    ModelSecretPolicy.restoreSecrets(
                        defaults,
                        readModelSecrets(defaults.map { it.id })
                    )
                }
            } else list
        }
        set(value) {
            persistModelSecrets(ModelSecretPolicy.extractSecrets(value))
            val ids = value.map { it.id }.toSet()
            securePrefs.edit().apply {
                securePrefs.all.keys
                    .filter { it.startsWith(KEY_LLM_SECRET_PREFIX) }
                    .filterNot { it.removePrefix(KEY_LLM_SECRET_PREFIX) in ids }
                    .forEach(::remove)
            }.apply()
            prefs.edit().putString(
                KEY_LLM_MODELS_JSON,
                json.encodeToString(
                    ListSerializer(ModelEntry.serializer()),
                    ModelSecretPolicy.stripSecrets(value)
                )
            ).apply()
        }

    /** 当前激活的 LLM 模型 id */
    var llmActiveId: String
        get() = prefs.getString(KEY_LLM_ACTIVE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_ACTIVE_ID, value).apply()

    /** 当前激活的 LLM 模型 entry（若激活 id 无效则回退到列表第一个） */
    val llmActiveModel: ModelEntry
        get() {
            val models = llmModels
            return models.firstOrNull { it.id == llmActiveId } ?: models.first()
        }

    // ===== LLM 便捷属性（读写激活模型） =====
    var llmBaseUrl: String
        get() = llmActiveModel.baseUrl.ifBlank { DEFAULT_LLM_BASE_URL }
        set(value) = updateLlmActive { it.copy(baseUrl = value) }

    var llmModel: String
        get() = llmActiveModel.model.ifBlank { DEFAULT_LLM_MODEL }
        set(value) = updateLlmActive { it.copy(model = value) }

    var llmApiKey: String
        get() = securePrefs.getString(
            KEY_LLM_SECRET_PREFIX + llmActiveModel.id,
            ""
        ) ?: ""
        set(value) = updateLlmActive { it.copy(apiKey = value) }

    // ===== TTS 配置（简化版：baseUrl + apiKey，模型固定为三个 MiMo 模型） =====
    // 三个模型由 TtsClient 根据场景自动选择：
    // - mimo-v2.5-tts-voiceclone：有音频样本时
    // - mimo-v2.5-tts-voicedesign：无样本、有音色描述时
    // - mimo-v2.5-tts：无样本、无描述、用预置音色时
    var ttsBaseUrl: String
        get() = prefs.getString(KEY_TTS_BASE_URL, DEFAULT_TTS_BASE_URL) ?: DEFAULT_TTS_BASE_URL
        set(value) = prefs.edit().putString(KEY_TTS_BASE_URL, value).apply()

    var ttsApiKey: String
        get() = securePrefs.getString(KEY_TTS_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_TTS_API_KEY, value).apply()

    /**
     * 当前 TTS 模型（已废弃用户选择，固定返回 voiceclone 模型作为"模型字段"占位）
     * 实际模型选择由 TtsClient 根据场景自动切换。
     * 保留此属性是为了兼容 ModelConfigScreen 旧 UI 和 LlmClient.api 日志。
     */
    @Deprecated("TTS 模型现已固定为三个 MiMo 模型自动选择，无需用户配置", ReplaceWith("TTS_MODEL_VOICECLONE"))
    var ttsModel: String
        get() = TTS_MODEL_VOICECLONE
        set(_) {}

    // ===== TTS 旧版迁移（首次启动从 legacy 字段迁移 baseUrl/apiKey） =====
    private fun migrateTtsFromLegacyIfNeeded() {
        if (prefs.getBoolean(KEY_TTS_MIGRATED, false)) return
        val oldUrl = prefs.getString(KEY_TTS_BASE_URL_LEGACY, null)
        val oldKey = securePrefs.getString(KEY_TTS_API_KEY_LEGACY, null)
        if (!oldUrl.isNullOrBlank()) ttsBaseUrl = oldUrl
        if (!oldKey.isNullOrBlank()) ttsApiKey = oldKey
        prefs.edit().putBoolean(KEY_TTS_MIGRATED, true).apply()
    }

    init {
        migrateTtsFromLegacyIfNeeded()
    }

    // ===== 多模型管理方法 =====
    /** 新增一个 LLM 模型并设为激活 */
    fun addLlmModel(name: String, baseUrl: String = "", apiKey: String = "", model: String = ""): ModelEntry {
        val entry = ModelEntry(id = generateId(), name = name, baseUrl = baseUrl, apiKey = apiKey, model = model)
        llmModels = llmModels + entry
        llmActiveId = entry.id
        return entry
    }

    /** 删除指定 LLM 模型；若删的是激活模型，自动切到剩余第一个 */
    fun removeLlmModel(id: String) {
        val models = llmModels.filter { it.id != id }
        if (models.isEmpty()) return // 至少保留 1 个
        llmModels = models
        if (llmActiveId == id) llmActiveId = models.first().id
    }

    /** 切换当前激活的 LLM 模型 */
    fun selectLlmModel(id: String) {
        if (llmModels.any { it.id == id }) llmActiveId = id
    }

    /** 更新激活 LLM 模型的字段（浅拷贝替换） */
    private fun updateLlmActive(transform: (ModelEntry) -> ModelEntry) {
        val models = llmModels.toMutableList()
        val idx = models.indexOfFirst { it.id == llmActiveId }
        if (idx >= 0) {
            models[idx] = transform(models[idx])
            llmModels = models.toList()
        } else if (models.isNotEmpty()) {
            // 激活 id 无效，更新第一个
            models[0] = transform(models[0])
            llmModels = models.toList()
            llmActiveId = models[0].id
        }
    }

    // ===== 行为配置 =====
    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    var showTranscript: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TRANSCRIPT, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TRANSCRIPT, value).apply()

    var boredInitiateEnabled: Boolean
        get() = prefs.getBoolean(KEY_BORED_INITIATE, true)
        set(value) = prefs.edit().putBoolean(KEY_BORED_INITIATE, value).apply()

    /** 开发者模式：开启后显示「今日作息」「定时任务管理」等后台调试模块 */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER_MODE, value).apply()

    /** 承诺定时提醒：开启后到点由系统主动发消息提醒；关闭后靠 LLM 在对话中自然提醒 */
    var commitmentTimerEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMMITMENT_TIMER, true)
        set(value) = prefs.edit().putBoolean(KEY_COMMITMENT_TIMER, value).apply()

    // ===== 状态查询 =====
    fun isConfigured(): Boolean = llmApiKey.isNotBlank() && ttsApiKey.isNotBlank()

    // ===== 旧版配置迁移 =====
    /** 如果旧版字段（KEY_LLM_BASE_URL/KEY_LLM_API_KEY）有值，迁移成一个 entry；否则返回 null */
    private fun migrateFromLegacyLlm(): ModelEntry? {
        val oldUrl = prefs.getString(KEY_LLM_BASE_URL_LEGACY, null)
        val oldKey = securePrefs.getString(KEY_LLM_API_KEY_LEGACY, null)
        val oldModel = prefs.getString(KEY_LLM_MODEL_LEGACY, null)
        // 只在旧字段有非空 apiKey 时迁移（避免空配置覆盖）
        if (oldKey.isNullOrBlank() && oldUrl.isNullOrBlank()) return null
        val entry = ModelEntry(
            id = generateId(),
            name = "默认 LLM",
            baseUrl = oldUrl ?: DEFAULT_LLM_BASE_URL,
            apiKey = oldKey ?: "",
            model = oldModel ?: DEFAULT_LLM_MODEL
        )
        if (entry.apiKey.isNotBlank()) persistModelSecrets(mapOf(entry.id to entry.apiKey))
        return entry.copy(apiKey = "")
    }

    private fun persistModelSecrets(secrets: Map<String, String>) {
        if (secrets.isEmpty()) return
        securePrefs.edit().apply {
            secrets.forEach { (id, key) -> putString(KEY_LLM_SECRET_PREFIX + id, key) }
        }.apply()
    }

    private fun readModelSecrets(ids: List<String>): Map<String, String> =
        ids.mapNotNull { id ->
            securePrefs.getString(KEY_LLM_SECRET_PREFIX + id, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { id to it }
        }.toMap()

    private fun generateId(): String = System.currentTimeMillis().toString(16) +
            (0..0xFFFF).random().toString(16)

    companion object {
        private const val PREFS_FILE_NAME = "ta_prefs"
        private const val SECURE_FILE_NAME = "ta_secure_prefs"

        private const val KEY_USER_NICKNAME = "user_nickname"
        private const val KEY_USER_AVATAR_PATH = "user_avatar_path"

        // LLM 多模型字段
        private const val KEY_LLM_MODELS_JSON = "llm_models_json"
        private const val KEY_LLM_ACTIVE_ID = "llm_active_id"
        private const val KEY_LLM_SECRET_PREFIX = "llm_api_key_"

        // TTS 简化字段（baseUrl + apiKey，模型固定）
        private const val KEY_TTS_BASE_URL = "tts_base_url"
        private const val KEY_TTS_API_KEY = "tts_api_key"
        private const val KEY_TTS_MIGRATED = "tts_migrated"

        // 旧版字段（仅用于迁移）
        private const val KEY_LLM_BASE_URL_LEGACY = "llm_base_url"
        private const val KEY_LLM_MODEL_LEGACY = "llm_model"
        private const val KEY_LLM_API_KEY_LEGACY = "llm_api_key"
        private const val KEY_TTS_BASE_URL_LEGACY = "tts_base_url"
        private const val KEY_TTS_MODEL_LEGACY = "tts_model"
        private const val KEY_TTS_API_KEY_LEGACY = "tts_api_key"

        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_SHOW_TRANSCRIPT = "show_transcript"
        private const val KEY_BORED_INITIATE = "bored_initiate"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_COMMITMENT_TIMER = "commitment_timer_enabled"

        const val DEFAULT_LLM_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_LLM_MODEL = "deepseek-v4-flash"
        const val DEFAULT_TTS_BASE_URL = "https://api.xiaomimimo.com/v1"

        // 预置 LLM 提供商（首次启动默认创建）
        const val PRESET_LLM_SENSENOVA_NAME = "日日新"
        const val PRESET_LLM_SENSENOVA_URL = "https://token.sensenova.cn/v1"
        const val PRESET_LLM_DEEPSEEK_NAME = "DeepSeek"
        const val PRESET_LLM_DEEPSEEK_URL = "https://api.deepseek.com/v1"

        // MiMo TTS 三个固定模型（由 TtsClient 根据场景自动选择）
        const val TTS_MODEL_VOICECLONE = "mimo-v2.5-tts-voiceclone"
        const val TTS_MODEL_VOICEDESIGN = "mimo-v2.5-tts-voicedesign"
        const val TTS_MODEL_TTS = "mimo-v2.5-tts"
    }
}
