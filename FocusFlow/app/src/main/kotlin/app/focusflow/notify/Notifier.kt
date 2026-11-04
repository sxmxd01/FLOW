package app.focusflow.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.focusflow.Actions
import app.focusflow.MainActivity
import app.focusflow.R
import app.focusflow.data.Block
import app.focusflow.data.Snapshot
import app.focusflow.util.TimeFmt

object Notifier {
    const val CH_START = "ff_block_start"
    const val CH_END = "ff_block_end"
    const val CH_LEAD = "ff_block_lead"

    private const val TEST_ID = 424242

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_START, ctx.getString(R.string.ch_start_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.ch_start_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 90, 180)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_END, ctx.getString(R.string.ch_end_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.ch_end_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LEAD, ctx.getString(R.string.ch_lead_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.ch_lead_desc)
                setShowBadge(false)
            }
        )
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun notifId(b: Block, kind: Scheduler.Kind): Int =
        "${b.id}|${b.date}|${kind.name}".hashCode()

    /** Removes any start / end / heads-up notification of this block (e.g. after Mark done). */
    fun cancelFor(ctx: Context, blockId: String, dateKey: String) {
        val nm = NotificationManagerCompat.from(ctx)
        for (k in arrayOf(Scheduler.Kind.LEAD, Scheduler.Kind.START, Scheduler.Kind.END)) {
            nm.cancel("${blockId}|${dateKey}|${k.name}".hashCode())
        }
    }

    private fun openPendingIntent(ctx: Context, b: Block, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = Actions.OPEN_BLOCK
            data = Uri.parse("focusflow://block/${b.date}/${Uri.encode(b.id)}")
            putExtra(Actions.EXTRA_BLOCK_ID, b.id)
            putExtra(Actions.EXTRA_DATE, b.date.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun markDonePendingIntent(ctx: Context, b: Block, notifId: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            action = Actions.MARK_DONE
            data = Uri.parse("focusflow://done/${b.date}/${Uri.encode(b.id)}")
            putExtra(Actions.EXTRA_BLOCK_ID, b.id)
            putExtra(Actions.EXTRA_DATE, b.date.toString())
            putExtra(Actions.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            ctx, notifId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Decide (from the user's settings + current block state) whether the event deserves a notification. */
    private fun shouldNotify(snap: Snapshot, ev: Scheduler.Event): Boolean {
        val b = ev.block ?: return false
        if (b.done) return false
        if (b.isFixed && !snap.notif.fixed) return false
        return when (ev.kind) {
            Scheduler.Kind.START -> snap.notif.start
            Scheduler.Kind.END -> snap.notif.end
            Scheduler.Kind.LEAD -> snap.notif.leadMin > 0
            Scheduler.Kind.ROLLOVER -> false
        }
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, snap: Snapshot, ev: Scheduler.Event) {
        val b = ev.block ?: return
        if (!shouldNotify(snap, ev)) return
        if (!notificationsAllowed(ctx)) return

        val fmt = TimeFmt(ctx)
        val id = notifId(b, ev.kind)
        val range = fmt.range(b.start, b.end)
        val durText = fmt.duration(b.dur)

        val next = snap.blocksOn(b.date)
            .filter { it.id != b.id && it.start >= b.end && !it.done }
            .minByOrNull { it.start }
        val nextLine = if (next != null)
            ctx.getString(R.string.n_next, next.title, fmt.time(next.start))
        else ctx.getString(R.string.n_last)

        val channel: String
        val title: String
        val text: String
        val big: String
        val timeout: Long

        when (ev.kind) {
            Scheduler.Kind.START -> {
                channel = Notifier.CH_START
                title = ctx.getString(R.string.n_start_title, b.title)
                text = ctx.getString(R.string.n_start_text, range, durText)
                big = listOfNotNull(text, b.subjectName, nextLine).joinToString("\n")
                timeout = b.dur * 60_000L
            }
            Scheduler.Kind.END -> {
                channel = Notifier.CH_END
                title = ctx.getString(R.string.n_end_title, b.title)
                text = if (b.isFixed) nextLine else ctx.getString(R.string.n_end_text_study)
                big = if (b.isFixed) nextLine else text + "\n" + nextLine
                timeout = 30 * 60_000L
            }
            else -> { // LEAD
                channel = Notifier.CH_LEAD
                title = ctx.getString(R.string.n_lead_title, b.title, snap.notif.leadMin)
                text = ctx.getString(R.string.n_start_text, range, durText)
                big = listOfNotNull(text, b.subjectName).joinToString("\n")
                timeout = snap.notif.leadMin * 60_000L
            }
        }

        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setColor(b.color)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(ev.time)
            .setTimeoutAfter(timeout)
            .setContentIntent(openPendingIntent(ctx, b, id))

        if (!b.subjectName.isNullOrEmpty()) builder.setSubText(b.subjectName)

        if (ev.kind == Scheduler.Kind.END && !b.isFixed) {
            builder.addAction(
                R.drawable.ic_check,
                ctx.getString(R.string.n_action_done),
                markDonePendingIntent(ctx, b, id),
            )
        }

        NotificationManagerCompat.from(ctx).notify(id, builder.build())
    }

    /** Used by Settings → "Send test notification". Returns false if notifications are blocked. */
    @SuppressLint("MissingPermission")
    fun postTest(ctx: Context): Boolean {
        if (!notificationsAllowed(ctx)) return false
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, TEST_ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_START)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(ctx.getString(R.string.n_test_title))
            .setContentText(ctx.getString(R.string.n_test_text))
            .setColor(0xFF95A0B8.toInt())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(ctx).notify(TEST_ID, n)
        return true
    }
}
