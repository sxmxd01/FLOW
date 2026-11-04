package app.focusflow.data

import android.content.Context
import android.util.AtomicFile
import app.focusflow.notify.Scheduler
import app.focusflow.widget.FocusWidgetProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for the planner data: one JSON file in app-private storage.
 * The web UI reads/writes it through [app.focusflow.FocusBridge]; alarms and the widget read it
 * directly; notification/widget actions mutate it and then tell the UI to refresh.
 */
object StateStore {
    private const val FILE_NAME = "focusflow_state.json"
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        for (l in listeners) runCatching { l() }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE_NAME)

    fun read(ctx: Context): String? = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists() && !File(f.path + ".bak").exists()) return@synchronized null
        try {
            AtomicFile(f).readFully().toString(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }
    }

    fun write(ctx: Context, json: String): Boolean = synchronized(lock) {
        val af = AtomicFile(file(ctx))
        var os: FileOutputStream? = null
        try {
            os = af.startWrite()
            os.write(json.toByteArray(Charsets.UTF_8))
            af.finishWrite(os)
            true
        } catch (e: IOException) {
            if (os != null) af.failWrite(os)
            false
        }
    }

    fun snapshot(ctx: Context): Snapshot = Snapshot.parse(read(ctx))

    /**
     * Sets or toggles a study block's done flag (mirrors the web `chk` handler: done ⇒ pct = 100).
     * Returns the block's new done state, or null if nothing changed.
     */
    fun setDone(ctx: Context, dateKey: String, id: String, force: Boolean?): Boolean? {
        if (id.startsWith("rec:")) return null
        synchronized(lock) {
            val raw = read(ctx) ?: return null
            val root = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                return null
            }
            val arr = root.optJSONObject("tasks")?.optJSONArray(dateKey) ?: return null
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                if (t.optString("id") != id) continue
                if (t.optString("type") == "fixed") return null
                val newDone = force ?: !t.optBoolean("done", false)
                t.put("done", newDone)
                if (newDone) t.put("pct", 100)
                return if (write(ctx, root.toString())) newDone else null
            }
        }
        return null
    }

    /** Called after any change to the data, wherever it came from. */
    fun onChanged(ctx: Context, notifyUi: Boolean) {
        val app = ctx.applicationContext
        Scheduler.reschedule(app)
        FocusWidgetProvider.refreshAll(app)
        if (notifyUi) notifyListeners()
    }
}
