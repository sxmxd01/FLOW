package app.focusflow

import android.app.Application
import app.focusflow.notify.Notifier

class FocusFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }
}
