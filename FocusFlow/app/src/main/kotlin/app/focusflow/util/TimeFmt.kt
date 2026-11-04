package app.focusflow.util

import android.content.Context
import android.text.format.DateFormat
import java.util.Locale

/** Formats minutes-from-midnight the way the system clock is configured (12h / 24h). */
class TimeFmt(ctx: Context) {
    private val is24 = DateFormat.is24HourFormat(ctx)

    fun time(minutes: Int, suffix: Boolean = true): String {
        val m = ((minutes % 1440) + 1440) % 1440
        val h = m / 60
        val mm = m % 60
        if (is24) return String.format(Locale.getDefault(), "%02d:%02d", h, mm)
        val hh = if (h % 12 == 0) 12 else h % 12
        val base = String.format(Locale.getDefault(), "%d:%02d", hh, mm)
        return if (suffix) base + if (h >= 12) " PM" else " AM" else base
    }

    fun range(start: Int, end: Int): String = "${time(start)} – ${time(end)}"

    fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }
}
