package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.AssetBalanceDao
import com.xemmy.mmbank.data.local.dao.WalletBalanceDao
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.WalletBalanceEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.remote.binance.BinanceApi
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
) : PortfolioRepository {

    override fun observeBalances(): Flow<List<AssetBalance>> =
        dao.observeAll().map { list -> list.map(AssetBalanceEntity::toDomain) }

    override fun observeWallets(): Flow<List<WalletBalance>> =
        walletDao.observeAll().map { list -> list.map(WalletBalanceEntity::toDomain) }

    override suspend fun syncSpotBalances(): Result<Int> = runCatching {
        val account = api.getAccount()
        val nonZero = account.balances.mapNotNull { dto ->
            val free = dto.free.toBigDecimalOrNull() ?: return@mapNotNull null
            val locked = dto.locked.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if ((free + locked).signum() <= 0) null else Triple(dto.asset, free, locked)
        }

        // Per-wallet overview (Earn, Funding, Trading Bots, …), BTC-valued.
        // Best-effort: older API keys may lack the wallet permission, and that
        // must never break the spot sync — we just keep the previous rows.
        val wallets = runCatching { api.getWalletBalances() }.getOrNull()

        // One public call for all pair prices; valuation prefers the USDT pair.
        val prices: Map<String, BigDecimal> = if (nonZero.isEmpty() && wallets.isNullOrEmpty()) {
            emptyMap()
        } else {
            api.getAllPrices().mapNotNull { ticker ->
                ticker.price.toBigDecimalOrNull()?.let { ticker.symbol to it }
            }.toMap()
        }

        val now = System.currentTimeMillis()
        val entities = nonZero.map { (asset, free, locked) ->
            val priceUsd = when (asset) {
                "USDT", "USDC", "FDUSD", "BUSD", "DAI" -> BigDecimal.ONE
                else -> prices["${asset}USDT"] ?: prices["${asset}USDC"] ?: prices["${asset}FDUSD"]
            }
            AssetBalanceEntity(
                asset = asset,
                free = free,
                locked = locked,
                priceUsd = priceUsd,
                valueUsd = priceUsd?.multiply(free + locked),
                updatedAt = now,
            )
        }

        val btcUsd = prices["BTCUSDT"]
        val walletEntities = wallets?.mapNotNull { dto ->
            val btc = dto.balance.toBigDecimalOrNull() ?: return@mapNotNull null
            if (dto.walletName.isBlank()) return@mapNotNull null
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
}
