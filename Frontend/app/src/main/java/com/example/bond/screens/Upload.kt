package com.example.bond.network
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class UploadUrlResponse(
    val uploadURL: String,
    val fileName: String,
    val bucket: String,
    val region: String
)

object UploadHelper {
    private val client = OkHttpClient()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(UploadUrlResponse::class.java)

    private const val FUNCTION_BASE_URL =
        "https://getuploadurl-kx4vbp35sa-uc.a.run.app" // <-- Replace this with your getuploadurl endpoint

    /** Step 1: Get a presigned upload URL from Firebase */
    fun getUploadUrl(fileName: String): UploadUrlResponse {
        val request = Request.Builder()
            .url("$FUNCTION_BASE_URL/getUploadUrl?fileName=$fileName&fileType=audio/mpeg")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("getUploadUrl failed: ${response.code}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response")
            return adapter.fromJson(body) ?: throw Exception("Invalid JSON: $body")
        }
    }

    /** Step 2: Upload file to S3 using the presigned URL */
    fun uploadToS3(file: File, uploadUrl: String) {
        val body = RequestBody.create("audio/mpeg".toMediaType(), file)
        val request = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .header("Content-Type", "audio/mpeg")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Upload failed: ${response.code} ${response.message}")
            } else {
                Log.d("UploadHelper", "Upload success: ${response.code}")
            }
        }
    }
}