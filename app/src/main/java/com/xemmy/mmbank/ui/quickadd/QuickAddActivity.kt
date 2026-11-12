package com.xemmy.mmbank.ui.quickadd

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.usecase.QuickAdd
import com.xemmy.mmbank.ui.theme.MMbankTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The quick-add sheet (backlog B.5 #1): an amount, an optional merchant, Save.
 * Opened from the launcher shortcut or the Quick Settings tile
 * ([QuickAddTileService]) so a cash purchase is recorded without opening the
 * app. It only ever *adds* one manual spend -- it shows no ledger data -- so
 * it needs no app-lock gate of its own; the tile asks the phone to be
 * unlocked first.
 */
class QuickAddActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MMBankApp).container
        setContent {
            val themeMode by container.themeMode.collectAsStateWithLifecycle()
            MMbankTheme(darkTheme = themeMode.resolve(androidx.compose.foundation.isSystemInDarkTheme())) {
                QuickAddSheet(
                    currency = container.preferences.defaultCurrency,
                    onCancel = { finish() },
                    onSave = { amount, merchant, report ->
                        lifecycleScope.launch {
                            val accounts = runCatching { container.accountRepository.observeActive().first() }.getOrDefault(emptyList())
                            when (val result = QuickAdd.build(amount, merchant, container.preferences.defaultCurrency, accounts, System.currentTimeMillis())) {
                                is QuickAdd.Result.Invalid -> report(result.reason)
                                is QuickAdd.Result.Ok -> runCatching { container.transactionRepository.recordManual(result.transaction) }
                                    .onSuccess {
                                        Toast.makeText(this@QuickAddActivity, "Saved", Toast.LENGTH_SHORT).show()
                                        finish()
                                    }
                                    .onFailure {
                                        container.recordError("QuickAddActivity.save", it)
                                        report("Couldn't save: ${it.message}")
                                    }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickAddSheet(
    currency: String,
    onCancel: () -> Unit,
    onSave: (amount: String, merchant: String, report: (String) -> Unit) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // A scrim over the launcher: tapping outside the card dismisses, like a dialog.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp).clickable(enabled = false) {}) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a cash spend", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    label = { Text("Amount ($currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Where (optional)") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onSave(amount, merchant) { error = it } }) { Text("Save") }
                }
            }
        }
    }
}
