package app.focusflow

import android.content.Context
import app.focusflow.data.StateStore
import app.focusflow.notify.Notifier

/** Native-side edits of a block (from notifications and the widget). */
object BlockActions {
    /** [force] = null toggles. Returns true if the data changed. */
    fun setDone(ctx: Context, dateKey: String, id: String, force: Boolean?): Boolean {
        val app = ctx.applicationContext
        val result = StateStore.setDone(app, dateKey, id, force) ?: return false
        if (result) Notifier.cancelFor(app, id, dateKey)
        StateStore.onChanged(app, notifyUi = true)
        return true
    }
}
