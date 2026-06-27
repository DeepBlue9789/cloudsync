package com.cloudsync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Result type for GitHub API calls that may be rate-limited.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class RateLimited(val retryAfterSeconds: Int = 60) : ApiResult<Nothing>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}

/**
 * GitHub Gist API client for storing/retrieving sync data.
 *
 * Uses a single private gist with a marker description to store all sync data.
 * The gist contains one file: "cloudsync_data.json" with the SyncPayload.
 */
object GitHubApiClient {
    private const val TAG = "CloudSync-GitHub"
    private const val API_BASE = "https://api.github.com"
    private const val GIST_DESCRIPTION = "CloudStream Sync Data — DO NOT DELETE"
    private const val SYNC_FILE_NAME = "cloudsync_data.json"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val mapper = jacksonObjectMapper()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Find an existing sync gist by searching through user's gists.
     * Returns the gist ID if found, null otherwise.
     */
    fun findExistingGist(token: String): String? {
        try {
            val request = Request.Builder()
                .url("$API_BASE/gists?per_page=100")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to list gists: ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val gists = mapper.readValue<List<Map<String, Any?>>>(body)

            val gist = gists.firstOrNull { (it["description"] as? String) == GIST_DESCRIPTION }
            if (gist != null) {
                val id = gist["id"] as? String
                Log.d(TAG, "Found existing sync gist: $id")
                return id
            }

            Log.d(TAG, "No existing sync gist found")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding gist: ${e.message}")
            return null
        }
    }

    /**
     * Create a new private gist for sync data.
     * Returns the gist ID.
     */
    fun createGist(token: String, initialData: SyncPayload): String? {
        try {
            val jsonData = mapper.writeValueAsString(initialData)

            val gistBody = mapOf(
                "description" to GIST_DESCRIPTION,
                "public" to false,
                "files" to mapOf(
                    SYNC_FILE_NAME to mapOf(
                        "content" to jsonData
                    )
                )
            )

            val requestBody = mapper.writeValueAsString(gistBody)
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$API_BASE/gists")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to create gist: ${response.code} — ${response.body?.string()}")
                return null
            }

            val body = response.body?.string() ?: return null
            val gistResponse = mapper.readValue<Map<String, Any?>>(body)
            val gistId = gistResponse["id"] as? String

            Log.d(TAG, "Created sync gist: $gistId")
            return gistId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating gist: ${e.message}")
            return null
        }
    }

    /**
     * Fetch the current sync payload from a gist.
     * Returns ApiResult to allow callers to handle rate limiting.
     */
    fun fetchGistResult(token: String, gistId: String): ApiResult<SyncPayload> {
        try {
            val request = Request.Builder()
                .url("$API_BASE/gists/$gistId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 429 || response.code == 403) {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 60
                    Log.w(TAG, "Rate limited on fetch: ${response.code}, retry after ${retryAfter}s")
                    return ApiResult.RateLimited(retryAfter)
                }
                Log.e(TAG, "Failed to fetch gist: ${response.code}")
                return ApiResult.Error("Failed to fetch gist", response.code)
            }

            val body = response.body?.string()
                ?: return ApiResult.Error("Empty response body")
            val gistResponse = mapper.readValue<Map<String, Any?>>(body)

            @Suppress("UNCHECKED_CAST")
            val files = gistResponse["files"] as? Map<String, Map<String, Any?>>
                ?: return ApiResult.Error("No files in gist")
            val syncFile = files[SYNC_FILE_NAME]
                ?: return ApiResult.Error("Sync file not found in gist")
            val content = syncFile["content"] as? String
                ?: return ApiResult.Error("No content in sync file")

            return ApiResult.Success(mapper.readValue<SyncPayload>(content))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching gist: ${e.message}")
            return ApiResult.Error("Exception: ${e.message}")
        }
    }

    /**
     * Convenience wrapper that returns nullable for backward compatibility.
     */
    fun fetchGist(token: String, gistId: String): SyncPayload? {
        return when (val result = fetchGistResult(token, gistId)) {
            is ApiResult.Success -> result.data
            else -> null
        }
    }

    /**
     * Update the sync gist with new data.
     * Returns ApiResult to allow callers to handle rate limiting.
     */
    fun updateGistResult(token: String, gistId: String, data: SyncPayload): ApiResult<Boolean> {
        try {
            val jsonData = mapper.writeValueAsString(data)

            val gistBody = mapOf(
                "files" to mapOf(
                    SYNC_FILE_NAME to mapOf(
                        "content" to jsonData
                    )
                )
            )

            val requestBody = mapper.writeValueAsString(gistBody)
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$API_BASE/gists/$gistId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .patch(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 429 || response.code == 403) {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 60
                    Log.w(TAG, "Rate limited on update: ${response.code}, retry after ${retryAfter}s")
                    return ApiResult.RateLimited(retryAfter)
                }
                Log.e(TAG, "Failed to update gist: ${response.code} — ${response.body?.string()}")
                return ApiResult.Error("Failed to update gist", response.code)
            }

            Log.d(TAG, "Successfully updated sync gist")
            return ApiResult.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating gist: ${e.message}")
            return ApiResult.Error("Exception: ${e.message}")
        }
    }

    /**
     * Convenience wrapper that returns boolean for backward compatibility.
     */
    fun updateGist(token: String, gistId: String, data: SyncPayload): Boolean {
        return when (updateGistResult(token, gistId, data)) {
            is ApiResult.Success -> true
            else -> false
        }
    }

    /**
     * Validate that a GitHub token has the required scopes (gist).
     */
    fun validateToken(token: String): Boolean {
        try {
            val request = Request.Builder()
                .url("$API_BASE/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Token validation failed: ${response.code}")
                return false
            }

            // Check X-OAuth-Scopes header for 'gist' scope
            val scopes = response.header("X-OAuth-Scopes") ?: ""
            val hasGistScope = scopes.split(",").map { it.trim() }.any {
                it == "gist" || it == "repo" // repo scope includes gist
            }

            if (!hasGistScope) {
                Log.w(TAG, "Token missing 'gist' scope. Available scopes: $scopes")
                // Fine-grained tokens don't report scopes this way, so we'll still return true
            }

            Log.d(TAG, "Token validated successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Token validation error: ${e.message}")
            return false
        }
    }

    /**
     * Delete the sync gist (for cleanup/reset).
     */
    fun deleteGist(token: String, gistId: String): Boolean {
        try {
            val request = Request.Builder()
                .url("$API_BASE/gists/$gistId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            return response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting gist: ${e.message}")
            return false
        }
    }
}
