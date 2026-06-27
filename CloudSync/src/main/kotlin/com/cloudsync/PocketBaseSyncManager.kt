package com.cloudsync

import android.content.Context
import com.lagradost.api.Log

/**
 * Core sync engine for PocketBase — orchestrates the full push/pull/merge cycle.
 */
object PocketBaseSyncManager {
    private const val TAG = "CloudSync-PocketBaseManager"

    fun fullSync(context: Context, creds: SyncCredentials): SyncResult {
        if (!creds.isConfigured()) {
            return SyncResult(false, "PocketBase credentials not configured")
        }

        Log.d(TAG, "Starting full sync with PocketBase...")

        // Step 1: Ensure we have auth and a record
        val token = PocketBaseApiClient.authenticate(creds)
            ?: return SyncResult(false, "PocketBase auth failed")

        val recordId = ensureRecord(creds, token)
            ?: return SyncResult(false, "Failed to create/find PocketBase record")

        // Step 2: Read local data
        val localPositions = LocalDataManager.readAllPlaybackPositions(context)
        val localHistory = LocalDataManager.readAllWatchHistory(context)
        val localResume = LocalDataManager.readAllResumeWatching(context)
        val localDeletedResume = LocalDataManager.readAllDeletedResume(context)
        val localPrefs = LocalDataManager.readAllPreferences(context)

        val localPayload = SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName,
            watchHistory = localHistory,
            playbackPositions = localPositions,
            resumeWatching = localResume,
            deletedResumeWatching = localDeletedResume,
            preferences = localPrefs
        )

        // Step 3: Fetch remote data
        val fetchResult = PocketBaseApiClient.fetchRecordResult(creds, token, recordId)
        val remotePayload = if (fetchResult is ApiResult.Success) fetchResult.data else null

        // Step 4: Merge
        val merged = if (remotePayload != null) {
            GitHubSyncManager.mergePayloads(localPayload, remotePayload)
        } else {
            localPayload
        }

        // Step 5: Write merged data back to local
        var itemsPulled = 0
        if (remotePayload != null) {
            itemsPulled = GitHubSyncManager.writeRemoteDataToLocal(context, localPayload, merged)
        }

        // Step 6: Push merged data to PocketBase
        val updatedMerged = merged.copy(
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName
        )

        val pushResult = PocketBaseApiClient.updateRecord(creds, token, recordId, updatedMerged)
        if (pushResult !is ApiResult.Success) {
            return SyncResult(
                success = false,
                message = "Failed to push data to PocketBase",
                itemsPulled = itemsPulled
            )
        }

        val totalItems = merged.playbackPositions.size +
            merged.watchHistory.size +
            merged.resumeWatching.size +
            merged.preferences.size

        Log.d(TAG, "PocketBase Sync complete: $totalItems total items, $itemsPulled pulled from remote")

        return SyncResult(
            success = true,
            message = "Synced $totalItems items ($itemsPulled pulled)",
            itemsPushed = totalItems,
            itemsPulled = itemsPulled,
            timestamp = System.currentTimeMillis()
        )
    }

    fun pushOnly(context: Context, creds: SyncCredentials): SyncResult {
        if (!creds.isConfigured()) {
            return SyncResult(false, "PocketBase credentials not configured")
        }

        val token = PocketBaseApiClient.authenticate(creds)
            ?: return SyncResult(false, "PocketBase auth failed")

        val recordId = ensureRecord(creds, token)
            ?: return SyncResult(false, "Failed to create/find PocketBase record")

        val localPositions = LocalDataManager.readAllPlaybackPositions(context)
        val localHistory = LocalDataManager.readAllWatchHistory(context)
        val localResume = LocalDataManager.readAllResumeWatching(context)
        val localDeletedResume = LocalDataManager.readAllDeletedResume(context)
        val localPrefs = LocalDataManager.readAllPreferences(context)

        val fetchResult = PocketBaseApiClient.fetchRecordResult(creds, token, recordId)
        val remotePayload = if (fetchResult is ApiResult.Success) fetchResult.data else null

        val localPayload = SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName,
            watchHistory = localHistory,
            playbackPositions = localPositions,
            resumeWatching = localResume,
            deletedResumeWatching = localDeletedResume,
            preferences = localPrefs
        )

        val merged = if (remotePayload != null) {
            GitHubSyncManager.mergePayloads(localPayload, remotePayload)
        } else {
            localPayload
        }

        val pushResult = PocketBaseApiClient.updateRecord(creds, token, recordId, merged)
        val success = pushResult is ApiResult.Success

        val totalItems = merged.playbackPositions.size + merged.watchHistory.size + merged.resumeWatching.size + merged.preferences.size
        return SyncResult(
            success = success,
            message = if (success) "Pushed $totalItems items" else "Push failed",
            itemsPushed = totalItems
        )
    }

    fun pullOnly(context: Context, creds: SyncCredentials): SyncResult {
        if (!creds.isConfigured()) {
            return SyncResult(false, "PocketBase credentials not configured")
        }

        val token = PocketBaseApiClient.authenticate(creds)
            ?: return SyncResult(false, "PocketBase auth failed")

        val recordId = if (creds.hasPbRecord()) creds.pbRecordId else {
            PocketBaseApiClient.findExistingRecord(creds, token) ?: return SyncResult(false, "No sync record found in PB")
        }

        val fetchResult = PocketBaseApiClient.fetchRecordResult(creds, token, recordId)
        if (fetchResult !is ApiResult.Success) {
            return SyncResult(false, "Failed to fetch remote data")
        }
        val remotePayload = fetchResult.data

        val localPositions = LocalDataManager.readAllPlaybackPositions(context)
        val localHistory = LocalDataManager.readAllWatchHistory(context)
        val localResume = LocalDataManager.readAllResumeWatching(context)
        val localDeletedResume = LocalDataManager.readAllDeletedResume(context)
        val localPrefs = LocalDataManager.readAllPreferences(context)
        val localPayload = SyncPayload(
            watchHistory = localHistory,
            playbackPositions = localPositions,
            resumeWatching = localResume,
            deletedResumeWatching = localDeletedResume,
            preferences = localPrefs
        )

        val merged = GitHubSyncManager.mergePayloads(localPayload, remotePayload)
        val itemsPulled = GitHubSyncManager.writeRemoteDataToLocal(context, localPayload, merged)

        return SyncResult(
            success = true,
            message = "Pulled $itemsPulled items from remote",
            itemsPulled = itemsPulled
        )
    }

    private fun ensureRecord(creds: SyncCredentials, token: String): String? {
        if (creds.hasPbRecord()) return creds.pbRecordId

        val existing = PocketBaseApiClient.findExistingRecord(creds, token)
        if (existing != null) return existing

        val initialPayload = SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName
        )

        return PocketBaseApiClient.createRecord(creds, token, initialPayload)
    }

    fun fullSyncWithRateInfo(context: Context, creds: SyncCredentials): Pair<SyncResult, Int> {
        // PocketBase doesn't have strict rate limits by default like GitHub, but we can reuse the logic
        val result = fullSync(context, creds)
        return Pair(result, 0)
    }
}
