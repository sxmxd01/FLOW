package app.focusflow.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.focusflow.Actions
import app.focusflow.BlockActions
import app.focusflow.util.Bg

/** Handles the "Mark done" button on notifications and in the widget's hero card. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Actions.MARK_DONE) return
        val id = intent.getStringExtra(Actions.EXTRA_BLOCK_ID) ?: return
        val date = intent.getStringExtra(Actions.EXTRA_DATE) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Bg.executor.execute {
            try {
                BlockActions.setDone(app, date, id, true)
            } catch (t: Throwable) {
                Log.e("FocusFlow", "mark done failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
