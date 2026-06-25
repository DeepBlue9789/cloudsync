package com.cloudsync

import java.io.File
import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs

/**
 * Reads and writes CloudStream's local playback data using the app's internal storage APIs.
 *
 * CloudStream stores data in SharedPreferences with key patterns:
 * - Playback position: "{account}/video_pos_dur/{id}" → {"position": ms, "duration": ms}
 * - Watch state: "{account}/result_watch_state/{id}" → int
 * - Watch state data: "{account}/result_watch_state_data/{id}" → JSON
 * - Resume watching: "{account}/result_resume_watching_2/{id}" → JSON
 * - Episode selection: "{account}/result_episode/{id}" → int
 * - Season selection: "{account}/result_season/{id}" → int
 * - Dub status: "{account}/result_dub/{id}" → JSON
 */
object LocalDataManager {
    private const val TAG = "CloudSync-Local"
    private const val POS_CACHE_FILE = "cloudsync_pos_cache.json"

    private val mapper = jacksonObjectMapper()

    data class CachedPos(
        val position: Long,
        val duration: Long,
        val timestamp: Long
    )

    private fun getPosCache(context: Context): MutableMap<String, CachedPos> {
        val file = File(context.filesDir, POS_CACHE_FILE)
        if (!file.exists()) return mutableMapOf()
        return try {
            mapper.readValue(file.readText())
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun savePosCache(context: Context, cache: Map<String, CachedPos>) {
        try {
            val file = File(context.filesDir, POS_CACHE_FILE)
            file.writeText(mapper.writeValueAsString(cache))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pos cache: ${e.message}")
        }
    }

    // CloudStream DataStore key prefixes (from DataStoreHelper.kt)
    private const val VIDEO_POS_DUR = "video_pos_dur"
    private const val RESULT_WATCH_STATE = "result_watch_state"
    private const val RESULT_WATCH_STATE_DATA = "result_watch_state_data"
    private const val RESULT_RESUME_WATCHING = "result_resume_watching_2"
    private const val RESULT_EPISODE = "result_episode"
    private const val RESULT_SEASON = "result_season"

    // Account index is typically "0" for the default profile
    private fun getAccountPrefix(): String {
        val accountIndex = getKey<Int>("data_store_helper/account_key_index") ?: 0
        return "$accountIndex"
    }

    /**
     * Read all playback positions from CloudStream's internal storage.
     * Returns a map of media ID → PlaybackPosition.
     */
    fun readAllPlaybackPositions(context: Context): Map<String, PlaybackPosition> {
        val positions = mutableMapOf<String, PlaybackPosition>()

        try {
            val prefs = context.getSharedPrefs()
            val allEntries = prefs.all
            val prefix = "${getAccountPrefix()}/$VIDEO_POS_DUR/"
            
            val cache = getPosCache(context)
            var cacheUpdated = false

            for ((key, value) in allEntries) {
                if (!key.startsWith(prefix)) continue

                val mediaId = key.removePrefix(prefix)
                if (mediaId.isBlank()) continue

                try {
                    val jsonStr = value as? String ?: continue
                    val posDur = mapper.readValue<PosDur>(jsonStr)

                    if (posDur.duration > 0) {
                        val percentage = if (posDur.duration > 0) {
                            (posDur.position.toFloat() / posDur.duration.toFloat()) * 100f
                        } else 0f
                        
                        val cached = cache[mediaId]
                        val timestamp = if (cached != null && cached.position == posDur.position && cached.duration == posDur.duration) {
                            cached.timestamp
                        } else {
                            cacheUpdated = true
                            val now = System.currentTimeMillis()
                            cache[mediaId] = CachedPos(posDur.position, posDur.duration, now)
                            now
                        }

                        positions[mediaId] = PlaybackPosition(
                            position = posDur.position,
                            duration = posDur.duration,
                            percentage = percentage,
                            lastUpdated = timestamp
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse pos/dur for key $key: ${e.message}")
                }
            }
            
            if (cacheUpdated) {
                savePosCache(context, cache)
            }

            Log.d(TAG, "Read ${positions.size} playback positions")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading playback positions: ${e.message}")
        }

        return positions
    }

    /**
     * Read all watch history entries (bookmarks/watch states).
     * Returns a map of media ID → WatchEntry.
     */
    fun readAllWatchHistory(context: Context): Map<String, WatchEntry> {
        val history = mutableMapOf<String, WatchEntry>()

        try {
            val prefs = context.getSharedPrefs()
            val allEntries = prefs.all
            val prefix = "${getAccountPrefix()}/$RESULT_WATCH_STATE_DATA/"

            for ((key, value) in allEntries) {
                if (!key.startsWith(prefix)) continue

                val mediaId = key.removePrefix(prefix)
                if (mediaId.isBlank()) continue

                try {
                    val jsonStr = value as? String ?: continue

                    // Parse the raw CloudStream watch state data
                    val data = mapper.readValue<Map<String, Any?>>(jsonStr)

                    val updateTime = (data["latestUpdatedTime"] as? Number)?.toLong() 
                        ?: (data["bookmarkedTime"] as? Number)?.toLong()
                        ?: System.currentTimeMillis()

                    history[mediaId] = WatchEntry(
                        name = data["name"] as? String ?: "",
                        url = data["url"] as? String ?: "",
                        apiName = data["apiName"] as? String ?: "",
                        type = data["type"] as? String ?: "",
                        posterUrl = data["posterUrl"] as? String,
                        watchState = (data["watchState"] as? Int)
                            ?: (data["watchState"] as? Number)?.toInt() ?: 0,
                        lastUpdated = updateTime
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse watch state for key $key: ${e.message}")
                }
            }

            Log.d(TAG, "Read ${history.size} watch history entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading watch history: ${e.message}")
        }

        return history
    }

    /**
     * Read all resume watching entries.
     * Returns a map of media ID → ResumeEntry.
     */
    fun readAllResumeWatching(context: Context): Map<String, ResumeEntry> {
        val resume = mutableMapOf<String, ResumeEntry>()

        try {
            val prefs = context.getSharedPrefs()
            val allEntries = prefs.all
            val prefix = "${getAccountPrefix()}/$RESULT_RESUME_WATCHING/"

            for ((key, value) in allEntries) {
                if (!key.startsWith(prefix)) continue

                val mediaId = key.removePrefix(prefix)
                if (mediaId.isBlank()) continue

                try {
                    val jsonStr = value as? String ?: continue
                    val data = mapper.readValue<Map<String, Any?>>(jsonStr)

                    val updateTime = (data["updateTime"] as? Number)?.toLong() ?: System.currentTimeMillis()

                    resume[mediaId] = ResumeEntry(
                        name = data["name"] as? String ?: "",
                        url = data["url"] as? String ?: "",
                        apiName = data["apiName"] as? String ?: "",
                        type = data["type"] as? String ?: "",
                        posterUrl = data["posterUrl"] as? String,
                        id = (data["id"] as? Number)?.toInt(),
                        parentId = (data["parentId"] as? Number)?.toInt(),
                        episode = (data["episode"] as? Number)?.toInt(),
                        season = (data["season"] as? Number)?.toInt(),
                        isFromDownload = data["isFromDownload"] as? Boolean ?: false,
                        lastUpdated = updateTime,
                        rawJson = jsonStr
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse resume entry for key $key: ${e.message}")
                }
            }

            Log.d(TAG, "Read ${resume.size} resume watching entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading resume watching: ${e.message}")
        }

        return resume
    }

    /**
     * Write a playback position back into CloudStream's storage.
     * This is the key operation for restoring exact playback timestamps on another device.
     */
    fun writePlaybackPosition(context: Context, mediaId: String, position: Long, duration: Long, lastUpdated: Long) {
        try {
            val key = "${getAccountPrefix()}/$VIDEO_POS_DUR/$mediaId"
            val posDur = PosDur(position = position, duration = duration)
            val json = mapper.writeValueAsString(posDur)

            val prefs = context.getSharedPrefs()
            prefs.edit().putString(key, json).apply()
            
            // Update cache so the local device knows about the pulled timestamp
            val cache = getPosCache(context)
            cache[mediaId] = CachedPos(position, duration, lastUpdated)
            savePosCache(context, cache)

            Log.d(TAG, "Wrote playback pos for $mediaId: ${position}ms / ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing playback position for $mediaId: ${e.message}")
        }
    }

    /**
     * Write a watch state entry back into CloudStream's storage.
     */
    fun writeWatchState(context: Context, mediaId: String, entry: WatchEntry) {
        try {
            val stateKey = "${getAccountPrefix()}/$RESULT_WATCH_STATE/$mediaId"
            val dataKey = "${getAccountPrefix()}/$RESULT_WATCH_STATE_DATA/$mediaId"

            val prefs = context.getSharedPrefs()
            val editor = prefs.edit()

            // Write the watch state integer
            editor.putInt(stateKey, entry.watchState)

            // Write the full watch state data as JSON
            val data = mapOf(
                "name" to entry.name,
                "url" to entry.url,
                "apiName" to entry.apiName,
                "type" to entry.type,
                "posterUrl" to entry.posterUrl,
                "watchState" to entry.watchState
            )
            editor.putString(dataKey, mapper.writeValueAsString(data))
            editor.apply()

            Log.d(TAG, "Wrote watch state for $mediaId: state=${entry.watchState}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing watch state for $mediaId: ${e.message}")
        }
    }

    /**
     * Write a resume watching entry back into CloudStream's storage.
     */
    fun writeResumeWatching(context: Context, mediaId: String, entry: ResumeEntry) {
        try {
            val key = "${getAccountPrefix()}/$RESULT_RESUME_WATCHING/$mediaId"

            // If we have the raw JSON from the original source, use it for perfect fidelity
            val json = if (entry.rawJson != null) {
                entry.rawJson
            } else {
                val data = mutableMapOf<String, Any?>(
                    "name" to entry.name,
                    "url" to entry.url,
                    "apiName" to entry.apiName,
                    "type" to entry.type,
                    "posterUrl" to entry.posterUrl,
                    "id" to entry.id,
                    "parentId" to entry.parentId,
                    "episode" to entry.episode,
                    "season" to entry.season,
                    "isFromDownload" to entry.isFromDownload
                )
                mapper.writeValueAsString(data)
            }

            val prefs = context.getSharedPrefs()
            prefs.edit().putString(key, json).apply()

            Log.d(TAG, "Wrote resume watching for $mediaId: ${entry.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing resume watching for $mediaId: ${e.message}")
        }
    }

    /**
     * Write episode selection state for accurate per-episode resume.
     */
    fun writeEpisodeState(context: Context, mediaId: String, episode: Int?, season: Int?) {
        try {
            val prefs = context.getSharedPrefs()
            val editor = prefs.edit()

            if (episode != null) {
                val epKey = "${getAccountPrefix()}/$RESULT_EPISODE/$mediaId"
                editor.putInt(epKey, episode)
            }
            if (season != null) {
                val seasonKey = "${getAccountPrefix()}/$RESULT_SEASON/$mediaId"
                editor.putInt(seasonKey, season)
            }

            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing episode state for $mediaId: ${e.message}")
        }
    }

    /**
     * Get a summary of all local sync-able data for display purposes.
     */
    fun getSyncSummary(context: Context): Map<String, Int> {
        val positions = readAllPlaybackPositions(context)
        val history = readAllWatchHistory(context)
        val resume = readAllResumeWatching(context)

        return mapOf(
            "playbackPositions" to positions.size,
            "watchHistory" to history.size,
            "resumeWatching" to resume.size,
            "total" to (positions.size + history.size + resume.size)
        )
    }
}
