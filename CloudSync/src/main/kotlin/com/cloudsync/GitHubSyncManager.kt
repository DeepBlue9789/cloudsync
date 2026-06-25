package com.cloudsync

import android.content.Context
import com.lagradost.api.Log
import java.util.UUID

/**
 * Core sync engine — orchestrates the full push/pull/merge cycle.
 *
 * Merge strategy per data type:
 * - PlaybackPositions: Latest timestamp wins per media ID. If timestamps equal, higher position wins.
 * - WatchHistory: Latest timestamp wins per media ID.
 * - ResumeWatching: Latest timestamp wins per media ID.
 */
object GitHubSyncManager {
    private const val TAG = "CloudSync-Manager"

    /**
     * Perform a full bidirectional sync:
     * 1. Read local data
     * 2. Fetch remote data from GitHub
     * 3. Merge using per-item timestamp conflict resolution
     * 4. Write merged data back to both local and remote
     */
    fun fullSync(context: Context, creds: SyncCredentials): SyncResult {
        if (!creds.isConfigured()) {
            return SyncResult(false, "GitHub token not configured")
        }

        Log.d(TAG, "Starting full sync...")

        // Step 1: Ensure we have a gist
        val gistId = ensureGist(creds)
            ?: return SyncResult(false, "Failed to create/find sync gist")

        // Step 2: Read local data
        val localPositions = LocalDataManager.readAllPlaybackPositions(context)
        val localHistory = LocalDataManager.readAllWatchHistory(context)
        val localResume = LocalDataManager.readAllResumeWatching(context)
        val localPrefs = LocalDataManager.readAllPreferences(context)

        val localPayload = SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName,
            watchHistory = localHistory,
            playbackPositions = localPositions,
            resumeWatching = localResume,
            preferences = localPrefs
        )

        // Step 3: Fetch remote data
        val remotePayload = GitHubApiClient.fetchGist(creds.token, gistId)

        // Step 4: Merge
        val merged = if (remotePayload != null) {
            mergePayloads(localPayload, remotePayload)
        } else {
            localPayload
        }

        // Step 5: Write merged data back to local
        var itemsPulled = 0
        if (remotePayload != null) {
            itemsPulled = writeRemoteDataToLocal(context, localPayload, merged)
        }

        // Step 6: Push merged data to GitHub
        val updatedMerged = merged.copy(
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName
        )

        val pushSuccess = GitHubApiClient.updateGist(creds.token, gistId, updatedMerged)
        if (!pushSuccess) {
            return SyncResult(
                success = false,
                message = "Failed to push data to GitHub",
                itemsPulled = itemsPulled
            )
        }

        val totalItems = merged.playbackPositions.size +
            merged.watchHistory.size +
            merged.resumeWatching.size +
            merged.preferences.size

        Log.d(TAG, "Sync complete: $totalItems total items, $itemsPulled pulled from remote")

        return SyncResult(
            success = true,
            message = "Synced $totalItems items successfully",
            itemsPushed = totalItems,
            itemsPulled = itemsPulled,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Push-only sync — read local and push to GitHub (no pull).
     * Used for manual "backup" operations.
     */
    fun pushOnly(context: Context, creds: SyncCredentials): SyncResult {
        if (!creds.isConfigured()) {
            return SyncResult(false, "GitHub token not configured")
        }

        val gistId = ensureGist(creds) ?: return SyncResult(false, "Failed to create/find sync gist")

        val localPositions = LocalDataManager.readAllPlaybackPositions(context)
        val localHistory = LocalDataManager.readAllWatchHistory(context)
        val localResume = LocalDataManager.readAllResumeWatching(context)
        val localPrefs = LocalDataManager.readAllPreferences(context)

        // Fetch remote and merge to avoid losing other device's data
        val remotePayload = GitHubApiClient.fetchGist(creds.token, gistId)
        val localPayload = SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName,
            watchHistory = localHistory,
            playbackPositions = localPositions,
            resumeWatching = localResume,
            preferences = localPrefs
        )

        val merged = if (remotePayload != null) {
            mergePayloads(localPayload, remotePayload)
        } else {
            localPayload
        }

        val success = GitHubApiClient.updateGist(creds.token, gistId, merged)

        return SyncResult(
            success = success,
            message = if (success) "Pushed ${merged.playbackPositions.size + merged.watchHistory.size + merged.preferences.size} items" else "Push failed",
            itemsPushed = merged.playbackPositions.size + merged.watchHistory.size + merged.resumeWatching.size + merged.preferences.size
        )
    }

    /**
     * Pull-only sync — fetch from GitHub and write to local (no push).
     * Used for manual "restore" operations.
     */
    fun pullOnly(context: Context, creds: SyncCredentials): SyncResult {
        if (!creds.isConfigured()) {
            return SyncResult(false, "GitHub token not configured")
        }

        val gistId = if (creds.hasGist()) creds.gistId else {
            GitHubApiClient.findExistingGist(creds.token) ?: return SyncResult(false, "No sync gist found")
        }

        val remotePayload = GitHubApiClient.fetchGist(creds.token, gistId)
            ?: return SyncResult(false, "Failed to fetch remote data")

        val localPositions = LocalDataManager.readAllPlaybackPositions(context)
        val localHistory = LocalDataManager.readAllWatchHistory(context)
        val localResume = LocalDataManager.readAllResumeWatching(context)
        val localPrefs = LocalDataManager.readAllPreferences(context)
        val localPayload = SyncPayload(
            watchHistory = localHistory,
            playbackPositions = localPositions,
            resumeWatching = localResume,
            preferences = localPrefs
        )

        val merged = mergePayloads(localPayload, remotePayload)
        val itemsPulled = writeRemoteDataToLocal(context, localPayload, merged)

        return SyncResult(
            success = true,
            message = "Pulled $itemsPulled items from remote",
            itemsPulled = itemsPulled
        )
    }

    /**
     * Ensure a gist exists for sync. Creates one if needed.
     * Returns the gist ID or null on failure.
     */
    private fun ensureGist(creds: SyncCredentials): String? {
        if (creds.hasGist()) return creds.gistId

        // Try to find existing
        val existing = GitHubApiClient.findExistingGist(creds.token)
        if (existing != null) return existing

        // Create new
        val initialPayload = SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = creds.deviceId,
            deviceName = creds.deviceName
        )

        return GitHubApiClient.createGist(creds.token, initialPayload)
    }

    /**
     * Merge two payloads using per-item latest-timestamp-wins strategy.
     *
     * For playback positions specifically:
     * - If timestamps differ → latest timestamp wins
     * - If timestamps are equal → higher position wins (user watched further)
     */
    fun mergePayloads(local: SyncPayload, remote: SyncPayload): SyncPayload {
        val mergedPositions = mergeByTimestamp(
            local.playbackPositions,
            remote.playbackPositions
        ) { localItem, remoteItem ->
            // Custom merge for playback positions: on equal timestamps, higher position wins
            when {
                localItem.lastUpdated > remoteItem.lastUpdated -> localItem
                remoteItem.lastUpdated > localItem.lastUpdated -> remoteItem
                localItem.position >= remoteItem.position -> localItem
                else -> remoteItem
            }
        }

        val mergedHistory = mergeByTimestamp(
            local.watchHistory,
            remote.watchHistory
        ) { localItem, remoteItem ->
            if (localItem.lastUpdated >= remoteItem.lastUpdated) localItem else remoteItem
        }

        val mergedResume = mergeByTimestamp(
            local.resumeWatching,
            remote.resumeWatching
        ) { localItem, remoteItem ->
            if (localItem.lastUpdated >= remoteItem.lastUpdated) localItem else remoteItem
        }

        val mergedPrefs = mergeByTimestamp(
            local.preferences,
            remote.preferences
        ) { localItem, remoteItem ->
            if (localItem.lastUpdated >= remoteItem.lastUpdated) localItem else remoteItem
        }

        return SyncPayload(
            version = 1,
            lastSync = System.currentTimeMillis(),
            deviceId = local.deviceId.ifBlank { remote.deviceId },
            deviceName = local.deviceName.ifBlank { remote.deviceName },
            watchHistory = mergedHistory,
            playbackPositions = mergedPositions,
            resumeWatching = mergedResume,
            preferences = mergedPrefs
        )
    }

    /**
     * Generic merge function for maps with timestamped values.
     */
    private fun <T> mergeByTimestamp(
        local: Map<String, T>,
        remote: Map<String, T>,
        resolver: (T, T) -> T
    ): Map<String, T> {
        val merged = mutableMapOf<String, T>()

        // Add all local entries
        merged.putAll(local)

        // Merge remote entries
        for ((key, remoteValue) in remote) {
            val localValue = merged[key]
            if (localValue == null) {
                // New from remote
                merged[key] = remoteValue
            } else {
                // Conflict — resolve
                merged[key] = resolver(localValue, remoteValue)
            }
        }

        return merged
    }

    /**
     * Write merged/remote data to local storage.
     * Only writes items that are new or more recent than local.
     * Returns the number of items written.
     */
    private fun writeRemoteDataToLocal(
        context: Context,
        localPayload: SyncPayload,
        mergedPayload: SyncPayload
    ): Int {
        var count = 0

        // Write playback positions
        for ((mediaId, mergedPos) in mergedPayload.playbackPositions) {
            val localPos = localPayload.playbackPositions[mediaId]
            // Only write if this is new or more recent than local
            if (localPos == null || mergedPos.lastUpdated > localPos.lastUpdated ||
                (mergedPos.lastUpdated == localPos.lastUpdated && mergedPos.position > localPos.position)
            ) {
                LocalDataManager.writePlaybackPosition(
                    context, 
                    mediaId, 
                    mergedPos.position, 
                    mergedPos.duration,
                    mergedPos.lastUpdated
                )
                count++
            }
        }

        // Write watch history
        for ((mediaId, mergedEntry) in mergedPayload.watchHistory) {
            val localEntry = localPayload.watchHistory[mediaId]
            if (localEntry == null || mergedEntry.lastUpdated > localEntry.lastUpdated) {
                LocalDataManager.writeWatchState(context, mediaId, mergedEntry)
                count++
            }
        }

        // Write resume watching entries
        for ((mediaId, mergedEntry) in mergedPayload.resumeWatching) {
            val localEntry = localPayload.resumeWatching[mediaId]
            if (localEntry == null || mergedEntry.lastUpdated > localEntry.lastUpdated) {
                LocalDataManager.writeResumeWatching(context, mediaId, mergedEntry)
                // Also sync episode/season state for series
                if (mergedEntry.episode != null || mergedEntry.season != null) {
                    LocalDataManager.writeEpisodeState(context, mediaId, mergedEntry.episode, mergedEntry.season)
                }
                count++
            }
        }

        // Write preferences
        val prefsToWrite = mutableMapOf<String, PrefEntry>()
        for ((key, mergedPref) in mergedPayload.preferences) {
            val localPref = localPayload.preferences[key]
            if (localPref == null || mergedPref.lastUpdated > localPref.lastUpdated) {
                prefsToWrite[key] = mergedPref
                count++
            }
        }
        if (prefsToWrite.isNotEmpty()) {
            LocalDataManager.writePreferences(context, prefsToWrite)
        }

        Log.d(TAG, "Wrote $count items from remote to local")
        return count
    }

    /**
     * Generate a unique device ID.
     */
    fun generateDeviceId(): String {
        return UUID.randomUUID().toString().take(8)
    }
}
