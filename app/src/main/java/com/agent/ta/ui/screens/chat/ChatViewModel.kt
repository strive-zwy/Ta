package com.agent.ta.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import com.agent.ta.util.VoicePlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal object ChatMessageMergePolicy {
    fun visibleUpdates(
        current: List<ChatMessageEntity>,
        allMessages: List<ChatMessageEntity>,
        newestLoadedCreatedAt: Long
    ): List<ChatMessageEntity> {
        val loadedIds = current.mapTo(mutableSetOf()) { it.id }
        return allMessages.filter { it.id in loadedIds || it.createdAt > newestLoadedCreatedAt }
    }

    fun merge(
        current: List<ChatMessageEntity>,
        updates: List<ChatMessageEntity>
    ): List<ChatMessageEntity> {
        if (updates.isEmpty()) return current
        return (current + updates)
            .associateBy { it.id }
            .values
            .sortedWith(compareBy<ChatMessageEntity> { it.createdAt }.thenBy { it.id })
    }
}

class ChatViewModel : ViewModel() {

    private val appContext = com.agent.ta.TaApplication.instance!!
    private val chatDao = ServiceLocator.chatMessageDao
    private val interactor = ChatInteractor(appContext)
    private val voicePlayer = VoicePlayer(appContext)
    private val activeAgentManager = ServiceLocator.activeAgentManager

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _hasMoreOlder = MutableStateFlow(false)
    val hasMoreOlder: StateFlow<Boolean> = _hasMoreOlder.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var newestLoadedCreatedAt: Long = 0L
    private var oldestLoadedCreatedAt: Long = Long.MAX_VALUE
    private var currentAgentId: Long? = null
    private var newMessageObserver: Job? = null

    init {
        viewModelScope.launch {
            activeAgentManager.activeAgentId.collect { agentId ->
                newMessageObserver?.cancel()
                newMessageObserver = null
                currentAgentId = agentId
                if (agentId != null) {
                    loadInitial(agentId)
                    startObservingNewMessages(agentId)
                } else {
                    _messages.value = emptyList()
                    _hasMoreOlder.value = false
                    newestLoadedCreatedAt = 0L
                    oldestLoadedCreatedAt = Long.MAX_VALUE
                }
            }
        }
    }

    private suspend fun loadInitial(agentId: Long) {
        val recent = chatDao.getRecentMessagesDesc(agentId, 50)
        // DESC → 反转为 ASC 用于显示
        _messages.value = recent.reversed()
        if (recent.isNotEmpty()) {
            newestLoadedCreatedAt = recent.first().createdAt
            oldestLoadedCreatedAt = recent.last().createdAt
            _hasMoreOlder.value = recent.size == 50
        } else {
            newestLoadedCreatedAt = 0L
            oldestLoadedCreatedAt = Long.MAX_VALUE
            _hasMoreOlder.value = false
        }
    }

    private fun startObservingNewMessages(agentId: Long) {
        newMessageObserver = viewModelScope.launch {
            chatDao.observeAll(agentId)
                .collect { allMessages ->
                    val updates = ChatMessageMergePolicy.visibleUpdates(
                        current = _messages.value,
                        allMessages = allMessages,
                        newestLoadedCreatedAt = newestLoadedCreatedAt
                    )
                    if (updates.isNotEmpty()) {
                        val merged = ChatMessageMergePolicy.merge(_messages.value, updates)
                        _messages.value = merged
                        newestLoadedCreatedAt = merged.lastOrNull()?.createdAt ?: newestLoadedCreatedAt
                    }
                }
        }
    }

    fun loadMoreOlder() {
        val agentId = currentAgentId ?: return
        if (_isLoadingMore.value || !_hasMoreOlder.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val older = chatDao.getMessagesBeforeDesc(agentId, oldestLoadedCreatedAt, 20)
                if (older.isNotEmpty()) {
                    oldestLoadedCreatedAt = older.last().createdAt
                    _hasMoreOlder.value = older.size == 20
                    // 前置插入（反转为 ASC）
                    _messages.value = older.reversed() + _messages.value
                } else {
                    _hasMoreOlder.value = false
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    val playingPath: StateFlow<String?> = voicePlayer.currentPath
    val isPlaying: StateFlow<Boolean> = voicePlayer.isPlaying

    /** Agent 是否正在生成回复（驱动 UI 显示"正在输入中"指示器） */
    val isReplying: StateFlow<Boolean> = ChatInteractor.isReplying

    fun updateInput(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value
        if (text.isBlank()) return
        interactor.sendUserMessage(text)
        _inputText.value = ""
    }

    /**
     * 用户在输入框追加 emoji（不直接发送）
     *
     * 允许"emoji + 文字"组合发送，更自然。
     */
    fun appendEmoji(emoji: String) {
        _inputText.value = _inputText.value + emoji
    }

    fun toggleVoicePlay(audioPath: String) {
        voicePlayer.togglePlay(audioPath)
    }

    override fun onCleared() {
        newMessageObserver?.cancel()
        voicePlayer.release()
        super.onCleared()
    }
}
