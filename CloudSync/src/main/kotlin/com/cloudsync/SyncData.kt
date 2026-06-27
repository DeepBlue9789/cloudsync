package com.cloudsync

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Top-level sync payload stored in GitHub Gist.
 * One gist file = one device's full sync state.
 * On merge, we compare per-item timestamps.
 */
data class SyncPayload(
    @JsonProperty("version") val version: Int = 1,
    @JsonProperty("lastSync") val lastSync: Long = 0L,
    @JsonProperty("deviceId") val deviceId: String = "",
    @JsonProperty("deviceName") val deviceName: String = "",
    @JsonProperty("watchHistory") val watchHistory: Map<String, WatchEntry> = emptyMap(),
    @JsonProperty("playbackPositions") val playbackPositions: Map<String, PlaybackPosition> = emptyMap(),
    @JsonProperty("resumeWatching") val resumeWatching: Map<String, ResumeEntry> = emptyMap(),
    @JsonProperty("deletedResumeWatching") val deletedResumeWatching: Map<String, Long> = emptyMap(),
    @JsonProperty("preferences") val preferences: Map<String, PrefEntry> = emptyMap()
)

/**
 * A simple preference entry to store primitive types from SharedPreferences.
 * Used for syncing profiles and source priority settings.
 */
data class PrefEntry(
    @JsonProperty("valueStr") val valueStr: String = "",
    @JsonProperty("type") val type: String = "String",
    @JsonProperty("lastUpdated") val lastUpdated: Long = 0L
)

/**
 * Watch history entry — tracks what the user has added to their watch list
 * and what state it's in (watching, completed, dropped, etc.)
 */
data class WatchEntry(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("apiName") val apiName: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("posterUrl") val posterUrl: String? = null,
    @JsonProperty("watchState") val watchState: Int = 0,
    @JsonProperty("lastUpdated") val lastUpdated: Long = 0L
)

/**
 * Exact playback position for a media item.
 * This is the core differentiator from Ultima — we sync exact millisecond position.
 *
 * Key format in CloudStream: "{account}/video_pos_dur/{id}" → {"position": ms, "duration": ms}
 */
data class PlaybackPosition(
    @JsonProperty("position") val position: Long = 0L,
    @JsonProperty("duration") val duration: Long = 0L,
    @JsonProperty("percentage") val percentage: Float = 0f,
    @JsonProperty("lastUpdated") val lastUpdated: Long = 0L
)

/**
 * Resume watching entry — the data CloudStream uses to show "Continue Watching" on home screen.
 * Key format: "{account}/result_resume_watching_2/{id}" → JSON
 */
data class ResumeEntry(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("apiName") val apiName: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("posterUrl") val posterUrl: String? = null,
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("parentId") val parentId: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("isFromDownload") val isFromDownload: Boolean = false,
    @JsonProperty("watchPos") val watchPos: Long? = null,
    @JsonProperty("duration") val duration: Long? = null,
    @JsonProperty("lastUpdated") val lastUpdated: Long = 0L,
    // Store the raw JSON string from CloudStream so we can restore it perfectly
    @JsonProperty("rawJson") val rawJson: String? = null
)

/**
 * GitHub Gist credentials stored locally.
 */
data class SyncCredentials(
    @JsonProperty("token") val token: String = "",
    @JsonProperty("gistId") val gistId: String = "",
    @JsonProperty("deviceId") val deviceId: String = "",
    @JsonProperty("deviceName") val deviceName: String = "Device",
    @JsonProperty("autoSync") val autoSync: Boolean = true,
    @JsonProperty("syncOnOpen") val syncOnOpen: Boolean = true,
    @JsonProperty("syncOnPlaybackEnd") val syncOnPlaybackEnd: Boolean = true,
    @JsonProperty("showSyncToasts") val showSyncToasts: Boolean = false
) {
    fun isConfigured(): Boolean = token.isNotBlank()
    fun hasGist(): Boolean = gistId.isNotBlank()
}

/**
 * Internal PosDur class matching CloudStream's storage format.
 * This is the exact format CloudStream uses in video_pos_dur keys.
 */
data class PosDur(
    @JsonProperty("position") val position: Long = 0L,
    @JsonProperty("duration") val duration: Long = 0L
)

/**
 * Sync operation result.
 */
data class SyncResult(
    val success: Boolean,
    val message: String,
    val itemsPushed: Int = 0,
    val itemsPulled: Int = 0,
    val conflicts: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
