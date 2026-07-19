package com.tailscalepiacontrol

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class IpLookupResult(
    val ip: String,
    @SerializedName("city") val city: String?,
    @SerializedName("region") val region: String?,
    @SerializedName("country_name") val countryName: String?,
    @SerializedName("org") val org: String?
)

object IpLookup {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun fetch(): IpLookupResult {
        val request = Request.Builder()
            .url("https://ipapi.co/json/")
            .header("User-Agent", "PIA-Control-Android/1.0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("IP lookup failed: HTTP ${response.code}")
            }
            return gson.fromJson(raw, IpLookupResult::class.java)
        }
    }

    fun format(result: IpLookupResult): String {
        return buildString {
            append("Public IP: ${result.ip}\n")
            append("Location: ${listOfNotNull(result.city, result.region, result.countryName).joinToString(", ")}\n")
            result.org?.let { append("Network: $it") }
        }.trim()
    }
}
