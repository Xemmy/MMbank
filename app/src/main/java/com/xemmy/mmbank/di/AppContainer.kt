package com.xemmy.mmbank.di

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xemmy.mmbank.domain.ledger.TransactionKindDetector
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.data.ai.AiParsingWorker
import com.xemmy.mmbank.data.ai.LocalAiConfig
import com.xemmy.mmbank.data.ai.LocalAiManager
import com.xemmy.mmbank.data.backup.BackupManager
import com.xemmy.mmbank.data.bills.BillReminderWorker
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.DatabaseEncryptionMigrator
import com.xemmy.mmbank.data.local.SecureDatabaseFactory
import com.xemmy.mmbank.data.prefs.AppPreferences
import com.xemmy.mmbank.data.prefs.SecureCredentialStore
import com.xemmy.mmbank.data.remote.binance.BinanceApi
import com.xemmy.mmbank.data.remote.binance.BinanceSigningInterceptor
import com.xemmy.mmbank.data.remote.binance.BinanceWalletAssetsService
import com.xemmy.mmbank.data.remote.forex.ForexApi
import com.xemmy.mmbank.data.repository.MerchantAccountRepositoryImpl
import com.xemmy.mmbank.data.repository.AccountRepositoryImpl
import com.xemmy.mmbank.data.repository.BillRepositoryImpl
import com.xemmy.mmbank.data.repository.CategoryRepositoryImpl
import com.xemmy.mmbank.data.repository.FriendRepositoryImpl
import com.xemmy.mmbank.data.repository.IgnoredMessageRepositoryImpl
import com.xemmy.mmbank.data.repository.ParsingRuleRepositoryImpl
import com.xemmy.mmbank.data.repository.PortfolioRepositoryImpl
import com.xemmy.mmbank.data.repository.RatesRepositoryImpl
import com.xemmy.mmbank.data.repository.SavingsGoalRepositoryImpl
import com.xemmy.mmbank.data.repository.SenderLabelRepositoryImpl
import com.xemmy.mmbank.data.repository.SplitGroupRepositoryImpl
import com.xemmy.mmbank.data.repository.SubscriptionRepositoryImpl
import com.xemmy.mmbank.data.repository.TransactionRepositoryImpl
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.retention.RetentionPruner
import com.xemmy.mmbank.data.retention.TransactionRetentionWorker
import com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.budget.DailyBudgetPacing
import com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine
import com.xemmy.mmbank.domain.demo.DemoDataGenerator
import com.xemmy.mmbank.domain.auth.AppLockController
import com.xemmy.mmbank.domain.auth.IdleSessionMonitor
import com.xemmy.mmbank.domain.repository.MerchantAccountRepository
import com.xemmy.mmbank.domain.repository.AccountRepository
import com.xemmy.mmbank.domain.repository.BillRepository
import com.xemmy.mmbank.domain.repository.CategoryRepository
import com.xemmy.mmbank.domain.repository.FriendRepository
import com.xemmy.mmbank.domain.repository.IgnoredMessageRepository
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.PortfolioRepository
import com.xemmy.mmbank.domain.repository.RatesRepository
import com.xemmy.mmbank.domain.repository.SavingsGoalRepository
import com.xemmy.mmbank.domain.repository.SenderLabelRepository
import com.xemmy.mmbank.domain.repository.SplitGroupRepository
import com.xemmy.mmbank.domain.repository.SubscriptionRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository
import com.xemmy.mmbank.domain.ai.AiDailyBudget
import com.xemmy.mmbank.domain.ai.AiParseResult
import com.xemmy.mmbank.domain.ai.AiSelfHealingRuleGenerator
import com.xemmy.mmbank.domain.model.BackgroundStyle
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.DesignMode
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.parser.IgnoredMessageCache
import com.xemmy.mmbank.domain.usecase.BulkUpdateTransactionsUseCase
import com.xemmy.mmbank.domain.usecase.ImportPastedMessagesUseCase
import com.xemmy.mmbank.domain.usecase.IngestCapturedMessageUseCase
import com.xemmy.mmbank.util.BudgetNotificationHelper
import com.xemmy.mmbank.util.CategoryBudgetNotificationHelper
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
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

    // Single reactive source of truth for Classic vs Premium design mode. The
    // toggle lives in Settings but the root theme wrapper (MainActivity), the
    // AI-parsing indicator and the transaction detail dialog all need it live,
    // so it's a container-level flow rather than a per-screen pref snapshot.
    private val _designMode by lazy { MutableStateFlow(preferences.designMode) }
    val designMode: StateFlow<DesignMode> by lazy { _designMode.asStateFlow() }

    fun setDesignMode(mode: DesignMode) {
        preferences.designMode = mode   // persist across restarts
        _designMode.value = mode        // notify all collectors live
    }

    // Single reactive source of truth for the app-wide ambient background
    // style. Read/written from the Appearance settings screen; kept at the
    // container level (not a per-screen pref snapshot) since the root app
    // scaffold and Settings both need it live, the same way hideBalances/
    // designMode do.
    private val _backgroundPreset by lazy { MutableStateFlow(preferences.backgroundPreset) }
    val backgroundPreset: StateFlow<BackgroundStyle> by lazy { _backgroundPreset.asStateFlow() }

    fun setBackgroundPreset(style: BackgroundStyle) {
        preferences.backgroundPreset = style   // persist across restarts
        _backgroundPreset.value = style        // notify all collectors live
    }

    // Single reactive source of truth for "Demo mode". The repositories collect
    // this to switch the ledger between the real partition (false) and the
    // disposable demo partition (true); flipping it hides/reveals real data live.
    private val _demoModeEnabled by lazy { MutableStateFlow(preferences.demoModeEnabled) }
    val demoModeEnabled: StateFlow<Boolean> by lazy { _demoModeEnabled.asStateFlow() }

    /**
     * Turns Demo mode ON: replaces any stale demo rows with a fresh random batch
     * (accounts + transactions, all tagged isDemo=1), then flips the flag so the
     * UI shows only that batch. Real rows are never read or written. Call only
     * after the biometric prompt has succeeded (the Settings UI enforces that).
     */
    suspend fun enableDemoMode() {
        database.withTransaction {
            // Fresh batch each time: clear any leftover demo rows first.
            database.transactionDao().deleteDemo()
            database.accountDao().deleteDemo()
            database.parsingRuleDao().deleteDemo()

            val batch = DemoDataGenerator.generate(currency = preferences.defaultCurrency)
            val idByKey = batch.accounts.associate { seed ->
                seed.key to database.accountDao().insert(seed.entity)
            }
            // Same enum-to-name bridge capture time uses, so demo transactions
            // land on real dynamic categories instead of showing up Uncategorized.
            val categoryIdByEnum = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME.mapNotNull { (enumValue, name) ->
                database.categoryDao().getByName(name)?.id?.let { enumValue to it }
            }.toMap()
            database.transactionDao().insertAll(batch.transactions(idByKey, categoryIdByEnum))
            database.parsingRuleDao().insertAll(batch.rules)
        }
        // Stash the real saved-people list and show demo people instead. Real
        // rules/accounts/transactions are only hidden (isDemo filter); people
        // live in a single pref string, so we swap it and keep the original safe.
        preferences.demoPersonsBackup = preferences.personsRaw
        preferences.persons = DemoDataGenerator.DEMO_PEOPLE
        preferences.demoModeEnabled = true
        _demoModeEnabled.value = true
    }

    /**
     * Turns Demo mode OFF: reveals the real data first (flip the flag), then
     * deletes the disposable demo rows — transactions before accounts, so the
     * accounts' ON DELETE SET_NULL never has to touch a row we're about to drop.
     */
    suspend fun disableDemoMode() {
        preferences.demoModeEnabled = false
        _demoModeEnabled.value = false
        // Restore the real saved-people list.
        preferences.personsRaw = preferences.demoPersonsBackup
        preferences.demoPersonsBackup = ""
        database.withTransaction {
            database.transactionDao().deleteDemo()
            database.accountDao().deleteDemo()
            database.parsingRuleDao().deleteDemo()
        }
    }

    /**
     * Forces Demo mode off without touching the DB — used after a backup import,
     * which clears every table (demo rows included) and restores real data +
     * the real people list, so the flag/backup must not be left pointing at a
     * now-gone demo state. (The import already set the real personsRaw.)
     */
    fun forceDemoModeOff() {
        preferences.demoModeEnabled = false
        _demoModeEnabled.value = false
        preferences.demoPersonsBackup = ""
    }

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
            // Persisted so the idle grace window survives the process being
            // killed: a return within the timeout doesn't force a re-auth.
            lastActive = object : AppLockController.LastActiveStore {
                override var value: Long
                    get() = preferences.appLockLastActiveAt
                    set(v) { preferences.appLockLastActiveAt = v }
            },
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

    /**
     * At-rest encryption: an existing plaintext ledger is migrated to
     * SQLCipher exactly once (verify-then-swap with a .bak safety net —
     * see DatabaseEncryptionMigrator); fresh installs are created encrypted.
     * If the migration fails the DB stays plaintext and is opened as such,
     * so the ledger keeps working and migration retries next process start.
     */
    private val database by lazy {
        val encrypted = runCatching {
            val passphrase = credentialStore.getOrCreateDatabasePassphrase()
            val migrated = DatabaseEncryptionMigrator.ensureEncrypted(
                appContext, AppDatabase.DB_NAME, passphrase, ::recordError,
            )
            if (migrated) SecureDatabaseFactory().encryptedFactory(appContext, passphrase) else null
        }.onFailure { recordError("AppContainer.databaseEncryption", it) }.getOrNull()
        AppDatabase.build(appContext, encrypted)
    }

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
        TransactionRepositoryImpl(
            database = database,
            dao = database.transactionDao(),
            demoMode = demoModeEnabled,
            defaultPersonProvider = { preferences.defaultPerson },
            categoryRepository = categoryRepository,
            merchantAccountRepository = merchantAccountRepository,
            onTransactionRecorded = { transaction ->
                checkDailyBudgetPacing(transaction)
                checkCategoryBudget(transaction)
                refreshSubscriptionForMerchant(transaction)
            },
        )
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(database.accountDao(), database.parsingRuleDao(), demoModeEnabled)
    }
    val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(database)
    }

    /**
     * Learned merchant -> account rules, written when the user assigns a
     * transaction by hand and read back at capture time. Lazy over [database]
     * like [categoryRepository], so no initialization cycle with
     * [transactionRepository].
     */
    val merchantAccountRepository: MerchantAccountRepository by lazy {
        MerchantAccountRepositoryImpl(database)
    }
    val parsingRuleRepository: ParsingRuleRepository by lazy {
        ParsingRuleRepositoryImpl(database.parsingRuleDao(), demoModeEnabled)
    }
    val senderLabelRepository: SenderLabelRepository by lazy {
        SenderLabelRepositoryImpl(database.senderLabelDao())
    }
    val ignoredMessageRepository: IgnoredMessageRepository by lazy {
        IgnoredMessageRepositoryImpl(database.ignoredMessagePatternDao())
    }
    val subscriptionRepository: SubscriptionRepository by lazy {
        SubscriptionRepositoryImpl(database.subscriptionDao())
    }
    val savingsGoalRepository: SavingsGoalRepository by lazy {
        SavingsGoalRepositoryImpl(database.savingsGoalDao())
    }
    val billRepository: BillRepository by lazy {
        BillRepositoryImpl(database.billDao())
    }
    val friendRepository: FriendRepository by lazy {
        FriendRepositoryImpl(database.friendDao())
    }
    val splitGroupRepository: SplitGroupRepository by lazy {
        SplitGroupRepositoryImpl(database.splitGroupDao(), database.groupExpenseDao())
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

    /**
     * Executes a retention prune without moving a balance (see
     * [RetentionPruner]). Held here rather than behind `TransactionRepository`
     * for the same reason as [backupManager]: it is a whole-database operation,
     * not a ledger query.
     */
    val retentionPruner: RetentionPruner by lazy {
        RetentionPruner(database)
    }

    private val compiledRuleCache by lazy {
        CompiledRuleCache(parsingRuleRepository, applicationScope)
    }

    private val ignoredMessageCache by lazy {
        IgnoredMessageCache(ignoredMessageRepository, applicationScope)
    }

    /**
     * Client for the Gate-2 AI server -- local (on-device) or online (cloud),
     * per the user's selected mode. Timeouts are generous enough for a real
     * internet round-trip (online mode) while staying fine for an on-device
     * server (local mode). No Binance signing interceptor. Cleartext to
     * loopback is permitted by the network-security-config; LoopbackGuard
     * blocks any non-loopback host whenever local mode is selected.
     */
    private val localAiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Gate 2's gateway to the configured AI server (local loopback, or an explicit online provider). */
    val localAiManager: LocalAiManager by lazy {
        LocalAiManager(
            context = appContext,
            readConfig = {
                LocalAiConfig(
                    enabled = preferences.aiParsingEnabled,
                    mode = preferences.aiProviderMode,
                    endpointUrl = preferences.aiEndpointUrl,
                    model = preferences.aiModelName,
                    apiKey = credentialStore.aiApiKey,
                )
            },
            httpClientFactory = { localAiClient },
        )
    }

    val ingestCapturedMessage: IngestCapturedMessageUseCase by lazy {
        IngestCapturedMessageUseCase(
            customRules = compiledRuleCache,
            transactionRepository = transactionRepository,
            onGate1Miss = ::enqueueAiParsingWorker,
            fallbackCurrencyProvider = { preferences.defaultCurrency },
            ignoredMessages = ignoredMessageCache,
        )
    }

    val bulkUpdateTransactions: BulkUpdateTransactionsUseCase by lazy {
        BulkUpdateTransactionsUseCase(transactionRepository)
    }

    /** Backs the Settings "Import & ignore messages" screen's paste box. */
    val importPastedMessages: ImportPastedMessagesUseCase by lazy {
        ImportPastedMessagesUseCase(ingestCapturedMessage, transactionRepository)
    }

    /**
     * A fresh [SimpleDateFormat] per call -- it's not thread-safe, and this is
     * read from both the capture threads (notification listener / SMS
     * receiver, via [consumeAiBudget]) and the UI thread (via
     * [aiRequestsUsedToday]), so a single shared instance could corrupt under
     * concurrent access.
     */
    private fun todaysAiBudgetDayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * Fire-and-forget: hands a Gate-1 miss to the expedited [AiParsingWorker],
     * unless today's automatic-parsing budget ([AppPreferences.aiDailyRequestLimit],
     * see [AiDailyBudget]) is already used up -- the message simply stays
     * unparsed, same as any other Gate-2 skip. Never awaited, never allowed to
     * affect the ingest pipeline's own result. The budget is only consulted
     * while AI parsing is enabled, so it can't pre-exhaust itself while the
     * feature is off.
     */
    private fun enqueueAiParsingWorker(message: CapturedMessage) {
        if (preferences.aiParsingEnabled && !consumeAiBudget()) return
        val input = Data.Builder()
            .putString(AiParsingWorker.KEY_RAW_MESSAGE, message.body)
            .build()
        val request = OneTimeWorkRequestBuilder<AiParsingWorker>()
            .setInputData(input)
            // Local mode talks to a loopback server that's reachable with or
            // without a WAN connection, but this constraint only delays the
            // one-shot run until *some* network is up -- cheap, and it saves
            // the online mode's HTTP call from firing while offline. The
            // worker still never retries (see its doc comment): re-sending a
            // user's raw message text to an online provider on every backoff
            // tick is a privacy cost this app doesn't take on for a
            // best-effort background parse.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appContext).enqueue(request)
    }

    /** True and consumed if today's automatic-parsing budget has room; false (and untouched) once it's spent. */
    private fun consumeAiBudget(): Boolean {
        val today = todaysAiBudgetDayKey()
        val dayKey = preferences.aiBudgetDayKey
        val state = if (dayKey.isEmpty()) null else AiDailyBudget.State(dayKey, preferences.aiBudgetDayCount)
        if (AiDailyBudget.isExhausted(state, today, preferences.aiDailyRequestLimit)) return false
        val next = AiDailyBudget.next(state, today)
        preferences.aiBudgetDayKey = next.dayKey
        preferences.aiBudgetDayCount = next.count
        return true
    }

    /** Automatic Gate-2 requests consumed so far today (read-only; for Settings' "used X / limit" display). */
    fun aiRequestsUsedToday(): Int {
        val today = todaysAiBudgetDayKey()
        return if (preferences.aiBudgetDayKey == today) preferences.aiBudgetDayCount else 0
    }

    /**
     * Gate 2 self-healing hook: stages a *disabled* [ParsingRule] candidate
     * generated from the AI's own extraction (see
     * [AiSelfHealingRuleGenerator]) for one-tap human approval in the Rules
     * screen. Nothing here ever writes a transaction or an enabled rule --
     * unreviewed AI output can only ever produce a pending suggestion.
     */
    fun onAiTransactionParsed(rawMessage: String, result: AiParseResult) {
        AiSelfHealingRuleGenerator.generate(rawMessage, result)?.let { candidate ->
            applicationScope.launch {
                runCatching {
                    val existing = parsingRuleRepository.observeAll().first()
                    if (existing.none { it.pattern == candidate.pattern }) {
                        parsingRuleRepository.save(candidate)
                    }
                }.onFailure { recordError("AppContainer.onAiTransactionParsed", it) }
            }
        }
        onAiCategorySuggested(result)
    }

    /**
     * Gate 2's category counterpart to the rule-candidate hook above: a
     * confident `category_name` match is learned as a confirmed merchant
     * mapping (backfilling any already-captured, still-uncategorized
     * transactions from that merchant); a brand-new `suggested_category` is
     * staged as a *pending* mapping instead -- surfaced for one-tap approval
     * in the Uncategorized queue, never auto-applied. No-op when the AI named
     * neither (or there's no merchant to key off of).
     */
    private fun onAiCategorySuggested(result: AiParseResult) {
        val merchant = result.merchant?.takeIf { it.isNotBlank() } ?: return
        applicationScope.launch {
            runCatching {
                when {
                    result.categoryName != null -> {
                        val category = categoryRepository.observeAll().first()
                            .firstOrNull { it.name.equals(result.categoryName, ignoreCase = true) }
                        if (category != null) categoryRepository.learnMapping(merchant, category.id)
                    }
                    result.suggestedCategoryName != null -> categoryRepository.suggestPending(
                        merchant, result.suggestedCategoryName, result.suggestedCategoryIcon, result.suggestedCategoryColorHex,
                    )
                }
            }.onFailure { recordError("AppContainer.onAiCategorySuggested", it) }
        }
    }

    /**
     * Real-time daily budget pacing (see [DailyBudgetPacing]): after a real
     * debit is recorded, checks it against
     * `(monthlySpendBudget - fixedMonthlyExpenses - spentSoFarThisMonth) / remainingDaysInMonth`
     * and posts a local notification if it pushed the day over its allowance.
     * A no-op while no monthly budget is configured. Fire-and-forget, same as
     * every other post-insert side effect here -- it must never affect the
     * ingest pipeline's own result.
     */
    private fun checkDailyBudgetPacing(transaction: Transaction) {
        if (!LedgerMath.isSpend(transaction)) return
        val monthlyBudget = preferences.monthlySpendBudget
        if (monthlyBudget <= 0.0) return

        applicationScope.launch {
            runCatching {
                val zone = ZoneId.systemDefault()
                val today = Instant.ofEpochMilli(transaction.timestampMillis).atZone(zone).toLocalDate()
                val monthStart = today.withDayOfMonth(1)
                val nextMonthStart = monthStart.plusMonths(1)
                val startMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMillis = nextMonthStart.atStartOfDay(zone).toInstant().toEpochMilli()

                val spentSoFar = database.transactionDao()
                    .sumDebitsInRange(transaction.currencyCode, startMillis, endMillis)
                    .toBigDecimal()

                val result = DailyBudgetPacing.checkPacing(
                    transactionAmount = transaction.amount,
                    monthlyBudget = BigDecimal.valueOf(monthlyBudget),
                    fixedExpenses = BigDecimal.valueOf(preferences.fixedMonthlyExpenses),
                    spentSoFarIncludingThis = spentSoFar,
                    remainingDaysInMonth = DailyBudgetPacing.remainingDaysInMonth(today),
                )
                if (result is DailyBudgetPacing.PacingResult.Overspend) {
                    BudgetNotificationHelper.postOverspendAlert(appContext, transaction, result)
                }
            }.onFailure { recordError("AppContainer.checkDailyBudgetPacing", it) }
        }
    }

    /**
     * Per-category cap check (see [CategoryBudgetCalculator]): after a real,
     * categorized debit is recorded, compares that category's month-to-date
     * spend against its [com.xemmy.mmbank.domain.model.Category.monthlyCap] and
     * notifies the first time it reaches 80% and again the first time it passes
     * 100%. A no-op for uncategorized rows or categories with no cap.
     *
     * Independent of [checkDailyBudgetPacing]: that one paces total
     * discretionary spend per day, this one guards one category's monthly
     * envelope. Both hang off the same post-insert hook and both are
     * fire-and-forget -- neither may affect the ingest result.
     */
    private fun checkCategoryBudget(transaction: Transaction) {
        if (!LedgerMath.isSpend(transaction)) return
        val categoryId = transaction.categoryId ?: return

        applicationScope.launch {
            runCatching {
                val category = categoryRepository.getById(categoryId) ?: return@runCatching
                if (category.monthlyCap == null) return@runCatching

                val zone = ZoneId.systemDefault()
                val day = Instant.ofEpochMilli(transaction.timestampMillis).atZone(zone).toLocalDate()
                val monthStart = day.withDayOfMonth(1)
                val startMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMillis = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

                val spent = database.transactionDao()
                    .sumDebitsInRangeForCategory(transaction.currencyCode, categoryId, startMillis, endMillis)
                    .toBigDecimal()

                val status = CategoryBudgetCalculator.statusFor(category, spent) ?: return@runCatching
                // Locale.US explicitly: the default locale would render these
                // digits Arabic-Indic on an Arabic device, so the same month
                // would key differently depending on system language.
                val monthKey = String.format(Locale.US, "%04d-%02d", monthStart.year, monthStart.monthValue)
                val alerted = preferences.categoryBudgetAlerts
                val key = CategoryBudgetCalculator.pendingAlert(status, monthKey, alerted) ?: return@runCatching

                // Keep only this month's keys, so the set can't grow without bound.
                preferences.categoryBudgetAlerts = alerted.filterTo(mutableSetOf()) { it.contains(":$monthKey:") } + key
                CategoryBudgetNotificationHelper.postCapAlert(appContext, status, transaction.currencyCode)
            }.onFailure { recordError("AppContainer.checkCategoryBudget", it) }
        }
    }

    /**
     * Keeps the subscription radar current without being asked. After a real
     * debit lands, re-runs [SubscriptionRadarEngine] over just that merchant's
     * history and upserts the result, so a newly-started subscription, a moved
     * renewal date or a price change shows up on its own instead of waiting for
     * the user to remember to tap "Scan" on the Subscriptions screen.
     *
     * Scoped to one merchant via an indexed query rather than loading the
     * ledger, since this runs on the capture path. `replaceAll` upserts by
     * merchant and preserves a dismissed flag, so re-detecting something the
     * user already dismissed can't silently un-dismiss it.
     */
    private fun refreshSubscriptionForMerchant(transaction: Transaction) {
        if (transaction.kind != TransactionKind.SPEND || transaction.excludeFromStats) return
        val merchant = transaction.merchant?.trim()?.takeIf { it.isNotBlank() } ?: return

        applicationScope.launch {
            runCatching {
                val history = database.transactionDao()
                    .findRealDebitsByMerchant(merchant)
                    .map { it.toDomain() }
                val detected = SubscriptionRadarEngine.detect(history)
                if (detected.isNotEmpty()) subscriptionRepository.replaceAll(detected)
            }.onFailure { recordError("AppContainer.refreshSubscriptionForMerchant", it) }
        }
    }

    /**
     * Turns the opt-in renewal reminder on or off, scheduling or cancelling one
     * of the app's periodic work items (see [SubscriptionReminderWorker]).
     * Nothing is scheduled while the toggle is off, so the app's
     * no-background-polling behavior is unchanged for anyone who doesn't want
     * reminders.
     */
    fun setSubscriptionRemindersEnabled(enabled: Boolean) {
        preferences.subscriptionRemindersEnabled = enabled
        if (enabled) ensureSubscriptionReminderWork() else cancelSubscriptionReminderWork()
    }

    /**
     * Re-arms the daily reminder if it's enabled. Idempotent (unique work with
     * KEEP), so calling it on every app start just confirms the existing
     * schedule rather than resetting its period.
     */
    fun ensureSubscriptionReminderWork() {
        if (!preferences.subscriptionRemindersEnabled) return
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                SubscriptionReminderWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SubscriptionReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        }.onFailure { recordError("AppContainer.ensureSubscriptionReminderWork", it) }
    }

    private fun cancelSubscriptionReminderWork() {
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(SubscriptionReminderWorker.WORK_NAME)
        }.onFailure { recordError("AppContainer.cancelSubscriptionReminderWork", it) }
    }

    /**
     * Turns the opt-in bill due-date reminder on or off, scheduling or
     * cancelling one of the app's periodic work items (see
     * [BillReminderWorker]). Independent of [setSubscriptionRemindersEnabled] --
     * a user may want one reminder stream but not the other.
     */
    fun setBillRemindersEnabled(enabled: Boolean) {
        preferences.billRemindersEnabled = enabled
        if (enabled) ensureBillReminderWork() else cancelBillReminderWork()
    }

    /**
     * Re-arms the daily bill reminder if it's enabled. Idempotent (unique
     * work with KEEP), so calling it on every app start just confirms the
     * existing schedule rather than resetting its period.
     */
    fun ensureBillReminderWork() {
        if (!preferences.billRemindersEnabled) return
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                BillReminderWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BillReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        }.onFailure { recordError("AppContainer.ensureBillReminderWork", it) }
    }

    private fun cancelBillReminderWork() {
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(BillReminderWorker.WORK_NAME)
        }.onFailure { recordError("AppContainer.cancelBillReminderWork", it) }
    }

    /**
     * Turns the opt-in transaction-retention prune on or off, scheduling or
     * cancelling [TransactionRetentionWorker]. `months <= 0` disables it (and
     * cancels any scheduled run); nothing is ever scheduled at a value of 0,
     * matching [setSubscriptionRemindersEnabled]'s off-by-default posture.
     */
    fun setTransactionRetentionMonths(months: Int) {
        preferences.transactionRetentionMonths = months
        if (months > 0) ensureTransactionRetentionWork() else cancelTransactionRetentionWork()
    }

    /**
     * Re-arms the weekly prune if a retention window is set. Idempotent
     * (unique work with KEEP), so calling it on every app start just confirms
     * the existing schedule rather than resetting its period.
     */
    fun ensureTransactionRetentionWork() {
        if (preferences.transactionRetentionMonths <= 0) return
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                TransactionRetentionWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TransactionRetentionWorker>(7, TimeUnit.DAYS).build(),
            )
        }.onFailure { recordError("AppContainer.ensureTransactionRetentionWork", it) }
    }

    private fun cancelTransactionRetentionWork() {
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(TransactionRetentionWorker.WORK_NAME)
        }.onFailure { recordError("AppContainer.cancelTransactionRetentionWork", it) }
    }

    /** Records a non-fatal exception (e.g. one caught instead of crashing a screen). */
    fun recordError(tag: String, throwable: Throwable) = Diagnostics.recordError(appContext, tag, throwable)

    /**
     * Inserts the starter Tabby Card / Tabby Cash rules exactly once. They are
     * ordinary custom rules, so the Rules screen can edit them — including the
     * "assign matches to" account, which is the whole point: these messages
     * carry no account number, so each rule pins its card explicitly.
     */
    /**
     * One-time pass after the v26 upgrade: the SQL migration can only tell a
     * plain debit from a plain credit, so ATM withdrawals, refunds, card
     * payments and own-account transfers recognizable from their message text
     * are re-read here with the full [TransactionKindDetector]. Bump
     * [LEDGER_KIND_BACKFILL_VERSION] to re-run it after the detector learns
     * new wording -- it only ever upgrades rows still on the plain default.
     */
    fun reclassifyLedgerOnce() {
        if (preferences.ledgerKindBackfillVersion >= LEDGER_KIND_BACKFILL_VERSION) return
        applicationScope.launch {
            runCatching {
                database.withTransaction {
                    val accountTypes = database.accountDao().getAll().associate { it.id to it.type }
                    database.transactionDao().getAll().forEach { entity ->
                        val tx = entity.toDomain()
                        val better = TransactionKindDetector.reclassify(tx, tx.accountId?.let { accountTypes[it] })
                            ?: return@forEach
                        database.transactionDao().update(entity.copy(kind = better))
                    }
                }
                preferences.ledgerKindBackfillVersion = LEDGER_KIND_BACKFILL_VERSION
            }.onFailure { recordError("AppContainer.reclassifyLedgerOnce", it) }
        }
    }

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

/** See [AppContainer.reclassifyLedgerOnce]. */
private const val LEDGER_KIND_BACKFILL_VERSION = 1
