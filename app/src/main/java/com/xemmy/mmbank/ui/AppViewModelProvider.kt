package com.xemmy.mmbank.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.ui.screens.accounts.AccountsViewModel
import com.xemmy.mmbank.ui.screens.analytics.AnalyticsViewModel
import com.xemmy.mmbank.ui.screens.budgets.CategoryBudgetsViewModel
import com.xemmy.mmbank.ui.screens.cashback.CashbackViewModel
import com.xemmy.mmbank.ui.screens.copilot.CopilotChatViewModel
import com.xemmy.mmbank.ui.screens.dashboard.DashboardViewModel
import com.xemmy.mmbank.ui.screens.goals.GoalsViewModel
import com.xemmy.mmbank.ui.screens.report.MonthlyReportViewModel
import com.xemmy.mmbank.ui.screens.review.ReviewViewModel
import com.xemmy.mmbank.ui.screens.rules.RuleEditorViewModel
import com.xemmy.mmbank.ui.screens.rules.RulesViewModel
import com.xemmy.mmbank.ui.screens.settings.SettingsViewModel
import com.xemmy.mmbank.ui.screens.subscriptions.SubscriptionsViewModel
import com.xemmy.mmbank.ui.screens.transactions.TransactionsViewModel
import com.xemmy.mmbank.ui.screens.transactions.UncategorizedQueueViewModel

/** Central factory wiring every ViewModel to the manual [AppContainer]. */
object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer { DashboardViewModel(mmBankApp().container) }
        initializer { TransactionsViewModel(mmBankApp().container) }
        initializer { AccountsViewModel(mmBankApp().container) }
        initializer { CashbackViewModel(mmBankApp().container) }
        initializer { RulesViewModel(mmBankApp().container) }
        initializer { RuleEditorViewModel(createSavedStateHandle(), mmBankApp().container) }
        initializer { SettingsViewModel(mmBankApp().container) }
        initializer { UncategorizedQueueViewModel(mmBankApp().container) }
        initializer { SubscriptionsViewModel(mmBankApp().container) }
        initializer { AnalyticsViewModel(mmBankApp().container) }
        initializer { GoalsViewModel(mmBankApp().container) }
        initializer { CopilotChatViewModel(mmBankApp().container) }
        initializer { ReviewViewModel(mmBankApp().container) }
        initializer { CategoryBudgetsViewModel(mmBankApp().container) }
        initializer { MonthlyReportViewModel(mmBankApp().container) }
    }

    private fun CreationExtras.mmBankApp(): MMBankApp =
        this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MMBankApp
}
