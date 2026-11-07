package app.focusflow.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.focusflow.data.Block
import app.focusflow.data.Snapshot
import app.focusflow.data.StateStore
import app.focusflow.widget.FocusWidgetProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Chain scheduler: exactly ONE alarm is ever pending – the next block boundary (start, end,
 * optional heads-up, or midnight rollover for the widget). When it fires we post everything that
 * is due, refresh the widget and arm the next one. This is cheap, survives edits to the plan,
 * and never leaves hundreds of stale alarms behind.
 */
object Scheduler {
    enum class Kind { LEAD, START, END, DAY_START, DAY_END, ROLLOVER }

    data class Event(val time: Long, val kind: Kind, val block: Block?, val date: LocalDate)

    private const val PREFS = "ff_sched"
    private const val KEY_LAST = "last_processed"
    private const val REQ_ALARM = 7001
    private const val STALE_MS = 15 * 60_000L          // don't announce things that are > 15 min old
    private const val LOOKBACK_MS = 6 * 3_600_000L     // catch-up window after reboot / doze
    private const val LOOKAHEAD_MS = 8 * 86_400_000L   // how far ahead we look for the next event

    /** Wall-clock → epoch. Handles minute == 1440 (block ending at midnight) and DST gaps. */
    fun epoch(date: LocalDate, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val extraDays = minute / 1440
        val m = minute % 1440
        return LocalDateTime.of(date.plusDays(extraDays.toLong()), LocalTime.of(m / 60, m % 60))
            .atZone(zone).toInstant().toEpochMilli()
    }

    private fun events(snap: Snapshot, from: Long, to: Long): List<Event> {
        val zone = ZoneId.systemDefault()
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(1)
        val lead = snap.notif.leadMin
        val out = ArrayList<Event>()
        var d = first
        while (!d.isAfter(last)) {
            for (b in snap.blocksOn(d)) {
                val s = epoch(d, b.start, zone)
                val e = epoch(d, b.end, zone)
                if (lead > 0) out += Event(s - lead * 60_000L, Kind.LEAD, b, d)
                out += Event(s, Kind.START, b, d)
                out += Event(e, Kind.END, b, d)
            }
            if (snap.notif.dayStart) out += Event(epoch(d, snap.wake, zone), Kind.DAY_START, null, d)
            if (snap.notif.dayEnd) out += Event(epoch(d, snap.sleep - 30, zone), Kind.DAY_END, null, d)
            out += Event(epoch(d.plusDays(1), 0, zone) + 2_000L, Kind.ROLLOVER, null, d)
            d = d.plusDays(1)
        }
        return out.filter { it.time > from && it.time <= to }.sortedBy { it.time }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lastProcessed(ctx: Context, now: Long): Long {
        val p = prefs(ctx)
        var last = p.getLong(KEY_LAST, 0L)
        if (last == 0L || last > now + 86_400_000L) {   // first run, or the clock went backwards a lot
            last = now
            p.edit().putLong(KEY_LAST, last).commit()
        }
        return last
    }

    /** Called when the system clock / timezone changed: forget the old timeline. */
    fun resetClock(ctx: Context) {
        prefs(ctx.applicationContext).edit().putLong(KEY_LAST, System.currentTimeMillis()).commit()
    }

    private fun alarmIntent(ctx: Context, create: Boolean = true): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, REQ_ALARM,
            Intent(ctx, AlarmReceiver::class.java).setAction("app.focusflow.action.ALARM"),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Cheap check used on app open: only (re)arm when no alarm is pending (e.g. after a force-stop). */
    fun ensureArmed(ctx: Context) {
        if (alarmIntent(ctx.applicationContext, create = false) == null) reschedule(ctx)
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** (Re)arm the single pending alarm for the next event after the last processed moment. */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(app)!!
        val now = System.currentTimeMillis()
        val last = lastProcessed(app, now)
        val snap = StateStore.snapshot(app)
        val next = events(snap, maxOf(last, now - LOOKBACK_MS), now + LOOKAHEAD_MS).firstOrNull()

        am.cancel(pi)
        if (next == null) return
        val trigger = maxOf(next.time, now + 1_000L)

        if (canScheduleExact(app)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                return
            } catch (e: SecurityException) {
                // permission revoked between the check and the call – fall through
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
    }

    /** The alarm fired: announce what is due, refresh the widget, arm the next boundary. */
    fun onAlarm(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val last = lastProcessed(app, now)
        val snap = StateStore.snapshot(app)
        val due = events(snap, maxOf(last, now - LOOKBACK_MS), now)

        prefs(app).edit().putLong(KEY_LAST, now).commit()

        for (ev in due) {
            if (ev.kind == Kind.ROLLOVER) continue
            if (now - ev.time > STALE_MS) continue
            Notifier.post(app, snap, ev)
        }
        FocusWidgetProvider.refreshAll(app)
        reschedule(app)
    }
}
