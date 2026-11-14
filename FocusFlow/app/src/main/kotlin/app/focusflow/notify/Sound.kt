package app.focusflow.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Which alert a sound belongs to. Heads-up and day reminders use the START sound. */
enum class SoundKind(val key: String) {
    START("start"), END("end");
    companion object { fun of(key: String?): SoundKind = if (key == "end") END else START }
}

/**
 * Custom alert sounds – one for block starts, one for block ends. Two ways to get them heard:
 *  • channel sound (a system sound was picked) – the OS plays it;
 *  • "force": the channel is silent and we play the sound ourselves, for phones (some Samsung
 *    One UI builds) that hide or ignore per-channel sounds, and for arbitrary audio files.
 * Android freezes a channel's sound after creation, so every change bumps [version], which gives
 * the channels new ids (old ones are deleted by [Notifier.ensureChannels]).
 */
object Sound {
    private const val PREFS = "ff_sound"
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uri(ctx: Context, k: SoundKind): Uri? = prefs(ctx).getString("uri_${k.key}", null)?.let { Uri.parse(it) }
    fun title(ctx: Context, k: SoundKind): String = prefs(ctx).getString("title_${k.key}", null) ?: "Default"
    fun force(ctx: Context, k: SoundKind): Boolean = uri(ctx, k) != null && prefs(ctx).getBoolean("force_${k.key}", false)
    fun version(ctx: Context): Int = prefs(ctx).getInt("ver", 0)

    fun set(ctx: Context, k: SoundKind, uri: Uri, title: String, force: Boolean) {
        prefs(ctx).edit().putString("uri_${k.key}", uri.toString()).putString("title_${k.key}", title)
            .putBoolean("force_${k.key}", force).putInt("ver", version(ctx) + 1).apply()
    }

    fun setForce(ctx: Context, k: SoundKind, force: Boolean) {
        if (uri(ctx, k) == null) return
        prefs(ctx).edit().putBoolean("force_${k.key}", force).putInt("ver", version(ctx) + 1).apply()
    }

    fun clear(ctx: Context, k: SoundKind) {
        prefs(ctx).edit().remove("uri_${k.key}").remove("title_${k.key}").remove("force_${k.key}").putInt("ver", version(ctx) + 1).apply()
    }

    /**
     * Plays the sound for the whole length of the file. [preview] ignores Do-Not-Disturb / silent mode
     * (the user explicitly asked to hear it).
     */
    fun play(ctx: Context, k: SoundKind, preview: Boolean = false) {
        val app = ctx.applicationContext
        val uri = uri(app, k) ?: return
        if (!preview) {
            val am = app.getSystemService(AudioManager::class.java)
            val nm = app.getSystemService(NotificationManager::class.java)
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        }
        try {
            ContextCompat.startForegroundService(app, Intent(app, SoundService::class.java).putExtra(SoundService.EXTRA_URI, uri.toString()))
        } catch (e: Exception) {          // foreground start refused (e.g. inexact alarm): play in-process, still uncapped
            Log.w("FocusFlow", "sound service unavailable, playing in-process", e)
            playInProcess(app, uri)
        }
    }

    private fun playInProcess(app: Context, uri: Uri) {
        main.post {
            stop()
            try {
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
                mp.setDataSource(app, uri)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener { stop() }
                mp.setOnErrorListener { _, _, _ -> stop(); true }
                mp.prepareAsync()
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
