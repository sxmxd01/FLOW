package app.focusflow

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.focusflow.data.Block
import app.focusflow.notify.ActionReceiver

/** The only place PendingIntents / launch intents are built (notifications, widget, router). */
object Intents {
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun blockUri(prefix: String, date: String, id: String): Uri =
        Uri.parse("focusflow://$prefix/$date/${Uri.encode(id)}")

    /** Intent that opens the app (optionally on a block, or into Start/End day). */
    fun app(ctx: Context, action: String, blockId: String? = null, date: String? = null): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (blockId != null && date != null) {
                putExtra(Actions.EXTRA_BLOCK_ID, blockId)
                putExtra(Actions.EXTRA_DATE, date)
                data = blockUri("block", date, blockId)
            }
        }

    fun openApp(ctx: Context, requestCode: Int, action: String = Intent.ACTION_MAIN, block: Block? = null): PendingIntent =
        PendingIntent.getActivity(ctx, requestCode, app(ctx, action, block?.id, block?.date?.toString()), FLAGS)

    fun markDone(ctx: Context, requestCode: Int, blockId: String, date: String, notifId: Int = 0): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            action = Actions.MARK_DONE
            data = blockUri("done", date, blockId)
            putExtra(Actions.EXTRA_BLOCK_ID, blockId)
            putExtra(Actions.EXTRA_DATE, date)
            putExtra(Actions.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(ctx, requestCode, i, FLAGS)
    }
}
