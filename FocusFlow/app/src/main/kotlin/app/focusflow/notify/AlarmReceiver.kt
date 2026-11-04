package app.focusflow.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.focusflow.util.Bg

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Bg.executor.execute {
            try {
                Scheduler.onAlarm(app)
            } catch (t: Throwable) {
                Log.e("FocusFlow", "alarm handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
