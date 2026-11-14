package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Amount entry: decimal keypad, Next on the keyboard, live input filtering and
 * an inline error ("Enter an amount like 125.50"). The error is hidden for a
 * blank field until [showRequiredError] (set it after the first save attempt).
 */
@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Amount",
    currencyCode: String? = null,
    maxFractionDigits: Int = 2,
    required: Boolean = false,
    showRequiredError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val error = if (value.isBlank() && !showRequiredError) {
        null
    } else {
        moneyFieldError(value, maxFractionDigits, required)
    }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(sanitizeMoneyInput(it)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = if (currencyCode != null) {
            { Text(currencyCode) }
        } else {
            null
        },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.small,
    )
}

/** Search box: Search on the keyboard, a clear button once there is text. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
        shape = MaterialTheme.shapes.small,
    )
}
