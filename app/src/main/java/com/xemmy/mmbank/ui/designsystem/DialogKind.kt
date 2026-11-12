package com.xemmy.mmbank.ui.designsystem

/** What a confirm dialog asks for: a plain confirmation, or a destructive action (delete, reset, wipe). */
enum class DialogKind { CONFIRM, DESTRUCTIVE }

/** Which colour role the confirm button takes. DANGER is reserved for destructive actions. */
enum class ConfirmButtonRole { PRIMARY, DANGER }

fun DialogKind.confirmRole(): ConfirmButtonRole = when (this) {
    DialogKind.CONFIRM -> ConfirmButtonRole.PRIMARY
    DialogKind.DESTRUCTIVE -> ConfirmButtonRole.DANGER
}
