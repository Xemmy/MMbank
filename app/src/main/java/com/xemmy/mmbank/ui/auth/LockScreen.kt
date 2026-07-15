package com.xemmy.mmbank.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import kotlinx.coroutines.launch

/**
 * Full-screen opaque gate drawn OVER the app content while the app lock is
 * engaged, so the underlying navigation/composition state survives a
 * background->unlock round-trip. All pointer input is consumed here — nothing
 * reaches the ledger underneath.
 *
 * The biometric prompt fires automatically the moment the gate appears; the
 * button re-triggers it after a cancel/lockout.
 */
@Composable
fun LockScreen(
    /** Shows the biometric/device-credential prompt; true = authenticated. */
    authenticate: suspend () -> Boolean,
    onUnlocked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prompting by remember { mutableStateOf(false) }

    fun tryUnlock() {
        if (prompting) return
        prompting = true
        scope.launch {
            try {
                if (authenticate()) onUnlocked()
            } finally {
                prompting = false
            }
        }
    }

    LaunchedEffect(Unit) { tryUnlock() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .obsidianCanvas()
            // Swallow every touch so the (state-preserved) app underneath is
            // unreachable while locked.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = ObsidianTokens.textMeta,
        )
        Text(
            "MMbank is locked",
            style = MaterialTheme.typography.titleLarge,
            color = ObsidianTokens.textPrimary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Text(
            "Unlock with your fingerprint, face, or device PIN.",
            style = MaterialTheme.typography.bodyMedium,
            color = ObsidianTokens.textMeta,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Button(
            onClick = { tryUnlock() },
            enabled = !prompting,
            colors = ButtonDefaults.buttonColors(
                containerColor = ObsidianTokens.textPrimary,
                contentColor = ObsidianTokens.canvas,
            ),
        ) {
            Text("Unlock")
        }
    }
}
