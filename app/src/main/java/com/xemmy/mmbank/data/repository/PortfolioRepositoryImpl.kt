package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.AssetBalanceDao
import com.xemmy.mmbank.data.local.dao.WalletBalanceDao
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.WalletBalanceEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.remote.binance.BinanceApi
import com.xemmy.mmbank.data.remote.binance.BinanceWalletAssetsService
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.WalletBalance
import com.xemmy.mmbank.domain.repository.PortfolioRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PortfolioRepositoryImpl(
    private val api: BinanceApi,
    private val database: AppDatabase,
    private val dao: AssetBalanceDao,
    private val walletDao: WalletBalanceDao,
    /** Per-asset Spot + Funding + Earn fetcher (each endpoint independently safe). */
    private val walletAssets: BinanceWalletAssetsService,
    /** Records a partial-endpoint failure without failing the whole sync. */
    private val recordError: (String, Throwable) -> Unit = { _, _ -> },
) : PortfolioRepository {

    override fun observeBalances(): Flow<List<AssetBalance>> =
        dao.observeAll().map { list -> list.map(AssetBalanceEntity::toDomain) }

    override fun observeWallets(): Flow<List<WalletBalance>> =
        walletDao.observeAll().map { list -> list.map(WalletBalanceEntity::toDomain) }

    /**
     * Pulls the full picture, not just Spot: per-asset balances are collected
     * across Spot, Funding and Simple Earn (each endpoint in its own try/catch,
     * so one failing never blanks the rest) and merged per asset. The BTC-valued
     * per-wallet overview (which also covers Trading Bots / Futures / Margin) is
     * layered on top for the grand total. Zero balances are already filtered out
     * by [BinanceWalletAssetsService].
     */
    override suspend fun syncSpotBalances(): Result<Int> = runCatching {
        // Spot + Funding + Earn, per asset. Empty only if every wallet endpoint
        // failed or the account is genuinely empty.
        val perAsset = walletAssets.fetchNonZeroAssets()

        // Per-wallet BTC totals (Spot, Funding, Earn, Trading Bots, Futures…).
        // Best-effort: a key without this permission keeps the per-asset data.
        val wallets = runCatching { api.getWalletBalances() }
            .onFailure { recordError("PortfolioRepository.walletBalances", it) }
            .getOrNull()

        val prices: Map<String, BigDecimal> =
            if (perAsset.isEmpty() && wallets.isNullOrEmpty()) {
                emptyMap()
            } else {
                runCatching { api.getAllPrices() }
                    .onFailure { recordError("PortfolioRepository.prices", it) }
                    .getOrNull()
                    ?.mapNotNull { t -> t.price.toBigDecimalOrNull()?.let { t.symbol to it } }
                    ?.toMap()
                    .orEmpty()
            }

        // Both sources failed: this is a failed fetch, not an empty account.
        // Clearing here would wipe the last good holdings and report success.
        if (perAsset.isEmpty() && wallets == null) {
            error("Binance returned no balance data; keeping the previous holdings")
        }

        val now = System.currentTimeMillis()

        // Merge the same asset across wallets into one holding (e.g. USDT that
        // sits partly in Spot and partly in Earn) so totals are complete.
        val entities = perAsset
            .groupBy { it.asset }
            .map { (asset, rows) ->
                val total = rows.sumOf { it.amount }.toBigDecimal()
                val priceUsd = priceForAsset(asset, prices)
                AssetBalanceEntity(
                    asset = asset,
                    free = total,
                    locked = BigDecimal.ZERO,
                    priceUsd = priceUsd,
                    valueUsd = priceUsd?.multiply(total),
                    updatedAt = now,
                )
            }

        val btcUsd = prices["BTCUSDT"]
        val walletEntities = wallets?.mapNotNull { dto ->
            val btc = dto.balance.toBigDecimalOrNull() ?: return@mapNotNull null
            if (dto.walletName.isBlank() || btc.signum() <= 0) return@mapNotNull null
            WalletBalanceEntity(
                walletName = dto.walletName,
                balanceBtc = btc,
                valueUsd = btcUsd?.multiply(btc),
                updatedAt = now,
            )
        }

        database.withTransaction {
            dao.clear()
            dao.insertAll(entities)
            if (walletEntities != null) {
                walletDao.clear()
                walletDao.insertAll(walletEntities)
            }
        }
        entities.size
    }

    private fun priceForAsset(asset: String, prices: Map<String, BigDecimal>): BigDecimal? = when (asset) {
        "USDT", "USDC", "FDUSD", "BUSD", "DAI", "TUSD" -> BigDecimal.ONE
        // LDxxx are Earn "Locked/flexible" wrapper tickers — price by the base asset.
        else -> {
            val base = if (asset.startsWith("LD") && asset.length > 3) asset.removePrefix("LD") else asset
            prices["${base}USDT"] ?: prices["${base}USDC"] ?: prices["${base}FDUSD"]
        }
    }
}
