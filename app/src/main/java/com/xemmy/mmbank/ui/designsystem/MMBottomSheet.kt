package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/** Modal bottom sheet for pickers, filters and detail. Solid overlay surface, 28dp top corners, 16dp gutter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MMBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MMTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceOverlay,
        contentColor = colors.textPrimary,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = MMSpacing.gutter)
                .padding(bottom = MMSpacing.xl),
            content = content,
        )
    }
}
