package com.xemmy.mmbank.ui.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MMbankTheme

/**
 * Confirmation dialog. Dialogs are for confirmations only (pickers and filters
 * belong in [MMBottomSheet]); a [DialogKind.DESTRUCTIVE] dialog paints its
 * confirm button in the danger colour.
 */
@Composable
fun MMDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    kind: DialogKind = DialogKind.CONFIRM,
    dismissLabel: String = "Cancel",
) {
    val colors = MMTheme.colors
    val confirmColor = when (kind.confirmRole()) {
        ConfirmButtonRole.PRIMARY -> colors.accent
        ConfirmButtonRole.DANGER -> colors.danger
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surfaceOverlay,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = confirmColor),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
            ) { Text(dismissLabel) }
        },
    )
}

@MMPreviews
@Composable
private fun MMDialogPreview() {
    MMbankTheme {
        MMDialog(
            title = "Delete account?",
            text = "Its transactions will be kept but unlinked.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
            kind = DialogKind.DESTRUCTIVE,
        )
    }
}
