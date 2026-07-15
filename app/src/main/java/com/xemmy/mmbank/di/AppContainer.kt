package com.xemmy.mmbank.di

import android.content.Context
import com.xemmy.mmbank.data.backup.BackupManager
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.prefs.AppPreferences
import com.xemmy.mmbank.data.prefs.SecureCredentialStore
import com.xemmy.mmbank.data.remote.binance.BinanceApi
import com.xemmy.mmbank.data.remote.binance.BinanceSigningInterceptor
import com.xemmy.mmbank.data.remote.binance.BinanceWalletAssetsService
import com.xemmy.mmbank.data.remote.forex.ForexApi
import com.xemmy.mmbank.data.repository.AccountRepositoryImpl
import com.xemmy.mmbank.data.repository.ParsingRuleRepositoryImpl
import com.xemmy.mmbank.data.repository.PortfolioRepositoryImpl
import com.xemmy.mmbank.data.repository.RatesRepositoryImpl
import com.xemmy.mmbank.data.repository.SenderLabelRepositoryImpl
import com.xemmy.mmbank.data.repository.TransactionRepositoryImpl
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.domain.auth.AppLockController
import com.xemmy.mmbank.domain.auth.IdleSessionMonitor
import com.xemmy.mmbank.domain.repository.AccountRepository
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.PortfolioRepository
import com.xemmy.mmbank.domain.repository.RatesRepository
import com.xemmy.mmbank.domain.repository.SenderLabelRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.usecase.BulkUpdateTransactionsUseCase
import com.xemmy.mmbank.domain.usecase.IngestCapturedMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Manual dependency container. The app deliberately avoids a DI framework:
 * everything is a lazy singleton hanging off the Application, reachable from
 * the UI, the NotificationListenerService and the SMS BroadcastReceiver alike.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** App-lifetime scope for hot flows like the rule cache; never cancelled. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferences by lazy { AppPreferences(appContext) }
    val credentialStore by lazy { SecureCredentialStore(appContext) }

    // Single reactive source of truth for the hide-balances toggle. The eye
    // toggle lives on one screen but every screen (Home, Dashboard/Insights,
    // Accounts) collects this flow, so flipping it propagates live everywhere
    // instead of each screen snapshotting the pref independently.
    private val _hideBalances by lazy { MutableStateFlow(preferences.hideBalances) }
    val hideBalances: StateFlow<Boolean> by lazy { _hideBalances.asStateFlow() }

    fun setHideBalances(hidden: Boolean) {
        preferences.hideBalances = hidden   // persist across restarts
        _hideBalances.value = hidden        // notify all collectors live
    }

    fun toggleHideBalances() = setHideBalances(!_hideBalances.value)

    /**
     * Opt-in biometric app lock. The controller is pure Kotlin; only this
     * scheduler lambda touches the Android main-thread Handler, so the whole
     * lock state machine stays JVM-testable.
     */
    val appLock: AppLockController by lazy {
        AppLockController(
            sessionMonitor = IdleSessionMonitor(schedule = { delayMillis, block ->
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val runnable = Runnable(block)
                handler.postDelayed(runnable, delayMillis)
                ({ handler.removeCallbacks(runnable) })
            }),
            isEnabled = { preferences.appLockEnabled },
            timeoutMillis = { preferences.appLockTimeoutMinutes * 60_000L },
        )
    }

    fun setAppLockEnabled(enabled: Boolean) {
        preferences.appLockEnabled = enabled
        appLock.onSettingsChanged()
    }

    fun setAppLockTimeoutMinutes(minutes: Int) {
        preferences.appLockTimeoutMinutes = minutes
        appLock.onSettingsChanged()   // re-arm the idle timer with the new duration
    }

    private val database by lazy { AppDatabase.build(appContext) }

    private val json = Json { ignoreUnknownKeys = true }

    private val binanceClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(BinanceSigningInterceptor(credentialStore))
            .build()
    }

    private val binanceApi: BinanceApi by lazy {
        Retrofit.Builder()
            .baseUrl(BinanceApi.BASE_URL)
            .client(binanceClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BinanceApi::class.java)
    }

    private val forexApi: ForexApi by lazy {
        Retrofit.Builder()
            .baseUrl(ForexApi.BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ForexApi::class.java)
    }

    val transactionRepository: TransactionRepository by lazy {
        TransactionRepositoryImpl(database, database.transactionDao()) { preferences.defaultPerson }
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(database.accountDao())
    }
    val parsingRuleRepository: ParsingRuleRepository by lazy {
        ParsingRuleRepositoryImpl(database.parsingRuleDao())
    }
    val senderLabelRepository: SenderLabelRepository by lazy {
        SenderLabelRepositoryImpl(database.senderLabelDao())
    }
    /** Logic-only Spot/Funding/Earn per-asset fetcher (zero balances filtered). */
    val binanceWalletAssets: BinanceWalletAssetsService by lazy {
        BinanceWalletAssetsService(binanceApi) { tag, t -> Diagnostics.recordError(appContext, tag, t) }
    }
    val portfolioRepository: PortfolioRepository by lazy {
        PortfolioRepositoryImpl(
            api = binanceApi,
            database = database,
            dao = database.assetBalanceDao(),
            walletDao = database.walletBalanceDao(),
            walletAssets = binanceWalletAssets,
            recordError = { tag, t -> Diagnostics.recordError(appContext, tag, t) },
        )
    }
    val ratesRepository: RatesRepository by lazy {
        RatesRepositoryImpl(forexApi, database.exchangeRateDao())
    }

    val backupManager: BackupManager by lazy {
        BackupManager(database, preferences)
    }

    private val compiledRuleCache by lazy {
        CompiledRuleCache(parsingRuleRepository, applicationScope)
    }

    val ingestCapturedMessage: IngestCapturedMessageUseCase by lazy {
        IngestCapturedMessageUseCase(
            customRules = compiledRuleCache,
            transactionRepository = transactionRepository,
            fallbackCurrencyProvider = { preferences.defaultCurrency },
        )
    }

    val bulkUpdateTransactions: BulkUpdateTransactionsUseCase by lazy {
        BulkUpdateTransactionsUseCase(transactionRepository)
    }

    /** Records a non-fatal exception (e.g. one caught instead of crashing a screen). */
    fun recordError(tag: String, throwable: Throwable) = Diagnostics.recordError(appContext, tag, throwable)

    /**
     * Inserts the starter Tabby Card / Tabby Cash rules exactly once. They are
     * ordinary custom rules, so the Rules screen can edit them — including the
     * "assign matches to" account, which is the whole point: these messages
     * carry no account number, so each rule pins its card explicitly.
     */
    fun seedDefaultRulesOnce() {
        if (preferences.tabbyRulesSeeded) return
        applicationScope.launch {
            runCatching {
                parsingRuleRepository.save(
                    ParsingRule(
                        name = "Tabby Card spend",
                        pattern = "You spent (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) " +
                            "at (?<merchant>[^.]+?)\\. Your available Tabby Card limit",
                        senderPattern = "tabby",
                        direction = TransactionDirection.DEBIT,
                        priority = 120,
                        sampleText = "You spent AED 110.90 at CAREEM RIDE. " +
                            "Your available Tabby Card limit is now AED 6111.71.",
                    )
                )
                parsingRuleRepository.save(
                    ParsingRule(
                        name = "Tabby Cash spend",
                        pattern = "You spent (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) " +
                            "at (?<merchant>[^.]+?)\\. Your available Tabby Cash balance",
                        senderPattern = "tabby",
                        direction = TransactionDirection.DEBIT,
                        priority = 120,
                        sampleText = "You spent AED 32.95 at CARREFOUR-MAF MKT YAS. " +
                            "Your available Tabby Cash balance is now AED 1675.59.",
                    )
                )
                preferences.tabbyRulesSeeded = true
            }.onFailure { recordError("AppContainer.seedDefaultRules", it) }
        }
    }
}
