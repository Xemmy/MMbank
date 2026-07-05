package com.xemmy.mmbank.data.remote.binance

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers

@Serializable
data class BinanceBalanceDto(
    val asset: String,
    val free: String,
    val locked: String,
)

@Serializable
data class BinanceAccountDto(
    val balances: List<BinanceBalanceDto> = emptyList(),
)

@Serializable
data class BinanceTickerPriceDto(
    val symbol: String,
    val price: String,
)

@Serializable
data class BinanceWalletBalanceDto(
    val activate: Boolean = false,
    /** Wallet total valued in BTC by Binance. */
    val balance: String = "0",
    /** "Spot", "Funding", "Earn", "Trading Bots", "Cross Margin", … */
    val walletName: String = "",
)

interface BinanceApi {

    /**
     * Spot account balances. SIGNED endpoint: [BinanceSigningInterceptor]
     * appends timestamp + HMAC-SHA256 signature and the API key header.
     */
    @Headers("${BinanceSigningInterceptor.SIGNED_HEADER}: true")
    @GET("api/v3/account")
    suspend fun getAccount(): BinanceAccountDto

    /**
     * BTC-valued total of every Binance wallet (Spot, Funding, Earn, Trading
     * Bots, Margin, Futures, Options) in one call — this is how assets parked
     * outside Spot are picked up without one integration per product.
     */
    @Headers("${BinanceSigningInterceptor.SIGNED_HEADER}: true")
    @GET("sapi/v1/asset/wallet/balance")
    suspend fun getWalletBalances(): List<BinanceWalletBalanceDto>

    /** Public endpoint: latest price for every trading pair. */
    @GET("api/v3/ticker/price")
    suspend fun getAllPrices(): List<BinanceTickerPriceDto>

    companion object {
        const val BASE_URL = "https://api.binance.com/"
    }
}
