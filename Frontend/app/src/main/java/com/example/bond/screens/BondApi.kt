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
 *   val result = BondApi.similarity("jlu31", "sganes8")
 *   val similarity = result.similarity
 *   val summary = result.summary
 *
 * Usage (without coroutines):
 *   BondApi.similarityAsync("jlu31", "sganes8") { result ->
 *       result.onSuccess { data -> 
 *           val similarity = data.similarity
 *           val summary = data.summary
 *       }
 *       .onFailure { err ->  //show error }
 *   }
 */

data class SimilarityResult(
    val similarity: Double,
    val summary: String,
    val icebreakers: List<String>
)
object BondApi {

    // ----------------- CONFIG -----------------

    // Option A: use API ID + stage (recommended)
    // Example ID: 3u8wgak0yk   Stage: prod
    @Volatile private var apiId: String? = null
    @Volatile private var stage: String = "prod"
    @Volatile private var region: String = "us-east-1"

    // Option B: set the full URL directly (overrides API ID)
    // Example: https://3u8wgak0yk.execute-api.us-east-1.amazonaws.com/prod/similarity
    @Volatile private var fullUrlOverride: String? = "https://3u8wgak0yk.execute-api.us-east-1.amazonaws.com/prod/similarity"

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
     * Suspend function to get similarity and summary.
     * Returns SimilarityResult on success, or throws an exception with a clear message.
     */
    suspend fun similarity(profileA: String, profileB: String): SimilarityResult =
        withContext(Dispatchers.IO) {
            val url = buildUrl()
            val body = JSONObject().apply {
                put("profileA", profileA)
                put("profileB", profileB)
            }.toString()

            val json = postJson(url, body)
            parseSimilarityResult(json, profileB) // Pass profileB to get the other user's summary
        }

    /**
     * Callback version if you don't use coroutines.
     * Runs on a background thread and posts the Result to the callback on the same thread (no main-thread hop).
     */
    fun similarityAsync(profileA: String, profileB: String, callback: (Result<SimilarityResult>) -> Unit) {
        Thread {
            runCatching {
                val url = buildUrl()
                val body = JSONObject().apply {
                    put("profileA", profileA)
                    put("profileB", profileB)
                }.toString()
                val json = postJson(url, body)
                parseSimilarityResult(json, profileB) // Pass profileB to get the other user's summary
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

    private fun parseSimilarityResult(json: String, otherUserId: String): SimilarityResult {
        val obj = JSONObject(json)
        if (obj.has("ok") && !obj.optBoolean("ok", false)) {
            throw IllegalStateException(obj.optString("error", "API returned ok: false"))
        }
        if (!obj.has("similarity")) {
            throw IllegalStateException("Response missing 'similarity' field: $json")
        }
        
        val similarity = obj.getDouble("similarity")
        val summaries = obj.optJSONObject("summaries")
        
        // Get the summary for the other user specifically
        val summary = summaries?.optString(otherUserId) ?: "No summary available"
        
        // Extract icebreakers array
        val icebreakersArray = obj.optJSONArray("icebreakers")
        val icebreakers = if (icebreakersArray != null) {
            val icebreakersList = mutableListOf<String>()
            for (i in 0 until icebreakersArray.length()) {
                icebreakersList.add(icebreakersArray.getString(i))
            }
            icebreakersList
        } else {
            listOf("No icebreakers available")
        }
        
        return SimilarityResult(similarity, summary, icebreakers)
    }
}
