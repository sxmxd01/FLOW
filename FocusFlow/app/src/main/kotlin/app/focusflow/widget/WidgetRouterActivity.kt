package app.focusflow.widget

import android.app.Activity
import android.os.Bundle
import app.focusflow.Actions
import app.focusflow.BlockActions
import app.focusflow.Intents

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
                else -> startActivity(Intents.app(this, Actions.OPEN_BLOCK, id, date))
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
