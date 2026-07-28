package com.xemmy.mmbank.ui.screens.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.screens.review.components.BatchApprovalBanner
import com.xemmy.mmbank.ui.screens.review.components.TransactionDetailReviewCard

/**
 * High-detail Review Transactions screen: a batch-approval banner for
 * high-confidence pending suggestions, then one [TransactionDetailReviewCard]
 * per still-uncategorized transaction with its ranked one-tap category picks.
 * Assigning a category (chip tap, bottom-sheet pick, or batch approval) drops
 * the row out of [ReviewViewModel.uiState] automatically -- it's driven off
 * [com.xemmy.mmbank.domain.repository.TransactionRepository.observeUncategorized],
 * so no local queue-popping bookkeeping is needed here.
 */
@Composable
fun ReviewTransactionsScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickerFor by remember { mutableStateOf<Transaction?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Review Transactions (${state.items.size})", style = MaterialTheme.typography.titleLarge)
        }

        if (state.items.isEmpty()) {
            ReviewEmptyState(modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.batchApprovableCount > 0) {
                    item(key = "batch_approval_banner") {
                        BatchApprovalBanner(
                            count = state.batchApprovableCount,
                            onApproveAll = viewModel::approveAllHighConfidence,
                        )
                    }
                }
                items(state.items, key = { it.transaction.id }) { queueItem ->
                    val transaction = queueItem.transaction
                    TransactionDetailReviewCard(
                        transaction = transaction,
                        suggestions = queueItem.suggestions,
                        confidenceLevel = queueItem.confidenceLevel,
                        accountLabel = state.accounts.firstOrNull { it.id == transaction.accountId }?.name,
                        onSelectSuggestion = { suggestion -> viewModel.assignSuggestion(transaction, suggestion) },
                        onMoreCategories = { pickerFor = transaction },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    pickerFor?.let { transaction ->
        CategoryPickerBottomSheet(
            categories = state.categories,
            onSelect = { category ->
                viewModel.assignCategory(transaction, category)
                pickerFor = null
            },
            onCreateNew = { name, colorHex, icon, cap ->
                viewModel.createAndAssignCategory(transaction, name, colorHex, icon, cap)
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }
}

@Composable
private fun ReviewEmptyState(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("All caught up!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Nothing left to review.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
