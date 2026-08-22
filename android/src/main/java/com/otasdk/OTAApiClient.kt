package com.otasdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads an optional string, treating an explicit JSON null as absent.
 *
 * `JSONObject.optString` returns the *string* "null" when the value is
 * JSON null, because it stringifies JSONObject.NULL. So a response containing
 * `"signature": null` produced the four characters n-u-l-l, which then sailed
 * past every `isNullOrBlank()` guard downstream and got handed to the ECDSA
 * verifier as if it were a real signature — failing with "bundle may be
 * tampered" instead of correctly treating the bundle as unsigned.
 */
private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).ifEmpty { null }

/**
 * Thin HTTP client for OTA backend API calls.
 * Runs on a background thread — never call from main thread.
 */
class OTAApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ── Update check ──────────────────────────────────────────────────

    data class UpdateCheckResult(
        val updateAvailable: Boolean,
        val update: UpdatePayload? = null,
    )

    data class UpdatePayload(
        val bundleId: String,
        val downloadUrl: String,
        val hash: String,
        val mandatory: Boolean,
        val releaseNotes: String?,
        /** ECDSA-SHA256 signature (base64) — present when the bundle was signed by CI */
        val signature: String? = null,
        /** Delta patch URL — present when the server has a patch from the device's current bundle */
        val patchUrl: String? = null,
        val patchHash: String? = null,
        val fromHash: String? = null,
    )

    fun checkForUpdate(
        serverUrl: String,
        appId: String,
        platform: String,
        appVersion: String,
        runtimeVersion: String,
        currentHash: String,
        channel: String,
        deviceHash: String,
    ): UpdateCheckResult {
        val body = JSONObject().apply {
            put("appId",             appId)
            put("platform",          platform)
            put("appVersion",        appVersion)
            put("runtimeVersion",    runtimeVersion)
            put("currentBundleHash", currentHash)
            put("channel",           channel)
            put("deviceHash",        deviceHash)
        }.toString()

        val request = Request.Builder()
            .url("$serverUrl/v1/update/check")
            .post(body.toRequestBody(JSON))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from server")

        if (!response.isSuccessful) {
            throw Exception("Server error ${response.code}: $responseBody")
        }

        val json = JSONObject(responseBody)
        val updateAvailable = json.getBoolean("updateAvailable")

        if (!updateAvailable) return UpdateCheckResult(updateAvailable = false)

        return UpdateCheckResult(
            updateAvailable = true,
            update = UpdatePayload(
                bundleId     = json.getString("bundleId"),
                downloadUrl  = json.getString("downloadUrl"),
                hash         = json.getString("hash"),
                mandatory    = json.optBoolean("mandatory", false),
                releaseNotes = json.optStringOrNull("releaseNotes"),
                signature    = json.optStringOrNull("signature"),
                patchUrl     = json.optStringOrNull("patchUrl"),
                patchHash    = json.optStringOrNull("patchHash"),
                fromHash     = json.optStringOrNull("fromHash"),
            )
        )
    }

    // ── Analytics event ───────────────────────────────────────────────

    fun reportEvent(
        serverUrl: String,
        appId: String,
        bundleId: String?,
        deviceHash: String,
        eventType: String,
        platform: String,
    ) {
        val body = JSONObject().apply {
            put("appId",      appId)
            put("deviceHash", deviceHash)
            put("eventType",  eventType)
            put("platform",   platform)
            bundleId?.let { put("bundleId", it) }
        }.toString()

        val request = Request.Builder()
            .url("$serverUrl/v1/analytics/event")
            .post(body.toRequestBody(JSON))
            .build()

        // Fire-and-forget — don't throw on failure
        try {
            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }
}
