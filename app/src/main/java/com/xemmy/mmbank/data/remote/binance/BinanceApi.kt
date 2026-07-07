package com.xemmy.mmbank.data.remote.binance

import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

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

@Serializable
data class BinanceFundingAssetDto(
    val asset: String = "",
    val free: String = "0",
    val locked: String = "0",
    val freeze: String = "0",
    val withdrawing: String = "0",
)

@Serializable
data class BinanceEarnPositionDto(
    val asset: String = "",
    /** Flexible positions report totalAmount; locked positions report amount. */
    val totalAmount: String = "0",
    val amount: String = "0",
)

@Serializable
data class BinanceEarnPositionsPageDto(
    val rows: List<BinanceEarnPositionDto> = emptyList(),
    val total: Int = 0,
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

    /**
     * Per-asset Funding-wallet balances. Binance accepts sapi POST parameters
     * in the query string, which is where [BinanceSigningInterceptor] signs —
     * the body stays empty (OkHttp requires one for POST, hence [emptyBody]).
     */
    @Headers("${BinanceSigningInterceptor.SIGNED_HEADER}: true")
    @POST("sapi/v1/asset/get-funding-asset")
    suspend fun getFundingAssets(@Body emptyBody: RequestBody): List<BinanceFundingAssetDto>

    /** Per-asset Simple Earn flexible positions. */
    @Headers("${BinanceSigningInterceptor.SIGNED_HEADER}: true")
    @GET("sapi/v1/simple-earn/flexible/position")
    suspend fun getFlexibleEarnPositions(@Query("size") size: Int): BinanceEarnPositionsPageDto

    /** Per-asset Simple Earn locked positions. */
    @Headers("${BinanceSigningInterceptor.SIGNED_HEADER}: true")
    @GET("sapi/v1/simple-earn/locked/position")
    suspend fun getLockedEarnPositions(@Query("size") size: Int): BinanceEarnPositionsPageDto

    /** Public endpoint: latest price for every trading pair. */
    @GET("api/v3/ticker/price")
    suspend fun getAllPrices(): List<BinanceTickerPriceDto>

    companion object {
        const val BASE_URL = "https://api.binance.com/"
    }
}
