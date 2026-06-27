package com.cloudsync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object PocketBaseApiClient {
    private const val TAG = "CloudSync-PocketBase"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val mapper = jacksonObjectMapper()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getBaseUrl(creds: SyncCredentials): String {
        var url = creds.pbUrl.trimEnd('/')
        if (!url.startsWith("http")) {
            url = "http://$url"
        }
        return url
    }

    /**
     * Authenticates and returns a JWT token.
     */
    fun authenticate(creds: SyncCredentials): String? {
        val baseUrl = getBaseUrl(creds)
        val authEndpoints = listOf(
            "/api/admins/auth-with-password", // PB < 0.23 admin
            "/api/collections/_superusers/auth-with-password", // PB >= 0.23 superusers
            "/api/collections/users/auth-with-password" // Regular users
        )

        val authBody = mapOf(
            "identity" to creds.pbEmail,
            "password" to creds.pbPassword
        )
        val requestBody = mapper.writeValueAsString(authBody).toRequestBody(JSON_MEDIA_TYPE)

        for (endpoint in authEndpoints) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$endpoint")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = mapper.readValue<Map<String, Any?>>(body)
                    val token = json["token"] as? String
                    if (token != null) {
                        Log.d(TAG, "Authenticated successfully via $endpoint")
                        return token
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auth failed on $endpoint: ${e.message}")
            }
        }
        Log.e(TAG, "Failed to authenticate with PocketBase")
        return null
    }

    /**
     * Finds an existing record in the collection.
     */
    fun findExistingRecord(creds: SyncCredentials, token: String): String? {
        try {
            val baseUrl = getBaseUrl(creds)
            val request = Request.Builder()
                .url("$baseUrl/api/collections/${creds.pbCollection}/records?perPage=1")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = mapper.readValue<Map<String, Any?>>(body)
            val items = json["items"] as? List<Map<String, Any?>>
            
            return items?.firstOrNull()?.get("id") as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error finding record: ${e.message}")
            return null
        }
    }

    /**
     * Creates a new record in the collection.
     */
    fun createRecord(creds: SyncCredentials, token: String, initialData: SyncPayload): String? {
        try {
            val baseUrl = getBaseUrl(creds)
            // PB expects fields to match collection schema. 
            // We assume a 'payload' JSON field.
            val bodyMap = mapOf("payload" to initialData)
            val requestBody = mapper.writeValueAsString(bodyMap).toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$baseUrl/api/collections/${creds.pbCollection}/records")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to create record: ${response.code} — ${response.body?.string()}")
                return null
            }

            val body = response.body?.string() ?: return null
            val responseMap = mapper.readValue<Map<String, Any?>>(body)
            return responseMap["id"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error creating record: ${e.message}")
            return null
        }
    }

    /**
     * Fetches a specific record.
     */
    fun fetchRecordResult(creds: SyncCredentials, token: String, recordId: String): ApiResult<SyncPayload> {
        try {
            val baseUrl = getBaseUrl(creds)
            val request = Request.Builder()
                .url("$baseUrl/api/collections/${creds.pbCollection}/records/$recordId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    return ApiResult.RateLimited(60)
                }
                return ApiResult.Error("Failed to fetch record", response.code)
            }

            val body = response.body?.string() ?: return ApiResult.Error("Empty response body")
            val json = mapper.readValue<Map<String, Any?>>(body)
            
            // PB returns the payload field as an object or we parse it
            val payloadObj = json["payload"]
            
            val payloadJson = if (payloadObj is String) {
                payloadObj
            } else {
                mapper.writeValueAsString(payloadObj)
            }
            
            val payload = mapper.readValue<SyncPayload>(payloadJson)
            return ApiResult.Success(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch record exception: ${e.message}")
            return ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Updates an existing record.
     */
    fun updateRecord(creds: SyncCredentials, token: String, recordId: String, payload: SyncPayload): ApiResult<Unit> {
        try {
            val baseUrl = getBaseUrl(creds)
            val bodyMap = mapOf("payload" to payload)
            val requestBody = mapper.writeValueAsString(bodyMap).toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$baseUrl/api/collections/${creds.pbCollection}/records/$recordId")
                .header("Authorization", "Bearer $token")
                .patch(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 429 || response.code == 403) {
                    return ApiResult.RateLimited(60)
                }
                return ApiResult.Error("Failed to update record", response.code)
            }
            return ApiResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Update record exception: ${e.message}")
            return ApiResult.Error(e.message ?: "Unknown error")
        }
    }
}
