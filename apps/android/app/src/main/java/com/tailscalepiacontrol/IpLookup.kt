package com.tailscalepiacontrol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class IpLookupResult(
    val ip: String,
    val city: String?,
    val region: String?,
    val countryName: String?,
    val org: String?,
    val source: String
)

object IpLookup {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun fetch(): IpLookupResult {
        val errors = mutableListOf<String>()
        for (provider in PROVIDERS) {
            try {
                return provider()
            } catch (error: Exception) {
                errors.add("${error.message}")
            }
        }
        throw IllegalStateException(errors.joinToString(" | "))
    }

    fun format(result: IpLookupResult): String {
        return buildString {
            append("Public IP: ${result.ip}\n")
            append("Location: ${listOfNotNull(result.city, result.region, result.countryName).joinToString(", ").ifBlank { "-" }}\n")
            result.org?.let { append("Network: $it\n") }
            append("Source: ${result.source}")
        }.trim()
    }

    private val PROVIDERS: List<() -> IpLookupResult> = listOf(
        { fetchIpApiCo() },
        { fetchIpInfo() },
        { fetchIpWhoIs() },
        { fetchIpifyOnly() },
    )

    private fun fetchJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PIA-Control-Android/1.0.6")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from $url")
            }
            val json = JsonParser.parseString(raw).asJsonObject
            if (json.has("error") && json.get("error").asBoolean) {
                val reason = json.get("reason")?.asString ?: json.get("message")?.asString ?: "unknown error"
                throw IllegalStateException(reason)
            }
            if (json.has("success") && !json.get("success").asBoolean) {
                val reason = json.get("message")?.asString ?: "lookup failed"
                throw IllegalStateException(reason)
            }
            return json
        }
    }

    private fun fetchIpApiCo(): IpLookupResult {
        val json = fetchJson("https://ipapi.co/json/")
        val ip = json.stringOrNull("ip") ?: throw IllegalStateException("missing ip")
        return IpLookupResult(
            ip = ip,
            city = json.stringOrNull("city"),
            region = json.stringOrNull("region"),
            countryName = json.stringOrNull("country_name"),
            org = json.stringOrNull("org"),
            source = "ipapi.co"
        )
    }

    private fun fetchIpInfo(): IpLookupResult {
        val json = fetchJson("https://ipinfo.io/json")
        val ip = json.stringOrNull("ip") ?: throw IllegalStateException("missing ip")
        return IpLookupResult(
            ip = ip,
            city = json.stringOrNull("city"),
            region = json.stringOrNull("region"),
            countryName = json.stringOrNull("country"),
            org = json.stringOrNull("org"),
            source = "ipinfo.io"
        )
    }

    private fun fetchIpWhoIs(): IpLookupResult {
        val json = fetchJson("https://ipwho.is/")
        val ip = json.stringOrNull("ip") ?: throw IllegalStateException("missing ip")
        val connection = json.getAsJsonObject("connection")
        return IpLookupResult(
            ip = ip,
            city = json.stringOrNull("city"),
            region = json.stringOrNull("region"),
            countryName = json.stringOrNull("country"),
            org = connection?.stringOrNull("org"),
            source = "ipwho.is"
        )
    }

    private fun fetchIpifyOnly(): IpLookupResult {
        val json = fetchJson("https://api64.ipify.org?format=json")
        val ip = json.stringOrNull("ip") ?: throw IllegalStateException("missing ip")
        return IpLookupResult(
            ip = ip,
            city = null,
            region = null,
            countryName = null,
            org = null,
            source = "ipify.org"
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        if (!has(key) || get(key).isJsonNull) return null
        return get(key).asString
    }
}
