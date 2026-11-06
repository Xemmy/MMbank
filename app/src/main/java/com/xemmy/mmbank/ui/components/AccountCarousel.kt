package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.screens.dashboard.AccountRow
import com.xemmy.mmbank.ui.util.asMoneyOrMask

private val ALL_ACCOUNTS_GRADIENT = listOf(Color(0xFF3A3D46), Color(0xFF1C1E24))
private val CARD_SHAPE = RoundedCornerShape(20.dp)

/**
 * Horizontal account picker for the Dashboard: an "All Accounts" aggregate
 * card, one [GlassWalletCard] per real account, then a "Manage" quick-action
 * chip into [ManageAccountsBottomSheet]. Selecting a card drives the
 * transaction feed filter below (see DashboardViewModel.selectAccount) --
 * [selectedAccountId] null means "All Accounts" is active.
 */
@Composable
fun AccountCarousel(
    accounts: List<AccountRow>,
    selectedAccountId: Long?,
    hidden: Boolean,
    allAccountsTotal: Double,
    baseCurrency: String,
    onSelectAccount: (Long?) -> Unit,
    onManageAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item(key = "all_accounts") {
            AllAccountsCard(
                selected = selectedAccountId == null,
                accountCount = accounts.size,
                total = allAccountsTotal,
                currency = baseCurrency,
                hidden = hidden,
                onClick = { onSelectAccount(null) },
            )
        }
        items(accounts, key = { "account_${it.account.id}" }) { row ->
            GlassWalletCard(
                account = row.account,
                balance = row.balance,
                hidden = hidden,
                selected = selectedAccountId == row.account.id,
                onClick = { onSelectAccount(row.account.id) },
            )
        }
        item(key = "manage_accounts_chip") {
            ManageAccountsChip(onClick = onManageAccounts)
        }
    }
}

@Composable
private fun AllAccountsCard(
    selected: Boolean,
    accountCount: Int,
    total: Double,
    currency: String,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 260.dp, height = 160.dp)
            .clip(CARD_SHAPE)
            .background(Brush.verticalGradient(ALL_ACCOUNTS_GRADIENT), CARD_SHAPE)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.22f),
                shape = CARD_SHAPE,
            )
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Icon(
                Icons.Filled.AccountBalanceWallet,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "All Accounts",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "$accountCount account${if (accountCount == 1) "" else "s"}",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                total.asMoneyOrMask(currency, hidden),
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

/** Slim "open account settings" quick action at the end of the carousel -- narrower than a wallet card so it reads as an action, not another account. */
@Composable
private fun ManageAccountsChip(onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 96.dp, height = 160.dp)
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant, CARD_SHAPE)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CARD_SHAPE)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Manage accounts",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Manage",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
