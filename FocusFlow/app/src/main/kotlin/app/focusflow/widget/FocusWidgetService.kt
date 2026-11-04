package app.focusflow.widget

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.focusflow.Actions
import app.focusflow.R
import app.focusflow.data.Block
import app.focusflow.data.StateStore
import app.focusflow.util.TimeFmt
import java.time.LocalDateTime

/** Feeds today's blocks to the widget's ListView. */
class FocusWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
        private var items: List<Block> = emptyList()
        private var nowMin = 0
        private lateinit var fmt: TimeFmt

        override fun onCreate() {
            fmt = TimeFmt(ctx)
        }

        override fun onDataSetChanged() {
            fmt = TimeFmt(ctx)
            val now = LocalDateTime.now()
            nowMin = now.hour * 60 + now.minute
            items = StateStore.snapshot(ctx).blocksOn(now.toLocalDate())
        }

        override fun onDestroy() {
            items = emptyList()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_item)
            val b = items.getOrNull(position) ?: return rv

            val isNow = !b.done && nowMin >= b.start && nowMin < b.end
            val dim = 0xFF6A7083.toInt()

            rv.setTextViewText(R.id.w_item_time, fmt.time(b.start, suffix = false))
            rv.setTextViewText(R.id.w_item_dur, "${b.dur}m")
            rv.setInt(R.id.w_item_dot, "setColorFilter", b.color)

            val title = SpannableString(b.title)
            if (b.done) {
                title.setSpan(StrikethroughSpan(), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                title.setSpan(ForegroundColorSpan(dim), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            rv.setTextViewText(R.id.w_item_title, title)
            rv.setTextColor(
                R.id.w_item_title,
                when {
                    b.done -> dim
                    b.isFixed -> 0xFFAAB0C0.toInt()
                    else -> 0xFFE8EAF0.toInt()
                },
            )
            rv.setTextColor(R.id.w_item_time, if (isNow) 0xFFC9D3E3.toInt() else 0xFF7C8296.toInt())
            rv.setInt(R.id.w_item_root, "setBackgroundResource", if (isNow) R.drawable.widget_item_now_bg else 0)

            rv.setImageViewResource(
                R.id.w_item_check,
                when {
                    b.isFixed -> R.drawable.ic_lock_small
                    b.done -> R.drawable.ic_check_on
                    else -> R.drawable.ic_check_off
                },
            )

            val open = fillIn(Actions.OP_OPEN, b)
            rv.setOnClickFillInIntent(R.id.w_item_body, open)
            rv.setOnClickFillInIntent(
                R.id.w_item_check,
                if (b.isFixed) open else fillIn(Actions.OP_TOGGLE, b),
            )
            return rv
        }

        private fun fillIn(op: String, b: Block) = Intent().apply {
            putExtra(Actions.EXTRA_OP, op)
            putExtra(Actions.EXTRA_BLOCK_ID, b.id)
            putExtra(Actions.EXTRA_DATE, b.date.toString())
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.let { "${it.id}|${it.date}".hashCode().toLong() } ?: position.toLong()

        override fun hasStableIds(): Boolean = true
    }
}
