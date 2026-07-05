package com.xemmy.mmbank.data.remote.forex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

@Serializable
data class ErApiLatestDto(
    /** "success" or "error". */
    val result: String = "",
    @SerialName("error-type") val errorType: String? = null,
    @SerialName("base_code") val baseCode: String = "",
    val rates: Map<String, Double> = emptyMap(),
)

/**
 * Free Forex rates from open.er-api.com — no API key, and unlike the ECB feed
 * (frankfurter) it supports AED and 160+ other base currencies, so refreshing
 * with an AED default no longer 404s. Rates update daily.
 */
interface ForexApi {

    @GET("v6/latest/{base}")
    suspend fun latest(@Path("base") base: String): ErApiLatestDto

    companion object {
        const val BASE_URL = "https://open.er-api.com/"
    }
}
