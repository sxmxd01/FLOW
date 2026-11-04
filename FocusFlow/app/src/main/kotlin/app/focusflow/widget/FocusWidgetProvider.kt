package app.focusflow.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import app.focusflow.Actions
import app.focusflow.MainActivity
import app.focusflow.R
import app.focusflow.data.Block
import app.focusflow.data.StateStore
import app.focusflow.notify.ActionReceiver
import app.focusflow.notify.Scheduler
import app.focusflow.util.TimeFmt
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Large (4×4, resizable) day-plan widget for Android 11+.
 *
 *  • header: date, planned hours / % done, quick "+" to add a block
 *  • hero card: what is on NOW (live countdown + progress + one-tap done) or what is UP NEXT
 *  • scrolling timeline of today's blocks, tap a row to open it, tap the circle to complete it
 */
class FocusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, id, appWidgetManager.getAppWidgetOptions(id)))
        }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.w_list)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId, newOptions))
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.w_list)
    }

    override fun onEnabled(context: Context) {
        Scheduler.reschedule(context)
    }

    companion object {
        private const val REQ_OPEN_HERO = 100
        private const val REQ_NEW = 101
        private const val REQ_OPEN_HEADER = 102
        private const val REQ_ROUTER = 103
        private const val REQ_HERO_DONE = 104

        /** Rebuilds every placed widget instance. Safe to call from any thread. */
        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, FocusWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                mgr.updateAppWidget(id, buildViews(app, id, mgr.getAppWidgetOptions(id)))
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }

        private fun mutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        private fun openApp(ctx: Context, requestCode: Int, action: String, block: Block?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                this.action = action
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (block != null) {
                    putExtra(Actions.EXTRA_BLOCK_ID, block.id)
                    putExtra(Actions.EXTRA_DATE, block.date.toString())
                    data = Uri.parse("focusflow://block/${block.date}/${Uri.encode(block.id)}")
                }
            }
            return PendingIntent.getActivity(
                ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun markDone(ctx: Context, block: Block): PendingIntent {
            val i = Intent(ctx, ActionReceiver::class.java).apply {
                action = Actions.MARK_DONE
                data = Uri.parse("focusflow://widget-done/${block.date}/${Uri.encode(block.id)}")
                putExtra(Actions.EXTRA_BLOCK_ID, block.id)
                putExtra(Actions.EXTRA_DATE, block.date.toString())
            }
            return PendingIntent.getBroadcast(
                ctx, REQ_HERO_DONE, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun buildViews(ctx: Context, widgetId: Int, options: Bundle?): RemoteViews {
            val snap = StateStore.snapshot(ctx)
            val fmt = TimeFmt(ctx)
            val zone = ZoneId.systemDefault()
            val nowDt = LocalDateTime.now()
            val today = nowDt.toLocalDate()
            val nowMin = nowDt.hour * 60 + nowDt.minute
            val nowMs = System.currentTimeMillis()

            val blocks = snap.blocksOn(today)
            val study = blocks.filter { !it.isFixed }
            val planned = study.sumOf { it.dur }
            val doneMinutes = study.sumOf { it.dur * it.pct / 100.0 }
            val pct = if (planned > 0) Math.round(doneMinutes / planned * 100).toInt() else 0

            val views = RemoteViews(ctx.packageName, R.layout.widget_focusflow)

            // ---------- size adaptation ----------
            val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250) ?: 250
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
            views.setViewVisibility(R.id.w_list_container, if (minH >= 200) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.w_stats, if (minW >= 210) View.VISIBLE else View.GONE)

            // ---------- header ----------
            val brand = SpannableStringBuilder("Focus").apply {
                setSpan(ForegroundColorSpan(0xFFE8EAF0.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val s = length
                append("Flow")
                setSpan(ForegroundColorSpan(0xFF95A0B8.toInt()), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            views.setTextViewText(R.id.w_brand, brand)
            views.setTextViewText(
                R.id.w_date,
                today.format(DateTimeFormatter.ofPattern("EEEE · MMM d", Locale.getDefault())),
            )
            val hours = String.format(Locale.US, "%.1f", planned / 60.0).removeSuffix(".0")
            views.setTextViewText(R.id.w_stats, if (planned > 0) "${hours}h · $pct%" else "")
            views.setProgressBar(R.id.w_day_progress, 100, pct.coerceIn(0, 100), false)

            views.setOnClickPendingIntent(R.id.w_header_left, openApp(ctx, REQ_OPEN_HEADER, Intent.ACTION_MAIN, null))
            views.setOnClickPendingIntent(R.id.w_add, openApp(ctx, REQ_NEW, Actions.NEW_BLOCK, null))

            // ---------- hero ----------
            val live = blocks.filter { !it.done && nowMin >= it.start && nowMin < it.end }
            val current = live.firstOrNull { !it.isFixed } ?: live.firstOrNull()
            val upcoming = blocks.filter { !it.done && it.start > nowMin }.minByOrNull { it.start }
            val focus = current ?: upcoming

            if (focus != null) {
                views.setTextViewText(
                    R.id.w_hero_label,
                    ctx.getString(if (current != null) R.string.widget_now else R.string.widget_up_next),
                )
                views.setTextViewText(R.id.w_hero_title, focus.title)
                views.setTextViewText(R.id.w_hero_time, "${fmt.range(focus.start, focus.end)} · ${fmt.duration(focus.dur)}")
                views.setInt(R.id.w_hero_strip, "setBackgroundColor", focus.color)

                val targetMs = Scheduler.epoch(today, if (current != null) current.end else focus.start, zone)
                val remaining = maxOf(0L, targetMs - nowMs)
                views.setViewVisibility(R.id.w_hero_cd_row, View.VISIBLE)
                views.setTextViewText(
                    R.id.w_hero_cd_label,
                    ctx.getString(if (current != null) R.string.widget_ends_in else R.string.widget_starts_in),
                )
                views.setChronometerCountDown(R.id.w_hero_chrono, true)
                views.setChronometer(R.id.w_hero_chrono, SystemClock.elapsedRealtime() + remaining, null, true)

                if (current != null) {
                    val startMs = Scheduler.epoch(today, current.start, zone)
                    val totalMs = maxOf(1L, targetMs - startMs)
                    val p = ((nowMs - startMs) * 100 / totalMs).toInt().coerceIn(0, 100)
                    views.setViewVisibility(R.id.w_hero_progress, View.VISIBLE)
                    views.setProgressBar(R.id.w_hero_progress, 100, p, false)
                } else {
                    views.setViewVisibility(R.id.w_hero_progress, View.GONE)
                }

                if (current != null && !current.isFixed) {
                    views.setViewVisibility(R.id.w_hero_check, View.VISIBLE)
                    views.setOnClickPendingIntent(R.id.w_hero_check, markDone(ctx, current))
                } else {
                    views.setViewVisibility(R.id.w_hero_check, View.GONE)
                }
                views.setOnClickPendingIntent(R.id.w_hero, openApp(ctx, REQ_OPEN_HERO, Actions.OPEN_BLOCK, focus))
            } else {
                views.setInt(R.id.w_hero_strip, "setBackgroundColor", 0xFF596173.toInt())
                views.setViewVisibility(R.id.w_hero_cd_row, View.GONE)
                views.setViewVisibility(R.id.w_hero_progress, View.GONE)
                views.setViewVisibility(R.id.w_hero_check, View.GONE)
                if (blocks.isEmpty()) {
                    views.setTextViewText(R.id.w_hero_label, ctx.getString(R.string.widget_today))
                    views.setTextViewText(R.id.w_hero_title, ctx.getString(R.string.widget_nothing_title))
                    views.setTextViewText(R.id.w_hero_time, ctx.getString(R.string.widget_nothing_sub))
                } else {
                    views.setTextViewText(R.id.w_hero_label, ctx.getString(R.string.widget_all_done))
                    views.setTextViewText(R.id.w_hero_title, ctx.getString(R.string.widget_wrap_title))
                    views.setTextViewText(R.id.w_hero_time, ctx.getString(R.string.widget_wrap_sub, blocks.size, pct))
                }
                views.setOnClickPendingIntent(R.id.w_hero, openApp(ctx, REQ_OPEN_HERO, Intent.ACTION_MAIN, null))
            }

            // ---------- timeline list ----------
            val svc = Intent(ctx, FocusWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.w_list, svc)
            views.setEmptyView(R.id.w_list, R.id.w_empty)

            val template = Intent(ctx, WidgetRouterActivity::class.java).apply {
                action = Actions.ROUTE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            views.setPendingIntentTemplate(
                R.id.w_list,
                PendingIntent.getActivity(
                    ctx, REQ_ROUTER, template,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
                ),
            )
            return views
        }
    }
}
