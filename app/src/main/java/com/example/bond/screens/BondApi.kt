package com.example.bond.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-file, no-dependency API client for your AWS API Gateway endpoint.
 *
 * Usage (with coroutines):
 *   BondApi.setApiId("<your-api-id>")  // or setFullUrl(...)
 *   val sim = BondApi.similarity("jlu31", "sganes8")
 *
 * Usage (without coroutines):
 *   BondApi.similarityAsync("jlu31", "sganes8") { result ->
 *       result.onSuccess { value -> //use similarity  }
 *             .onFailure { err ->  //show error }
 *   }
 */
object BondApi {

    // ----------------- CONFIG -----------------

    // Option A: use API ID + stage (recommended)
    // Example ID: 3u8wgak0yk   Stage: prod
    @Volatile private var apiId: String? = null
    @Volatile private var stage: String = "prod"
    @Volatile private var region: String = "us-east-1"

    // Option B: set the full URL directly (overrides API ID)
    // Example: https://3u8wgak0yk.execute-api.us-east-1.amazonaws.com/prod/similarity
    @Volatile private var fullUrlOverride: String? = null

    /** Set by API ID (you can call this once in Application.onCreate or anywhere before first call). */
    fun setApiId(id: String, stage: String = "prod", region: String = "us-east-1") {
        this.apiId = id
        this.stage = stage
        this.region = region
    }

    /** Or set a full URL directly (skips ID/stage/region). Must end with `/similarity`. */
    fun setFullUrl(url: String) {
        this.fullUrlOverride = url
    }

    // ----------------- PUBLIC API -----------------

    /**
     * Suspend function to get similarity.
     * Returns Double on success, or throws an exception with a clear message.
     */
    suspend fun similarity(profileA: String, profileB: String): Double =
        withContext(Dispatchers.IO) {
            val url = buildUrl()
            val body = JSONObject().apply {
                put("profileA", profileA)
                put("profileB", profileB)
            }.toString()

            val json = postJson(url, body)
            parseSimilarity(json)
        }

    /**
     * Callback version if you don’t use coroutines.
     * Runs on a background thread and posts the Result to the callback on the same thread (no main-thread hop).
     */
    fun similarityAsync(profileA: String, profileB: String, callback: (Result<Double>) -> Unit) {
        Thread {
            runCatching {
                val url = buildUrl()
                val body = JSONObject().apply {
                    put("profileA", profileA)
                    put("profileB", profileB)
                }.toString()
                val json = postJson(url, body)
                parseSimilarity(json)
            }.let(callback)
        }.start()
    }

    // ----------------- INTERNALS -----------------

    private fun buildUrl(): String {
        fullUrlOverride?.let { return it }
        val id = apiId ?: error("BondApi: No endpoint configured. Call setApiId(<id>) or setFullUrl(<url>) first.")
        return "https://$id.execute-api.$region.amazonaws.com/$stage/similarity"
    }

    private fun postJson(urlStr: String, jsonBody: String): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
        }

        try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

            if (code !in 200..299) {
                // Propagate server-side error message if present
                throw IllegalStateException(extractErrorMessage(response, code))
            }

            return response
        } finally {
            conn.disconnect()
        }
    }

    private fun extractErrorMessage(raw: String, code: Int): String {
        return runCatching {
            val obj = JSONObject(raw)
            // try common shapes:
            obj.optString("message")
                .ifBlank { obj.optString("error") }
                .ifBlank { obj.toString() }
        }.getOrElse { "HTTP $code: $raw" }
    }

    private fun parseSimilarity(json: String): Double {
        val obj = JSONObject(json)
        if (obj.optBoolean("ok", false).not() && obj.has("error")) {
            throw IllegalStateException(obj.optString("error"))
        }
        if (!obj.has("similarity")) {
            throw IllegalStateException("Response missing 'similarity' field: $json")
        }
        return obj.getDouble("similarity")
    }
}
