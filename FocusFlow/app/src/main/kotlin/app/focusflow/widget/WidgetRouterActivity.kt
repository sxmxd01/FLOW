package app.focusflow.widget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.focusflow.Actions
import app.focusflow.BlockActions
import app.focusflow.MainActivity

/**
 * Invisible trampoline for widget list rows. A widget collection can only have ONE pending-intent
 * template, so rows come here with an `op` extra and we either complete the block silently or
 * open the app on that block. Finishes immediately; never shows UI.
 */
class WidgetRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(Actions.EXTRA_BLOCK_ID)
        val date = intent.getStringExtra(Actions.EXTRA_DATE)

        if (id != null && date != null) {
            when (intent.getStringExtra(Actions.EXTRA_OP)) {
                Actions.OP_TOGGLE -> BlockActions.setDone(applicationContext, date, id, null)
                else -> startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        action = Actions.OPEN_BLOCK
                        data = Uri.parse("focusflow://block/$date/${Uri.encode(id)}")
                        putExtra(Actions.EXTRA_BLOCK_ID, id)
                        putExtra(Actions.EXTRA_DATE, date)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
