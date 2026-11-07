package app.focusflow.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.focusflow.Actions
import app.focusflow.Intents
import app.focusflow.R
import app.focusflow.data.Block
import app.focusflow.data.Snapshot
import app.focusflow.util.TimeFmt

object Notifier {
    private const val CH_START = "ff_block_start"
    private const val CH_END = "ff_block_end"
    private const val CH_LEAD = "ff_block_lead"
    private const val CH_DAY = "ff_day"
    private const val TEST_ID = 424242
    private const val DAY_START_ID = 424243
    private const val DAY_END_ID = 424244

    /** Channel ids carry the sound version: Android can't change a channel's sound after creation. */
    private fun id(ctx: Context, base: String) = "${base}_v${Sound.version(ctx)}"

    fun ensureChannels(ctx: Context) {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        val ver = "_v${Sound.version(app)}"
        nm.notificationChannels.filter { it.id.startsWith("ff_") && !it.id.endsWith(ver) }.forEach { nm.deleteNotificationChannel(it.id) }

        val soundUri = Sound.uri(app)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

        fun make(base: String, name: Int, desc: Int, importance: Int, vib: LongArray?) {
            val ch = NotificationChannel(id(app, base), app.getString(name), importance).apply {
                description = app.getString(desc)
                setShowBadge(false)
                if (vib != null) { enableVibration(true); vibrationPattern = vib }
                when {
                    Sound.force(app) -> setSound(null, null)                      // we play it ourselves
                    soundUri != null -> setSound(soundUri, attrs)                // let the system play it
                }
            }
            nm.createNotificationChannel(ch)
        }
        make(CH_START, R.string.ch_start_name, R.string.ch_start_desc, NotificationManager.IMPORTANCE_HIGH, longArrayOf(0, 180, 90, 180))
        make(CH_END, R.string.ch_end_name, R.string.ch_end_desc, NotificationManager.IMPORTANCE_HIGH, longArrayOf(0, 120))
        make(CH_LEAD, R.string.ch_lead_name, R.string.ch_lead_desc, NotificationManager.IMPORTANCE_DEFAULT, null)
        make(CH_DAY, R.string.ch_day_name, R.string.ch_day_desc, NotificationManager.IMPORTANCE_DEFAULT, null)
    }

    fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun notifId(b: Block, kind: Scheduler.Kind): Int = "${b.id}|${b.date}|${kind.name}".hashCode()

    /** Removes any start / end / heads-up notification of this block (e.g. after Mark done). */
    fun cancelFor(ctx: Context, blockId: String, dateKey: String) {
        val nm = NotificationManagerCompat.from(ctx)
        for (k in arrayOf(Scheduler.Kind.LEAD, Scheduler.Kind.START, Scheduler.Kind.END)) nm.cancel("$blockId|$dateKey|${k.name}".hashCode())
    }

    private fun shouldNotify(snap: Snapshot, ev: Scheduler.Event): Boolean {
        val b = ev.block ?: return false
        if (b.done || (b.isFixed && !snap.notif.fixed)) return false
        return when (ev.kind) {
            Scheduler.Kind.START -> snap.notif.start
            Scheduler.Kind.END -> snap.notif.end
            Scheduler.Kind.LEAD -> snap.notif.leadMin > 0
            else -> false
        }
    }

    fun post(ctx: Context, snap: Snapshot, ev: Scheduler.Event) {
        if (!notificationsAllowed(ctx)) return
        when (ev.kind) {
            Scheduler.Kind.DAY_START, Scheduler.Kind.DAY_END -> postDay(ctx, snap, ev)
            Scheduler.Kind.ROLLOVER -> Unit
            else -> if (shouldNotify(snap, ev)) postBlock(ctx, snap, ev)
        }
    }

    @SuppressLint("MissingPermission")
    private fun postBlock(ctx: Context, snap: Snapshot, ev: Scheduler.Event) {
        val b = ev.block ?: return
        val fmt = TimeFmt(ctx)
        val nid = notifId(b, ev.kind)
        val range = fmt.range(b.start, b.end)
        val durText = fmt.duration(b.dur)
        val next = snap.blocksOn(b.date).filter { it.id != b.id && it.start >= b.end && !it.done }.minByOrNull { it.start }
        val nextLine = if (next != null) ctx.getString(R.string.n_next, next.title, fmt.time(next.start)) else ctx.getString(R.string.n_last)

        val channel: String; val title: String; val text: String; val big: String; val timeout: Long
        when (ev.kind) {
            Scheduler.Kind.START -> {
                channel = id(ctx, CH_START); title = ctx.getString(R.string.n_start_title, b.title)
                text = ctx.getString(R.string.n_start_text, range, durText)
                big = listOfNotNull(text, b.subjectName, nextLine).joinToString("\n"); timeout = b.dur * 60_000L
            }
            Scheduler.Kind.END -> {
                channel = id(ctx, CH_END); title = ctx.getString(R.string.n_end_title, b.title)
                text = if (b.isFixed) nextLine else ctx.getString(R.string.n_end_text_study)
                big = if (b.isFixed) nextLine else text + "\n" + nextLine; timeout = 30 * 60_000L
            }
            else -> {
                channel = id(ctx, CH_LEAD); title = ctx.getString(R.string.n_lead_title, b.title, snap.notif.leadMin)
                text = ctx.getString(R.string.n_start_text, range, durText)
                big = listOfNotNull(text, b.subjectName).joinToString("\n"); timeout = snap.notif.leadMin * 60_000L
            }
        }
        val builder = base(ctx, channel, title, text, b.color)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setWhen(ev.time).setShowWhen(true).setTimeoutAfter(timeout)
            .setContentIntent(Intents.openApp(ctx, nid, Actions.OPEN_BLOCK, b))
        if (!b.subjectName.isNullOrEmpty()) builder.setSubText(b.subjectName)
        if (ev.kind == Scheduler.Kind.END && !b.isFixed) {
            builder.addAction(R.drawable.ic_check, ctx.getString(R.string.n_action_done), Intents.markDone(ctx, nid, b.id, b.date.toString(), nid))
        }
        deliver(ctx, nid, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun postDay(ctx: Context, snap: Snapshot, ev: Scheduler.Event) {
        val blocks = snap.blocksOn(ev.date).filter { !it.isFixed }
        val info = snap.dayInfo(ev.date)
        val start = ev.kind == Scheduler.Kind.DAY_START
        if (start && info.startedAt != null) return
        if (!start && (info.endedAt != null || blocks.isEmpty())) return
        val mins = blocks.sumOf { it.dur }
        val open = blocks.count { !it.done }
        val title: String; val text: String; val action: String; val label: Int
        if (start) {
            title = ctx.getString(R.string.n_daystart_title)
            text = if (blocks.isEmpty()) ctx.getString(R.string.n_daystart_empty)
                   else ctx.getString(R.string.n_daystart_text, blocks.size, TimeFmt(ctx).duration(mins))
            action = Actions.START_DAY; label = R.string.n_action_start_day
        } else {
            title = ctx.getString(R.string.n_dayend_title)
            text = ctx.getString(R.string.n_dayend_text, blocks.size - open, blocks.size)
            action = Actions.END_DAY; label = R.string.n_action_end_day
        }
        val nid = if (start) DAY_START_ID else DAY_END_ID
        val open1 = Intents.openApp(ctx, nid, action)
        val n = base(ctx, id(ctx, CH_DAY), title, text, 0xFF95A0B8.toInt())
            .setWhen(ev.time).setShowWhen(true).setTimeoutAfter(3 * 3_600_000L)
            .setContentIntent(open1).addAction(R.drawable.ic_stat_focus, ctx.getString(label), open1).build()
        deliver(ctx, nid, n)
    }

    private fun base(ctx: Context, channel: String, title: String, text: String, color: Int) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_focus).setContentTitle(title).setContentText(text)
            .setColor(color).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)

    @SuppressLint("MissingPermission")
    private fun deliver(ctx: Context, id: Int, n: android.app.Notification) {
        NotificationManagerCompat.from(ctx).notify(id, n)
        if (Sound.force(ctx)) Sound.play(ctx)
    }

    /** Settings → Alerts → Send. Returns false if notifications are blocked. */
    fun postTest(ctx: Context): Boolean {
        if (!notificationsAllowed(ctx)) return false
        ensureChannels(ctx)
        val n = base(ctx, id(ctx, CH_START), ctx.getString(R.string.n_test_title), ctx.getString(R.string.n_test_text), 0xFF95A0B8.toInt())
            .setContentIntent(Intents.openApp(ctx, TEST_ID)).build()
        deliver(ctx, TEST_ID, n)
        return true
    }
}
