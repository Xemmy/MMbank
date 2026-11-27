package com.xemmy.mmbank.ui.designsystem

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the user has hide-balances on. Provide it once at the app root
 * (from `container.hideBalances`); [MoneyText] reads it and masks itself, so
 * individual screens never thread the flag through.
 */
val LocalHideBalances = compositionLocalOf { false }
