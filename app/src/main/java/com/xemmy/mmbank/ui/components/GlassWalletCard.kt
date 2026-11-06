package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.ui.util.asMoneyOrMask

/** Gradient stops per [AccountType] — purely decorative, kept as data rather than logic. */
private fun walletGradient(type: AccountType): List<Color> = when (type) {
    AccountType.CHECKING -> listOf(Color(0xFF4568DC), Color(0xFFB06AB3))
    AccountType.SAVINGS -> listOf(Color(0xFF11998E), Color(0xFF38EF7D))
    AccountType.CASH -> listOf(Color(0xFFF7971E), Color(0xFFFFD200))
    AccountType.CREDIT_CARD -> listOf(Color(0xFF232526), Color(0xFF485563))
    AccountType.LOAN -> listOf(Color(0xFFEB3349), Color(0xFFF45C43))
    AccountType.CRYPTO_SPOT -> listOf(Color(0xFFF2994A), Color(0xFFF2C94C))
    AccountType.INVESTMENT -> listOf(Color(0xFF0F2027), Color(0xFF2C5364))
}

/** "•••• 1234" from the last 4 digits of a raw account/card number; a masked placeholder when there's nothing to show. */
internal fun maskedAccountNumber(rawAccountNumber: String?): String {
    val digits = rawAccountNumber?.trim()?.takeLast(4)
    return if (digits.isNullOrEmpty()) "•••• ····" else "•••• $digits"
}

private val CARD_SHAPE = RoundedCornerShape(20.dp)
private val CARD_WIDTH = 260.dp
private val CARD_HEIGHT = 160.dp

/**
 * Glassmorphism bank-account card: a brand-gradient background (by
 * [AccountType]) with a translucent sheen overlay and a masked account
 * number, in the same visual language as [com.xemmy.mmbank.ui.theme.glassSurface].
 * Tappable to select it in [AccountCarousel]; [selected] draws a bright ring
 * and a checkmark badge so the active card is unambiguous in a scrolling row.
 */
@Composable
fun GlassWalletCard(
    account: Account,
    balance: Double,
    hidden: Boolean,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(width = CARD_WIDTH, height = CARD_HEIGHT)
            .clip(CARD_SHAPE)
            .background(Brush.verticalGradient(walletGradient(account.type)), CARD_SHAPE)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.22f),
                shape = CARD_SHAPE,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // Glass translucent overlay: a soft sheen from the top-left, same
        // "glass over a colored surface" trick as glassSurface's blur fallback.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    ),
                    CARD_SHAPE,
                ),
        )
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                account.bankName?.takeIf { it.isNotBlank() } ?: account.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                maskedAccountNumber(account.accountNumber),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                balance.asMoneyOrMask(account.currencyCode, hidden),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f)),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = Color(0xFF1B1B1B),
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}
