package com.agent.ta.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音播放器（ExoPlayer 封装）
 *
 * 功能：
 * - 从文件路径播放语音
 * - 播放状态 Flow 暴露给 UI
 * - 支持 V2 流式播放预留接口
 */
class VoicePlayer(private val context: Context) {

    private var player: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    /**
     * 当前播放音频的总时长（毫秒），STATE_READY 后才有值
     * UI 用这个显示真实语音时长（不再写死 5 秒）
     */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * 播放队列（用于连续播放未听消息）
     */
    private val playQueue = mutableListOf<String>()
    private var isAutoAdvance = false

    /**
     * 从文件播放
     */
    fun playFromFile(filePath: String, autoAdvance: Boolean = false) {
        isAutoAdvance = autoAdvance
        _currentPath.value = filePath
        _durationMs.value = 0L

        releasePlayer()

        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(filePath))))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // 拿到真实时长（ExoPlayer 在 STATE_READY 时 duration 才有效）
                    if (playbackState == Player.STATE_READY) {
                        val d = player?.duration ?: 0L
                        if (d > 0) _durationMs.value = d
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        _isPlaying.value = false
                        _currentPath.value = null
                        if (isAutoAdvance && playQueue.isNotEmpty()) {
                            val next = playQueue.removeAt(0)
                            playFromFile(next, true)
                        }
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }
            })
        }
    }

    /**
     * 暂停
     */
    fun pause() {
        player?.pause()
    }

    /**
     * 恢复
     */
    fun resume() {
        player?.play()
    }

    /**
     * 停止
     */
    fun stop() {
        player?.stop()
        _isPlaying.value = false
        _currentPath.value = null
        playQueue.clear()
    }

    /**
     * 添加到播放队列（连续播放）
     */
    fun addToQueue(filePath: String) {
        playQueue.add(filePath)
    }

    /**
     * 清空队列
     */
    fun clearQueue() {
        playQueue.clear()
    }

    /**
     * 释放资源
     */
    fun release() {
        releasePlayer()
        playQueue.clear()
    }

    /**
     * 切换播放/暂停
     */
    fun togglePlay(filePath: String) {
        if (_currentPath.value == filePath && _isPlaying.value) {
            pause()
        } else if (_currentPath.value == filePath) {
            resume()
        } else {
            playFromFile(filePath)
        }
    }

    /**
     * V2 预留：从流播放
     */
    fun playFromStream(inputStream: java.io.InputStream, format: String) {
        // V2 实现：从 pcm16 流播放
        // 需要 ExoPlayer 的 ProgressiveMediaSource + ByteArrayDataSource
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        _isPlaying.value = false
    }
}
