package com.xemmy.mmbank.di

import android.content.Context
import com.xemmy.mmbank.data.backup.BackupManager
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.prefs.AppPreferences
import com.xemmy.mmbank.data.prefs.SecureCredentialStore
import com.xemmy.mmbank.data.remote.binance.BinanceApi
import com.xemmy.mmbank.data.remote.binance.BinanceSigningInterceptor
import com.xemmy.mmbank.data.remote.forex.ForexApi
import com.xemmy.mmbank.data.repository.AccountRepositoryImpl
import com.xemmy.mmbank.data.repository.ParsingRuleRepositoryImpl
import com.xemmy.mmbank.data.repository.PortfolioRepositoryImpl
import com.xemmy.mmbank.data.repository.RatesRepositoryImpl
import com.xemmy.mmbank.data.repository.SenderLabelRepositoryImpl
import com.xemmy.mmbank.data.repository.TransactionRepositoryImpl
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.domain.repository.AccountRepository
import com.xemmy.mmbank.domain.repository.ParsingRuleRepository
import com.xemmy.mmbank.domain.repository.PortfolioRepository
import com.xemmy.mmbank.domain.repository.RatesRepository
import com.xemmy.mmbank.domain.repository.SenderLabelRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository
import com.xemmy.mmbank.domain.parser.CompiledRuleCache
import com.xemmy.mmbank.domain.usecase.IngestCapturedMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        TransactionRepositoryImpl(database, database.transactionDao())
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
    val portfolioRepository: PortfolioRepository by lazy {
        PortfolioRepositoryImpl(binanceApi, database, database.assetBalanceDao(), database.walletBalanceDao())
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

    /** Records a non-fatal exception (e.g. one caught instead of crashing a screen). */
    fun recordError(tag: String, throwable: Throwable) = Diagnostics.recordError(appContext, tag, throwable)
}
