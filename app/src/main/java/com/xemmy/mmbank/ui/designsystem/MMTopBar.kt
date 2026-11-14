package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MMbankTheme

/**
 * Screen header. Tab roots (no [onBack]) get a large title that collapses on
 * scroll; pushed screens get a small title and a back arrow. The container is
 * transparent so the root canvas shows through, and the bar owns the
 * status-bar inset (M3's default window insets).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MMTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val c = MMTheme.colors
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
        navigationIconContentColor = c.textPrimary,
        titleContentColor = c.textPrimary,
        actionIconContentColor = c.textSecondary,
    )
    val titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (onBack == null) {
        LargeTopAppBar(
            title = titleContent,
            modifier = modifier,
            actions = actions,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(
            title = titleContent,
            modifier = modifier,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = actions,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}

/**
 * Screen scaffold: [MMTopBar] plus a transparent body. Tab roots collapse
 * their large title on scroll; pushed screens pin their small title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MMScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = if (onBack == null) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentColor = MMTheme.colors.textPrimary,
        topBar = {
            MMTopBar(title = title, onBack = onBack, actions = actions, scrollBehavior = behavior)
        },
        content = content,
    )
}

@MMPreviews
@Composable
private fun MMScaffoldPreview() {
    MMbankTheme {
        MMScaffold(title = "Accounts", onBack = {}) { padding ->
            Text("Body", modifier = Modifier.padding(padding))
        }
    }
}
