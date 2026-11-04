package app.focusflow

/** Intent actions / extras shared across the app, notifications and the widget. */
object Actions {
    const val OPEN_BLOCK = "app.focusflow.action.OPEN_BLOCK"
    const val NEW_BLOCK = "app.focusflow.action.NEW_BLOCK"
    const val MARK_DONE = "app.focusflow.action.MARK_DONE"
    const val ROUTE = "app.focusflow.action.ROUTE"

    const val EXTRA_BLOCK_ID = "block_id"
    const val EXTRA_DATE = "date"
    const val EXTRA_NOTIF_ID = "notif_id"
    const val EXTRA_OP = "op"

    const val OP_OPEN = "open"
    const val OP_TOGGLE = "toggle"
}
