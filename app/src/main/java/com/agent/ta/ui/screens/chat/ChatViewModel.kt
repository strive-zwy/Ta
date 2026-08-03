package com.agent.ta.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import com.agent.ta.util.VoicePlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class ChatViewModel : ViewModel() {

    private val appContext = com.agent.ta.TaApplication.instance!!
    private val chatDao = ServiceLocator.chatMessageDao
    private val interactor = ChatInteractor(appContext)
    private val voicePlayer = VoicePlayer(appContext)

    val messages: StateFlow<List<ChatMessageEntity>> = chatDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        voicePlayer.release()
        super.onCleared()
    }
}
