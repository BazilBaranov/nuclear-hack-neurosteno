package dev.neurostenografist.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.neurostenografist.core.Turn
import java.util.concurrent.atomic.AtomicInteger

/** All media I/O and position checks stay off the UI thread. */
internal class SegmentPlayer(private val onState: (Boolean, String?) -> Unit) {
    private val thread = HandlerThread("transcript-player").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var player: MediaPlayer? = null
    @Volatile private var closed = false

    fun play(context: Context, uri: String, turn: Turn) {
        val token = generation.incrementAndGet()
        worker.post {
            release()
            if (closed || token != generation.get()) return@post
            try {
                val media = MediaPlayer()
                player = media
                media.setOnErrorListener { _, _, _ ->
                    finish(token, "Не удалось воспроизвести аудио. Проверьте доступ к исходному файлу")
                    true
                }
                media.setOnCompletionListener { finish(token, null) }
                media.setOnPreparedListener {
                    if (closed || token != generation.get()) return@setOnPreparedListener
                    try {
                        if (turn.startMs >= media.duration) finish(token, "Начало реплики находится за концом аудио")
                        else media.seekTo(turn.startMs, MediaPlayer.SEEK_CLOSEST)
                    } catch (_: Exception) { finish(token, "Не удалось перейти к реплике") }
                }
                media.setOnSeekCompleteListener {
                    if (closed || token != generation.get()) return@setOnSeekCompleteListener
                    val stopAt = try {
                        media.start()
                        state(token, true, null)
                        turn.endMs.coerceAtMost(media.duration.toLong())
                    } catch (_: Exception) {
                        finish(token, "Не удалось начать воспроизведение")
                        return@setOnSeekCompleteListener
                    }
                    worker.post(object : Runnable {
                        override fun run() {
                            if (closed || token != generation.get() || player !== media) return
                            try {
                                if (!media.isPlaying || media.currentPosition >= stopAt) finish(token, null)
                                else worker.postDelayed(this, 30)
                            } catch (_: IllegalStateException) { finish(token, "Воспроизведение прервано") }
                        }
                    })
                }
                media.setDataSource(context.applicationContext, Uri.parse(uri))
                media.prepareAsync()
                state(token, true, null)
            } catch (_: Exception) {
                finish(token, "Не удалось открыть исходное аудио. Выберите файл заново")
            }
        }
    }

    fun stop() {
        val token = generation.incrementAndGet()
        worker.post { release(); state(token, false, null) }
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        worker.post { release(); thread.quitSafely() }
    }

    private fun finish(token: Int, error: String?) {
        if (token != generation.get()) return
        release()
        state(token, false, error)
    }

    private fun state(token: Int, active: Boolean, error: String?) {
        main.post { if (!closed && token == generation.get()) onState(active, error) }
    }

    private fun release() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}
