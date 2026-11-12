package com.xemmy.mmbank.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.xemmy.mmbank.domain.auth.PinHasher
import com.xemmy.mmbank.ui.theme.MMTheme

/**
 * A numeric keypad for the app PIN: dots for what's typed, 0-9, delete and
 * OK (enabled from [PinHasher.MIN_LENGTH] digits). [onSubmit] gets the digits
 * and the pad clears itself, ready for another try.
 */
@Composable
fun PinPad(
    title: String,
    message: String?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    messageIsError: Boolean = false,
) {
    var digits by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    // Reject buzz whenever a new error message appears.
    LaunchedEffect(message, messageIsError) {
        if (messageIsError && message != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MMTheme.colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(maxOf(digits.length, PinHasher.MIN_LENGTH)) { i ->
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (i < digits.length) MMTheme.colors.textPrimary else MMTheme.colors.borderStrong,
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (messageIsError) MaterialTheme.colorScheme.error else MMTheme.colors.textTertiary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(8.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val keyEnabled = enabled && when (key) {
                        "⌫" -> digits.isNotEmpty()
                        "OK" -> digits.length >= PinHasher.MIN_LENGTH
                        else -> digits.length < PinHasher.MAX_LENGTH
                    }
                    TextButton(
                        onClick = {
                            when (key) {
                                "⌫" -> digits = digits.dropLast(1)
                                "OK" -> {
                                    val entered = digits
                                    digits = ""
                                    onSubmit(entered)
                                }
                                else -> digits += key
                            }
                        },
                        enabled = keyEnabled,
                        modifier = Modifier
                            .size(width = 76.dp, height = 56.dp)
                            .semantics { if (key == "⌫") contentDescription = "Delete digit" },
                    ) {
                        Text(key, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.padding(2.dp))
        }
    }
}
