package com.xemmy.mmbank.data.remote.binance

import android.util.Log
import okhttp3.RequestBody.Companion.toRequestBody

/** One non-zero balance from a specific Binance wallet. */
data class BinanceWalletAsset(
    /** "Spot", "Funding" or "Earn". */
    val wallet: String,
    val asset: String,
    val amount: Double,
)

/**
 * Logic-only helper that queries the signed Binance endpoints for Spot,
 * Funding and Simple Earn (flexible + locked) balances, parses amounts as
 * doubles, and strictly drops zero balances before returning the clean list.
 *
 * Each wallet query runs in its own runCatching, so an endpoint the API key
 * can't access (or a transient failure) never breaks the other wallets —
 * partial data is returned instead. Request signing (HMAC-SHA256 over the
 * query string) is inherited from [BinanceSigningInterceptor] via the shared
 * [BinanceApi] client.
 */
class BinanceWalletAssetsService(
    private val api: BinanceApi,
    /** Surfaces a failing endpoint to Diagnostics so a missing wallet is explainable. */
    private val recordError: (String, Throwable) -> Unit = { _, _ -> },
) {

    /** Wallets whose fetch failed on the last call (e.g. "Funding", "Earn"). */
    val lastFailedWallets = mutableListOf<String>()

    suspend fun fetchNonZeroAssets(): List<BinanceWalletAsset> {
        val out = mutableListOf<BinanceWalletAsset>()
        lastFailedWallets.clear()

        fun fail(wallet: String, tag: String, t: Throwable) {
            if (wallet !in lastFailedWallets) lastFailedWallets += wallet
            Log.w(TAG, "$tag failed", t)
            recordError("Binance.$tag", t)
        }

        runCatching {
            api.getAccount().balances.forEach { dto ->
                val amount = dto.free.toAmount() + dto.locked.toAmount()
                if (amount > 0.0) out += BinanceWalletAsset(WALLET_SPOT, dto.asset, amount)
            }
        }.onFailure { fail(WALLET_SPOT, "spot", it) }

        runCatching {
            api.getFundingAssets(EMPTY_BODY).forEach { dto ->
                val amount = dto.free.toAmount() + dto.locked.toAmount() +
                    dto.freeze.toAmount() + dto.withdrawing.toAmount()
                if (amount > 0.0) out += BinanceWalletAsset(WALLET_FUNDING, dto.asset, amount)
            }
        }.onFailure { fail(WALLET_FUNDING, "funding", it) }

        runCatching {
            api.getFlexibleEarnPositions(size = PAGE_SIZE).rows.forEach { row ->
                val amount = row.totalAmount.toAmount()
                if (amount > 0.0) out += BinanceWalletAsset(WALLET_EARN, row.asset, amount)
            }
        }.onFailure { fail(WALLET_EARN, "earn-flexible", it) }

        runCatching {
            api.getLockedEarnPositions(size = PAGE_SIZE).rows.forEach { row ->
                val amount = maxOf(row.amount.toAmount(), row.totalAmount.toAmount())
                if (amount > 0.0) out += BinanceWalletAsset(WALLET_EARN, row.asset, amount)
            }
        }.onFailure { fail(WALLET_EARN, "earn-locked", it) }

        // Same asset can appear several times (e.g. two locked positions):
        // collapse to one row per wallet+asset.
        return out
            .groupBy { it.wallet to it.asset }
            .map { (key, rows) -> BinanceWalletAsset(key.first, key.second, rows.sumOf { it.amount }) }
            .sortedWith(compareBy({ it.wallet }, { -it.amount }))
    }

    private fun String.toAmount(): Double = toDoubleOrNull() ?: 0.0

    private companion object {
        const val TAG = "BinanceWalletAssets"
        const val WALLET_SPOT = "Spot"
        const val WALLET_FUNDING = "Funding"
        const val WALLET_EARN = "Earn"
        const val PAGE_SIZE = 100
        /** OkHttp requires a body on POST; Binance takes the params from the query. */
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
