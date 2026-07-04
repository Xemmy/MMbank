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

interface BinanceApi {

    /**
     * Spot account balances. SIGNED endpoint: [BinanceSigningInterceptor]
     * appends timestamp + HMAC-SHA256 signature and the API key header.
     */
    @Headers("${BinanceSigningInterceptor.SIGNED_HEADER}: true")
    @GET("api/v3/account")
    suspend fun getAccount(): BinanceAccountDto

    /** Public endpoint: latest price for every trading pair. */
    @GET("api/v3/ticker/price")
    suspend fun getAllPrices(): List<BinanceTickerPriceDto>

    companion object {
        const val BASE_URL = "https://api.binance.com/"
    }
}
