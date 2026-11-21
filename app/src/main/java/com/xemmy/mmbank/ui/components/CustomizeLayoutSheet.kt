package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.layout.ScreenLayout
import com.xemmy.mmbank.domain.layout.SectionSpec
import com.xemmy.mmbank.ui.designsystem.ReorderHandle
import com.xemmy.mmbank.ui.designsystem.liftWhileDragging

/**
 * Lets the user reorder and show/hide the boxes of a screen. Changes apply live to the screen behind.
 * A dialog rather than a bottom sheet, like [ManageAccountsBottomSheet].
 */
@Composable
fun CustomizeLayoutSheet(
    title: String,
    layout: ScreenLayout,
    specs: List<SectionSpec>,
    onMove: (String, Int) -> Unit,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val byId = remember(specs) { specs.associateBy { it.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Drag the ≡ grip to reorder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(layout.order, key = { it }) { id ->
                        val spec = byId[id] ?: return@items
                        var lifted by remember { mutableStateOf(false) }
                        val shown = id !in layout.hidden
                        Row(
                            Modifier
                                .animateItem()
                                .fillMaxWidth()
                                .liftWhileDragging(lifted)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReorderHandle(onMove = { onMove(id, it) }, onDragStateChange = { lifted = it })
                            Column(Modifier.weight(1f).alpha(if (shown) 1f else 0.5f)) {
                                Text(spec.label, style = MaterialTheme.typography.titleMedium)
                                if (!spec.removable) {
                                    Text(
                                        "Always shown",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = shown,
                                onCheckedChange = { onToggle(id) },
                                enabled = spec.removable,
                            )
                        }
                        HorizontalDivider(Modifier.animateItem())
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset to default") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
