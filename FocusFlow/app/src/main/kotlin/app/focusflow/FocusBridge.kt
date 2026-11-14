package app.focusflow

import android.content.ClipData
import android.content.Intent
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import app.focusflow.data.StateStore
import app.focusflow.notify.Notifier
import app.focusflow.notify.Sound
import app.focusflow.notify.SoundKind
import app.focusflow.util.Bg
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Exposed to the web UI as `window.FFNative`. Everything here is called from the bundled
 * (same-origin, offline) page only; external navigation is blocked in [MainActivity].
 */
class FocusBridge(private val activity: MainActivity) {
    private val app = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable {
        Bg.run { StateStore.onChanged(app, notifyUi = false) }
    }

    // ------------------------------------------------------------------ state
    @JavascriptInterface
    fun getState(): String = StateStore.read(app) ?: ""

    /** Persist immediately (crash-safe), then re-arm alarms + refresh widget after a short debounce. */
    @JavascriptInterface
    fun saveState(json: String) {
        if (json.length < 2 || json[0] != '{') return
        if (!StateStore.write(app, json)) return
        main.removeCallbacks(syncRunnable)
        main.postDelayed(syncRunnable, 350)
    }

    @JavascriptInterface
    fun getVersion(): String = activity.versionLabel()

    @JavascriptInterface
    fun getSystemDark(): Boolean = activity.isSystemDark()

    /** The web UI tells us which theme is showing so status/navigation bars and the window background match. */
    @JavascriptInterface
    fun setTheme(light: Boolean) { activity.runOnUiThread { activity.setLight(light) } }

    // ------------------------------------------------------------ permissions
    @JavascriptInterface
    fun getStatus(): String = activity.statusJson()

    @JavascriptInterface
    fun requestNotifPermission() {
        activity.runOnUiThread { activity.requestNotificationAccess() }
    }

    @JavascriptInterface
    fun openExactAlarmSettings() {
        activity.runOnUiThread { activity.openExactAlarmSettings() }
    }

    @JavascriptInterface
    fun testNotification(): Boolean = Notifier.postTest(app)

    // ------------------------------------------------------------------ sound
    @JavascriptInterface
    fun getSounds(): String = activity.soundsJson()

    @JavascriptInterface
    fun pickSound(kind: String) { activity.runOnUiThread { activity.pickSound(SoundKind.of(kind)) } }

    @JavascriptInterface
    fun previewSound(kind: String) { Sound.play(app, SoundKind.of(kind), preview = true) }

    @JavascriptInterface
    fun clearSound(kind: String) { Sound.clear(app, SoundKind.of(kind)); activity.runOnUiThread { activity.soundChanged() } }

    @JavascriptInterface
    fun setSoundForce(kind: String, force: Boolean) { Sound.setForce(app, SoundKind.of(kind), force); activity.runOnUiThread { activity.soundChanged() } }

    // --------------------------------------------------------------------- UI
    @JavascriptInterface
    fun ready() {
        activity.runOnUiThread { activity.onWebReady() }
    }

    @JavascriptInterface
    fun setSheetOpen(open: Boolean) {
        activity.runOnUiThread { activity.setBackIntercept(open) }
    }

    @JavascriptInterface
    fun haptic(ms: Int) {
        activity.runOnUiThread {
            val type = when {
                ms < 8 -> HapticFeedbackConstants.CLOCK_TICK
                ms < 12 -> HapticFeedbackConstants.VIRTUAL_KEY
                ms < 20 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.LONG_PRESS
            }
            activity.window.decorView.performHapticFeedback(type)
        }
    }

    // ---------------------------------------------------------------- sharing
    /** Writes the (base64) payload to cache and opens the system share sheet. */
    @JavascriptInterface
    fun shareFile(base64: String, filename: String, mime: String, title: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "focusflow" }
            val dir = File(app.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, safe)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            activity.runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, title)
                    clipData = ClipData.newRawUri(title, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, title))
            }
        } catch (e: Exception) {
            android.util.Log.e("FocusFlow", "share failed", e)
        }
    }
}
