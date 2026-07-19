package com.tailscalepiacontrol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ControllerClient(baseUrl: String, private val apiToken: String? = null) {
    private val gson = Gson()
    private val normalizedBase = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val vpnClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getPairingInfo(): PairingInfoResponse {
        val request = Request.Builder()
            .url("$normalizedBase/pairing")
            .get()
            .build()
        return execute(request, PairingInfoResponse::class.java)
    }

    fun register(name: String, platform: String, pairingSecret: String?, pairingCode: String?): DeviceRegisterResponse {
        val body = gson.toJson(
            mapOf(
                "name" to name,
                "platform" to platform,
                "pairing_secret" to pairingSecret,
                "pairing_code" to pairingCode
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$normalizedBase/devices/register")
            .post(body)
            .build()

        return execute(request, DeviceRegisterResponse::class.java)
    }

    fun listRegions(): RegionListResponse {
        val request = Request.Builder()
            .url("$normalizedBase/regions")
            .get()
            .build()
        return execute(request, RegionListResponse::class.java)
    }

    fun getVpnStatus(): VpnStatusResponse {
        val request = authorized("$normalizedBase/devices/me/vpn").get().build()
        return execute(request, VpnStatusResponse::class.java)
    }

    fun updateVpn(enabled: Boolean, region: String?): VpnStatusResponse {
        val body = gson.toJson(VpnUpdateRequest(enabled, region)).toRequestBody("application/json".toMediaType())
        val request = authorized("$normalizedBase/devices/me/vpn").put(body).build()
        AppLogger.info("ControllerClient", "updateVpn enabled=$enabled region=${region ?: "-"}")
        return execute(request, VpnStatusResponse::class.java, vpnClient)
    }

    fun updateDeviceName(name: String): DeviceUpdateResponse {
        val body = gson.toJson(mapOf("name" to name)).toRequestBody("application/json".toMediaType())
        val request = authorized("$normalizedBase/devices/me").patch(body).build()
        return execute(request, DeviceUpdateResponse::class.java)
    }

    private fun authorized(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        require(!apiToken.isNullOrBlank()) { "Device is not registered" }
        builder.header("Authorization", "Bearer $apiToken")
        return builder
    }

    private fun <T> execute(request: Request, clazz: Class<T>, httpClient: OkHttpClient = client): T {
        val startedAt = System.currentTimeMillis()
        AppLogger.info("ControllerClient", "--> ${request.method} ${redactUrl(request.url.toString())}")
        try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - startedAt
                if (!response.isSuccessful) {
                    val message = parseErrorMessage(response.code, raw)
                    AppLogger.error(
                        "ControllerClient",
                        "<-- HTTP ${response.code} (${elapsed}ms) ${request.method} ${redactUrl(request.url.toString())}: $message"
                    )
                    throw IllegalStateException(message)
                }
                AppLogger.info(
                    "ControllerClient",
                    "<-- HTTP ${response.code} (${elapsed}ms) ${request.method} ${redactUrl(request.url.toString())} body=${truncate(raw)}"
                )
                return gson.fromJson(raw, clazz)
            }
        } catch (error: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            AppLogger.error(
                "ControllerClient",
                "<-- FAILED (${elapsed}ms) ${request.method} ${redactUrl(request.url.toString())}",
                error
            )
            throw error
        }
    }

    private fun redactUrl(url: String): String {
        return url
    }

    private fun truncate(raw: String, max: Int = 500): String {
        val compact = raw.replace("\n", " ").trim()
        return if (compact.length <= max) compact else compact.take(max) + "…"
    }

    private fun parseErrorMessage(code: Int, raw: String): String {
        return try {
            val json = JsonParser.parseString(raw)
            if (json is JsonObject && json.has("detail")) {
                val detail = json.get("detail")
                if (detail.isJsonPrimitive) detail.asString else detail.toString()
            } else {
                "HTTP $code: $raw"
            }
        } catch (_: Exception) {
            if (raw.isBlank()) "HTTP $code" else "HTTP $code: $raw"
        }
    }
}
