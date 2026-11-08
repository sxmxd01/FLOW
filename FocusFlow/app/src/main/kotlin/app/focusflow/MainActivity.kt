package app.focusflow

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import app.focusflow.data.StateStore
import app.focusflow.notify.Notifier
import app.focusflow.notify.Scheduler
import app.focusflow.notify.Sound
import app.focusflow.notify.SoundKind
import app.focusflow.util.Bg
import org.json.JSONObject
import java.io.File

/**
 * Hosts the FocusFlow web UI (bundled in assets, served from a virtual https origin so storage
 * and fonts behave exactly like a real site) and wires it to native alerts + the widget.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private val startedAt = SystemClock.uptimeMillis()
    private val prefs by lazy { getSharedPreferences("ff_ui", MODE_PRIVATE) }

    @Volatile
    private var pageReady = false
    private val pendingJs = ArrayList<String>()
    private var soundKind = SoundKind.START
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            js("window.__ffBack&&window.__ffBack()")
        }
    }

    private val stateListener: () -> Unit = {
        runOnUiThread {
            if (pageReady) js("window.__ffExternalChange&&window.__ffExternalChange()")
        }
    }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                // Denied twice: the system won't show the dialog again – send them to settings.
                openAppNotificationSettings()
            }
            pushStatus()
            Bg.run { Scheduler.reschedule(this) }
            maybeAskExactAlarm()
        }

    private val ringtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            if (uri != null) {
                val title = try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) } catch (e: Exception) { null }
                saveSound(soundKind, uri, title ?: "Custom", force = false)
            }
        }

    private val audioFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: SecurityException) { }
                saveSound(soundKind, uri, displayName(uri) ?: "Audio file", force = true)   // arbitrary files need "force"
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    // =============================================================== lifecycle
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !pageReady && SystemClock.uptimeMillis() - startedAt < 2000 }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        webView = WebView(this).apply {
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)

        // The page is laid out between the system bars (and above the keyboard).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        configureWebView()
        webView.loadUrl(APP_URL)

        if (savedInstanceState == null) handleIntent(intent)

        Bg.run { cleanSharedCache() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        StateStore.addListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (pageReady) js("window.__ffResume&&window.__ffResume()")
        Bg.run { Scheduler.ensureArmed(this) }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        StateStore.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        root.removeView(webView)
        webView.removeJavascriptInterface("FFNative")
        webView.destroy()
        super.onDestroy()
    }

    // ================================================================= WebView
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.addJavascriptInterface(FocusBridge(this), "FFNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                    }
                }
                return true // never navigate the app away from its own origin
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageReady = false
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Renderer crashed or was killed to reclaim memory: rebuild instead of crashing.
                runOnUiThread { recreate() }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
    }

    private fun js(code: String) {
        webView.evaluateJavascript(code, null)
    }

    // ========================================================= bridge callbacks
    internal fun onWebReady() {
        pageReady = true
        pushStatus()
        flushPending()
        startPermissionFlow()
    }

    internal fun setBackIntercept(enabled: Boolean) {
        backCallback.isEnabled = enabled
    }

    internal fun statusJson(): String =
        JSONObject()
            .put("notif", Notifier.notificationsAllowed(this))
            .put("exact", Scheduler.canScheduleExact(this))
            .toString()

    private fun pushStatus() {
        if (!pageReady) return
        js("window.__ffStatus&&window.__ffStatus(${JSONObject.quote(statusJson())})")
    }

    // ============================================================ deep links
    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            Actions.OPEN_BLOCK -> {
                val id = i.getStringExtra(Actions.EXTRA_BLOCK_ID)
                if (id != null) {
                    val date = i.getStringExtra(Actions.EXTRA_DATE)
                    pendingJs += "window.__ffOpenBlock&&window.__ffOpenBlock(${JSONObject.quote(id)}, ${if (date != null) JSONObject.quote(date) else "null"})"
                }
            }
            Actions.NEW_BLOCK -> pendingJs += "window.__ffNewBlock&&window.__ffNewBlock()"
            Actions.START_DAY -> pendingJs += "window.__ffStartDay&&window.__ffStartDay()"
            Actions.END_DAY -> pendingJs += "window.__ffEndDay&&window.__ffEndDay()"
        }
        flushPending()
    }

    private fun flushPending() {
        if (!pageReady || pendingJs.isEmpty()) return
        pendingJs.forEach { js(it) }
        pendingJs.clear()
    }

    // ============================================================ permissions
    private fun startPermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_ASKED_NOTIF, false)
        ) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        maybeAskExactAlarm()
    }

    private fun maybeAskExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (Scheduler.canScheduleExact(this) || prefs.getBoolean(KEY_ASKED_EXACT, false)) return
        if (!Notifier.notificationsAllowed(this) || isFinishing) return
        prefs.edit().putBoolean(KEY_ASKED_EXACT, true).apply()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.exact_title)
            .setMessage(R.string.exact_message)
            .setPositiveButton(R.string.exact_allow) { _, _ -> openExactAlarmSettings() }
            .setNegativeButton(R.string.exact_later, null)
            .show()
    }

    /** Settings → Notifications → "Allow". */
    internal fun requestNotificationAccess() {
        val needsRuntimeGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimeGrant) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    internal fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    // ================================================================== sound
    internal fun soundsJson(): String {
        fun one(k: SoundKind) = JSONObject().put("title", Sound.title(this, k)).put("custom", Sound.uri(this, k) != null).put("force", Sound.force(this, k))
        return JSONObject().put("start", one(SoundKind.START)).put("end", one(SoundKind.END)).toString()
    }

    internal fun pickSound(kind: SoundKind) {
        soundKind = kind
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (kind == SoundKind.END) R.string.sound_title_end else R.string.sound_title_start)
            .setItems(arrayOf(getString(R.string.sound_system), getString(R.string.sound_file), getString(R.string.sound_default))) { _, which ->
                when (which) {
                    0 -> ringtoneLauncher.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Sound.uri(this, kind))
                    )
                    1 -> audioFileLauncher.launch(arrayOf("audio/*"))
                    else -> { Sound.clear(this, kind); soundChanged() }
                }
            }.show()
    }

    internal fun soundChanged() {
        Bg.run { Notifier.ensureChannels(this) }
        js("window.__ffSoundChanged&&window.__ffSoundChanged()")
    }

    private fun saveSound(kind: SoundKind, uri: Uri, title: String, force: Boolean) {
        Sound.set(this, kind, uri, title, force)
        soundChanged()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun cleanSharedCache() {
        val dir = File(cacheDir, "shared")
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        private const val BG = 0xFF0A0B0E.toInt()
        private const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"
        private const val KEY_ASKED_NOTIF = "asked_notif"
        private const val KEY_ASKED_EXACT = "asked_exact"
    }
}
