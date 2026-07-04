package com.xemmy.mmbank.data.remote.forex

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class FrankfurterLatestDto(
    val base: String,
    val date: String,
    val rates: Map<String, Double> = emptyMap(),
)

/** Free ECB-backed Forex rates, no API key required (frankfurter.app). */
interface ForexApi {

    @GET("latest")
    suspend fun latest(@Query("from") base: String): FrankfurterLatestDto

    companion object {
        const val BASE_URL = "https://api.frankfurter.app/"
    }
}
