package app.focusflow.notify

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Custom alert sound. Two ways to get it heard:
 *  • channel sound (system ringtone picked) – the OS plays it;
 *  • "force": the channel is silent and we play the sound ourselves, for phones (some Samsung
 *    One UI builds) that hide or ignore per-channel sounds, and for arbitrary audio files.
 * Android freezes a channel's sound after creation, so every change bumps [version], which gives
 * the channels new ids (old ones are deleted by [Notifier.ensureChannels]).
 */
object Sound {
    private const val PREFS = "ff_sound"
    private const val MAX_PLAY_MS = 9_000L
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uri(ctx: Context): Uri? = prefs(ctx).getString("uri", null)?.let { Uri.parse(it) }
    fun title(ctx: Context): String = prefs(ctx).getString("title", null) ?: "Default"
    fun force(ctx: Context): Boolean = uri(ctx) != null && prefs(ctx).getBoolean("force", false)
    fun version(ctx: Context): Int = prefs(ctx).getInt("ver", 0)

    fun set(ctx: Context, uri: Uri, title: String, force: Boolean) {
        prefs(ctx).edit().putString("uri", uri.toString()).putString("title", title)
            .putBoolean("force", force).putInt("ver", version(ctx) + 1).apply()
    }

    fun setForce(ctx: Context, force: Boolean) {
        if (uri(ctx) == null) return
        prefs(ctx).edit().putBoolean("force", force).putInt("ver", version(ctx) + 1).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("uri").remove("title").remove("force").putInt("ver", version(ctx) + 1).apply()
    }

    /** [preview] ignores Do-Not-Disturb / silent mode (the user explicitly asked to hear it). */
    fun play(ctx: Context, preview: Boolean = false) {
        val app = ctx.applicationContext
        val uri = uri(app) ?: return
        if (!preview) {
            val am = app.getSystemService(AudioManager::class.java)
            val nm = app.getSystemService(NotificationManager::class.java)
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        }
        main.post {
            stop()
            try {
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                mp.setDataSource(app, uri)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener { stop() }
                mp.setOnErrorListener { _, _, _ -> stop(); true }
                mp.prepareAsync()
                main.postDelayed({ if (player === mp) stop() }, MAX_PLAY_MS)
            } catch (e: Exception) {
                Log.w("FocusFlow", "sound failed", e)
                stop()
            }
        }
    }

    private fun stop() {
        val mp = player ?: return
        player = null
        try { if (mp.isPlaying) mp.stop() } catch (e: IllegalStateException) { }
        try { mp.release() } catch (e: Exception) { }
    }
}
