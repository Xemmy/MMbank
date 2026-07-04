package com.xemmy.mmbank.data.remote.binance

import com.xemmy.mmbank.data.prefs.SecureCredentialStore
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Signs Binance requests marked with [SIGNED_HEADER]: appends `timestamp` and
 * `recvWindow`, computes HMAC-SHA256 over the final query string with the
 * secret key, and attaches the API key header. Unmarked (public) requests pass
 * through untouched.
 */
class BinanceSigningInterceptor(
    private val credentials: SecureCredentialStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(SIGNED_HEADER) == null) return chain.proceed(request)

        val apiKey = credentials.binanceApiKey
            ?: throw IOException("Binance API key not configured (Settings > Binance)")
        val secret = credentials.binanceSecretKey
            ?: throw IOException("Binance secret key not configured (Settings > Binance)")

        val urlWithParams = request.url.newBuilder()
            .addQueryParameter("recvWindow", "10000")
            .addQueryParameter("timestamp", System.currentTimeMillis().toString())
            .build()

        val payload = urlWithParams.encodedQuery.orEmpty()
        val signedUrl = urlWithParams.newBuilder()
            .addQueryParameter("signature", hmacSha256Hex(secret, payload))
            .build()

        val signedRequest = request.newBuilder()
            .removeHeader(SIGNED_HEADER)
            .header("X-MBX-APIKEY", apiKey)
            .url(signedUrl)
            .build()
        return chain.proceed(signedRequest)
    }

    private fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SIGNED_HEADER = "X-MMBANK-SIGNED"
    }
}
