package app.focusflow.util

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Single background thread for file IO / scheduling work so order is always preserved. */
object Bg {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ff-bg").apply { isDaemon = true }
    }

    fun run(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e("FocusFlow", "background task failed", t)
            }
        }
    }
}
