package com.xemmy.mmbank.ui.screens.paylater

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xemmy.mmbank.domain.model.Account
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal val PAY_LATER_DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

/** A labelled button showing [date]; tapping opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickField(label: String, date: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(date.format(PAY_LATER_DATE_LABEL))
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** A labelled dropdown over [accounts]; [allowNone] adds a leading "None" entry that picks null. */
@Composable
internal fun AccountPickField(
    label: String,
    accounts: List<Account>,
    selectedId: Long?,
    allowNone: Boolean,
    onPick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = accounts.firstOrNull { it.id == selectedId }?.name ?: if (allowNone) "None" else "Choose an account"
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("None") }, onClick = { onPick(null); open = false })
            }
            accounts.forEach { a ->
                DropdownMenuItem(text = { Text(a.name) }, onClick = { onPick(a.id); open = false })
            }
        }
    }
}
