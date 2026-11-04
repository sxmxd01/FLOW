package app.focusflow.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.focusflow.util.Bg
import app.focusflow.widget.FocusWidgetProvider

/** Re-arms the alarm chain and refreshes the widget after reboot, updates, clock or permission changes. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val pending = goAsync()
        Bg.executor.execute {
            try {
                if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
                    Scheduler.resetClock(app)
                }
                Notifier.ensureChannels(app)
                Scheduler.reschedule(app)
                FocusWidgetProvider.refreshAll(app)
            } catch (t: Throwable) {
                Log.e("FocusFlow", "system event handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
