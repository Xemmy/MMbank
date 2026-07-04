package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.AssetBalanceDao
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.remote.binance.BinanceApi
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.repository.PortfolioRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PortfolioRepositoryImpl(
    private val api: BinanceApi,
    private val database: AppDatabase,
    private val dao: AssetBalanceDao,
) : PortfolioRepository {

    override fun observeBalances(): Flow<List<AssetBalance>> =
        dao.observeAll().map { list -> list.map(AssetBalanceEntity::toDomain) }

    override suspend fun syncSpotBalances(): Result<Int> = runCatching {
        val account = api.getAccount()
        val nonZero = account.balances.mapNotNull { dto ->
            val free = dto.free.toBigDecimalOrNull() ?: return@mapNotNull null
            val locked = dto.locked.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if ((free + locked).signum() <= 0) null else Triple(dto.asset, free, locked)
        }

        // One public call for all pair prices; valuation prefers the USDT pair.
        val prices: Map<String, BigDecimal> = if (nonZero.isEmpty()) {
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

        database.withTransaction {
            dao.clear()
            dao.insertAll(entities)
        }
        entities.size
    }
}
