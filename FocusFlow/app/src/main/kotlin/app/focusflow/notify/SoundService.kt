package app.focusflow.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.focusflow.R

/**
 * Plays a custom alert sound to the very end — however long the file is. A foreground service keeps the
 * process alive for the whole clip (a plain receiver would be killed seconds after the alarm fires).
 * The tiny, silent notification it shows has a Stop button.
 */
class SoundService : Service() {
    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        val uri = intent?.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) { finish(); return START_NOT_STICKY }
        play(uri)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ch_playing_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false); setSound(null, null)
            }
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(getString(R.string.sound_playing))
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_check, getString(R.string.sound_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    private fun play(uri: Uri) {
        release()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, uri)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { finish() }          // no timeout: the whole clip plays
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("FocusFlow", "sound failed", e)
            finish()
        }
    }

    private fun release() {
        val mp = player ?: return
        player = null
        try { if (mp.isPlaying) mp.stop() } catch (e: IllegalStateException) { }
        try { mp.release() } catch (e: Exception) { }
    }

    private fun finish() {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.focusflow.action.STOP_SOUND"
        const val EXTRA_URI = "uri"
        private const val CHANNEL = "ff_playing"
        private const val ID = 424250
    }
}
