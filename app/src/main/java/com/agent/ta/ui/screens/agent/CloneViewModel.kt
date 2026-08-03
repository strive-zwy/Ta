package com.agent.ta.ui.screens.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agent.ta.TaApplication
import com.agent.ta.domain.CelebrityCloner
import com.agent.ta.domain.CloneResult
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 克隆页面 UI 状态
 */
sealed class CloneUiState {
    /** 初始空闲态 */
    data object Idle : CloneUiState()
    /** 生成中 */
    data object Loading : CloneUiState()
    /** 生成成功，返回结果供预览/微调 */
    data class Success(val result: CloneResult) : CloneUiState()
    /** 生成或保存失败 */
    data class Error(val message: String) : CloneUiState()
}

/**
 * 克隆页面 ViewModel（Phase 4）
 *
 * 管理：
 * - 输入的启发人物名 / 自定义昵称
 * - 生成状态（Idle/Loading/Success/Error）
 * - 生成结果应用与保存
 *
 * 流程：generate() → 成功后 UI 预览/微调 → applyAndSave() 写回 AgentConfig
 */
class CloneViewModel : ViewModel() {

    private val cloner = CelebrityCloner()
    private val editor = ServiceLocator.agentConfigEditor

    private val _uiState = MutableStateFlow<CloneUiState>(CloneUiState.Idle)
    val uiState: StateFlow<CloneUiState> = _uiState.asStateFlow()

    private val _starName = MutableStateFlow("")
    val starName: StateFlow<String> = _starName.asStateFlow()

    private val _customNickname = MutableStateFlow("")
    val customNickname: StateFlow<String> = _customNickname.asStateFlow()

    /** 保存状态：Idle / Saving / Saved / SaveError */
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    fun onStarNameChange(value: String) { _starName.value = value }
    fun onNicknameChange(value: String) { _customNickname.value = value }

    /**
     * 触发克隆生成
     *
     * 成功后 uiState 变为 Success，UI 可读取 result 预览/微调
     */
    fun generate() {
        val starName = _starName.value.trim()
        val nickname = _customNickname.value.trim()
        if (starName.isBlank() || nickname.isBlank()) {
            _uiState.value = CloneUiState.Error("请填写启发人物名和自定义昵称")
            return
        }

        val appContext = TaApplication.instance
            ?: return run { _uiState.value = CloneUiState.Error("应用未初始化，请稍后重试") }

        _uiState.value = CloneUiState.Loading
        viewModelScope.launch {
            try {
                val result = cloner.generate(starName, nickname, appContext)
                _uiState.value = CloneUiState.Success(result)
            } catch (e: CelebrityCloner.CloneException) {
                _uiState.value = CloneUiState.Error(e.message ?: "克隆失败")
            } catch (e: Exception) {
                _uiState.value = CloneUiState.Error("未知错误：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 应用克隆结果并保存到 AgentConfig
     *
     * - 覆盖 identity + 部分 persona
     * - 保留 voice / avatars / behavior
     * - 写入 DB 并刷新内存缓存
     */
    fun applyAndSave(result: CloneResult) {
        val nickname = _customNickname.value.trim().ifBlank {
            _uiState.value = CloneUiState.Error("昵称为空，无法应用")
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            try {
                editor.update { current ->
                    cloner.applyToConfig(current, result, nickname)
                }
                // 配置写入后触发作息重新生成（复用 Agent 切换流程：
                // 重新生成今日作息 + 更新状态机 + 重新调度，isAgentSwitch=true 不注入旧记忆）
                val appContext = TaApplication.instance
                if (appContext != null) {
                    com.agent.ta.service.AgentEngine.reloadAfterConfigChanged(appContext)
                }
                _saveState.value = SaveState.Saved
            } catch (e: Exception) {
                _saveState.value = SaveState.SaveError(e.message ?: "保存失败")
            }
        }
    }

    fun resetState() {
        _uiState.value = CloneUiState.Idle
        _saveState.value = SaveState.Idle
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
    }
}

sealed class SaveState {
    data object Idle : SaveState()
    data object Saving : SaveState()
    data object Saved : SaveState()
    data class SaveError(val message: String) : SaveState()
}
