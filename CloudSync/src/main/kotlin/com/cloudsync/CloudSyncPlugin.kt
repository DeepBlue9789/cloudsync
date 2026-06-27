package com.cloudsync

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.*

@CloudstreamPlugin
class CloudSyncPlugin : Plugin() {
    var activity: AppCompatActivity? = null

    private var pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var syncJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var pauseDebounceJob: Job? = null
    private var lifecycleCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null
    private var registeredApp: android.app.Application? = null

    // ── Sync State ────────────────────────────────────────────────────────────

    /** True when a sync is currently in-flight */
    @Volatile
    private var isSyncing = false

    /** True when another sync has been requested while one is in-flight */
    @Volatile
    private var syncQueued = false

    /** Dirty flag — set when SharedPreferences data changes, cleared after sync */
    @Volatile
    private var isDirty = false

    /** Timestamp of last completed sync (success or not) */
    @Volatile
    private var lastSyncTimestamp = 0L

    /** Minimum interval between syncs in ms. Starts at 30s, increases on rate limit */
    @Volatile
    private var syncMinIntervalMs = 30_000L

    /** Default minimum interval (reset target after successful sync) */
    private val DEFAULT_MIN_INTERVAL_MS = 30_000L

    /** Maximum backoff interval (5 minutes) */
    private val MAX_BACKOFF_INTERVAL_MS = 300_000L

    /** Periodic sync tick interval */
    private val PERIODIC_TICK_MS = 20_000L

    /** Debounce time for detecting player pause (silence in position updates) */
    private val PAUSE_DEBOUNCE_MS = 2_000L

    /** Count of started activities — when this drops to 0, app went to background */
    @Volatile
    private var startedActivityCount = 0

    /** Last known episode/season key for episode-change detection */
    @Volatile
    private var lastKnownEpisodeKey = ""

    /** Whether position keys are actively being updated (playback in progress) */
    @Volatile
    private var isPlaybackActive = false

    companion object {
        private const val TAG = "CloudSync"
        internal const val CREDS_KEY = "CLOUDSYNC_CREDENTIALS"
        internal const val LAST_SYNC_KEY = "CLOUDSYNC_LAST_SYNC"
        internal const val LAST_SYNC_RESULT_KEY = "CLOUDSYNC_LAST_RESULT"

        /**
         * Get current sync credentials.
         */
        fun getCredentials(): SyncCredentials {
            return getKey<SyncCredentials>(CREDS_KEY) ?: SyncCredentials()
        }

        /**
         * Save sync credentials.
         */
        fun saveCredentials(creds: SyncCredentials) {
            setKey(CREDS_KEY, creds)
        }

        /**
         * Get last sync timestamp.
         */
        fun getLastSyncTime(): Long {
            return getKey<Long>(LAST_SYNC_KEY) ?: 0L
        }

        /**
         * Save last sync result for display.
         */
        fun saveLastSyncResult(result: SyncResult) {
            setKey(LAST_SYNC_KEY, result.timestamp)
            setKey(LAST_SYNC_RESULT_KEY, result.message)
        }

        fun getLastSyncMessage(): String {
            return getKey<String>(LAST_SYNC_RESULT_KEY) ?: "Never synced"
        }

        /**
         * Trigger CloudStream's UI reload events via reflection so that
         * freshly synced data (bookmarks, continue watching, etc.) appears
         * without requiring a manual app restart.
         *
         * We use reflection because these are internal MainActivity statics
         * that aren't exposed in the plugin API.
         */
        fun triggerUIRefresh() {
            try {
                val mainActivityClass = Class.forName("com.lagradost.cloudstream3.MainActivity")

                // Fire bookmarksUpdatedEvent(true) — refreshes library & bookmarks
                try {
                    val bookmarksMethod = mainActivityClass.getMethod("bookmarksUpdatedEvent", Boolean::class.javaPrimitiveType)
                    bookmarksMethod.invoke(null, true)
                    Log.d(TAG, "Triggered bookmarksUpdatedEvent")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not trigger bookmarksUpdatedEvent: ${e.message}")
                }

                // Fire reloadHomeEvent(true) — refreshes home page (Continue Watching)
                try {
                    val reloadMethod = mainActivityClass.getMethod("reloadHomeEvent", Boolean::class.javaPrimitiveType)
                    reloadMethod.invoke(null, true)
                    Log.d(TAG, "Triggered reloadHomeEvent")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not trigger reloadHomeEvent: ${e.message}")
                }

                // Fire reloadLibraryEvent(true) — refreshes library tab
                try {
                    val libraryMethod = mainActivityClass.getMethod("reloadLibraryEvent", Boolean::class.javaPrimitiveType)
                    libraryMethod.invoke(null, true)
                    Log.d(TAG, "Triggered reloadLibraryEvent")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not trigger reloadLibraryEvent: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not find MainActivity for UI refresh: ${e.message}")
            }
        }
    }

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        // Register the CloudSync MainAPI provider
        registerMainAPI(CloudSync(this))

        // Bind the settings panel launcher
        this.openSettings = { ctx ->
            CloudSyncSettings.showSettingsDialog(ctx)
        }

        Log.d(TAG, "CloudSync v6 plugin loaded")

        // Auto-sync on plugin load (app open) if configured
        val creds = getCredentials()
        if (creds.isConfigured() && creds.syncOnOpen) {
            pluginScope.launch {
                try {
                    delay(2000) // Wait for app to initialize
                    val ctx = CloudStreamApp.context ?: activity ?: return@launch
                    Log.d(TAG, "Auto-sync on app open...")
                    val result = if (creds.syncMethod == "pocketbase") {
                        PocketBaseSyncManager.fullSync(ctx, creds)
                    } else {
                        GitHubSyncManager.fullSync(ctx, creds)
                    }
                    saveLastSyncResult(result)
                    if (result.success) {
                        if (result.itemsPulled > 0) {
                            withContext(Dispatchers.Main) {
                                triggerUIRefresh()
                                if (creds.showSyncToasts) showToast("☁️ Synced: ${result.message}")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                if (creds.showSyncToasts) showToast("☁️ Synced: ${result.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-sync failed: ${e.message}")
                }
            }
        }

        // Set up all sync triggers
        if (creds.isConfigured()) {
            setupSharedPrefsListener(context)    // Triggers 1, 5 (pause + episode change)
            setupLifecycleCallbacks(context)     // Triggers 2, 3 (player close + app background)
            startPeriodicSync(context)           // Trigger 4 (every 20s, dirty-flag gated)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRIGGER 1 & 5: SharedPreferences Listener
    // Detects player pause (debounce on position silence) and episode changes.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Listen for SharedPreferences changes. Handles two sync triggers:
     *
     * Trigger 1 (Player Pause): When video_pos_dur keys update, we start a 2-second
     * debounce timer. Each new position update resets the timer. When updates stop
     * (player paused), the timer fires and triggers a sync.
     *
     * Trigger 5 (Episode Change): When result_episode or result_season keys change,
     * trigger an immediate sync — the user switched episodes.
     */
    private fun setupSharedPrefsListener(context: Context) {
        val creds = getCredentials()
        if (!creds.autoSync) return

        try {
            val prefs = context.getSharedPrefs()

            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null) return@OnSharedPreferenceChangeListener

                when {
                    // ── Playback position changed (video_pos_dur) ──
                    key.contains("video_pos_dur") -> {
                        isDirty = true
                        isPlaybackActive = true

                        // Trigger 1: Debounce — sync when position stops updating (pause)
                        pauseDebounceJob?.cancel()
                        pauseDebounceJob = pluginScope.launch {
                            delay(PAUSE_DEBOUNCE_MS)
                            // If we get here, position hasn't updated for 2s → player paused
                            isPlaybackActive = false
                            Log.d(TAG, "[Trigger 1] Player pause detected (position silence)")
                            requestSync(context, "player_pause")
                        }
                    }

                    // ── Episode or season changed ──
                    key.contains("result_episode") || key.contains("result_season") -> {
                        val newKey = key
                        if (newKey != lastKnownEpisodeKey) {
                            lastKnownEpisodeKey = newKey
                            isDirty = true
                            Log.d(TAG, "[Trigger 5] Episode/season changed: $key")
                            requestSync(context, "episode_change")
                        }
                    }

                    // ── Resume watching / watch state changes ──
                    key.contains("result_resume_watching") ||
                    key.contains("result_watch_state") -> {
                        isDirty = true
                        Log.d(TAG, "Data changed (will sync on next trigger): $key")
                    }
                }
            }

            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            Log.d(TAG, "SharedPreferences listener registered (Triggers 1 & 5)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up SharedPreferences listener: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRIGGER 2 & 3: Lifecycle Callbacks
    // Detects player close and app going to background.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Register ActivityLifecycleCallbacks to detect:
     *
     * Trigger 2 (Player Closed): When an activity whose class name contains "player"
     * (case-insensitive) is stopped, it means the player was closed.
     *
     * Trigger 3 (App Background): Track started activity count. When it drops to 0,
     * the entire app went to background.
     */
    private fun setupLifecycleCallbacks(context: Context) {
        val creds = getCredentials()
        if (!creds.syncOnPlaybackEnd) return

        try {
            val app = (context as? android.app.Activity)?.application
                ?: (context.applicationContext as? android.app.Application)
                ?: return

            lifecycleCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivityCount++
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    // Trigger 2: Player closed
                    val activityName = activity.javaClass.simpleName.lowercase()
                    if (activityName.contains("player")) {
                        isDirty = true  // Player always has unsaved progress
                        Log.d(TAG, "[Trigger 2] Player activity stopped: ${activity.javaClass.simpleName}")
                        requestSync(activity.applicationContext, "player_close")
                    }

                    // Trigger 3: App going to background
                    startedActivityCount--
                    if (startedActivityCount <= 0) {
                        startedActivityCount = 0
                        if (isDirty) {
                            Log.d(TAG, "[Trigger 3] App going to background with dirty data")
                            requestSync(activity.applicationContext, "app_background")
                        }
                    }
                }

                override fun onActivityPaused(activity: android.app.Activity) {
                    // Trigger 3 also: sync on any activity pause if data is dirty
                    // This catches the case where the app is being killed
                    val activityName = activity.javaClass.simpleName.lowercase()
                    if (activityName.contains("player") && isDirty) {
                        Log.d(TAG, "[Trigger 3] Player activity paused with dirty data")
                        requestSync(activity.applicationContext, "app_pause")
                    }
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            }

            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            registeredApp = app
            Log.d(TAG, "Lifecycle callbacks registered (Triggers 2 & 3)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up lifecycle callbacks: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRIGGER 4: Periodic Sync (every ~20 seconds, dirty-flag gated)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start a coroutine that ticks every 20 seconds. On each tick:
     * - If dirty flag is set AND enough time has passed since last sync → sync.
     * - If not dirty → skip (no API call).
     *
     * This avoids burning through the GitHub rate limit (5000/hr authenticated)
     * while still ensuring progress is saved regularly during active use.
     */
    private fun startPeriodicSync(context: Context) {
        val creds = getCredentials()
        if (!creds.autoSync) return

        periodicSyncJob?.cancel()
        periodicSyncJob = pluginScope.launch {
            // Initial delay to let app settle
            delay(10_000)
            Log.d(TAG, "Periodic sync timer started (${PERIODIC_TICK_MS / 1000}s interval)")

            while (isActive) {
                delay(PERIODIC_TICK_MS)

                if (!isDirty) {
                    continue  // Nothing changed, skip
                }

                val elapsed = System.currentTimeMillis() - lastSyncTimestamp
                if (elapsed < syncMinIntervalMs) {
                    Log.d(TAG, "[Trigger 4] Periodic tick: dirty but throttled (${elapsed/1000}s < ${syncMinIntervalMs/1000}s)")
                    continue
                }

                Log.d(TAG, "[Trigger 4] Periodic sync — dirty data, ${elapsed/1000}s since last sync")
                requestSync(context, "periodic")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UNIFIED SYNC DISPATCHER
    // All 5 triggers funnel through here.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Central sync request handler. Enforces:
     * 1. Minimum interval between syncs (adjustable for backoff).
     * 2. Coalescing — if a sync is in-flight, queue exactly one follow-up.
     * 3. Dirty flag — cleared on successful sync.
     *
     * @param context Android context
     * @param trigger Name of the trigger for logging
     */
    private fun requestSync(context: Context, trigger: String) {
        // Check minimum interval
        val elapsed = System.currentTimeMillis() - lastSyncTimestamp
        if (elapsed < syncMinIntervalMs) {
            Log.d(TAG, "Sync request ($trigger) throttled: ${elapsed/1000}s < ${syncMinIntervalMs/1000}s minimum")
            // Don't clear dirty flag — it will be picked up on next opportunity
            return
        }

        // If already syncing, queue one follow-up
        if (isSyncing) {
            syncQueued = true
            Log.d(TAG, "Sync request ($trigger) queued — sync already in-flight")
            return
        }

        // Cancel any pending scheduled sync to avoid double-fire
        syncJob?.cancel()
        syncJob = pluginScope.launch {
            executeSync(context, trigger)
        }
    }

    /**
     * Execute a sync with rate-limit awareness and exponential backoff.
     * Uses fullSyncWithRateInfo to detect 429/403 responses and adjust intervals.
     */
    private suspend fun executeSync(context: Context, trigger: String) {
        if (isSyncing) {
            syncQueued = true
            return
        }

        var keepGoing: Boolean
        do {
            isSyncing = true
            syncQueued = false
            try {
                val creds = getCredentials()
                if (!creds.isConfigured()) {
                    syncQueued = false
                    return
                }

                val ctx = CloudStreamApp.context ?: context
                Log.d(TAG, "Executing sync (trigger: $trigger)...")

                val (result, backoffSeconds) = if (creds.syncMethod == "pocketbase") {
                    PocketBaseSyncManager.fullSyncWithRateInfo(ctx, creds)
                } else {
                    GitHubSyncManager.fullSyncWithRateInfo(ctx, creds)
                }
                lastSyncTimestamp = System.currentTimeMillis()
                saveLastSyncResult(result)

                if (backoffSeconds > 0) {
                    // Rate limited — apply exponential backoff
                    val newInterval = (backoffSeconds * 1000L).coerceAtMost(MAX_BACKOFF_INTERVAL_MS)
                    syncMinIntervalMs = newInterval
                    Log.w(TAG, "Rate limited! Backoff interval set to ${newInterval/1000}s")
                    // Keep dirty flag so we retry on next opportunity
                } else if (result.success) {
                    // Success — clear dirty flag and reset backoff
                    isDirty = false
                    syncMinIntervalMs = DEFAULT_MIN_INTERVAL_MS
                    Log.d(TAG, "Sync ($trigger) success: ${result.message}")

                    if (creds.showSyncToasts) {
                        withContext(Dispatchers.Main) {
                            showToast("☁️ Auto-Sync: ${result.message}")
                        }
                    }
                } else {
                    // Non-rate-limit error
                    Log.e(TAG, "Sync ($trigger) failed: ${result.message}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    syncQueued = false
                    throw e
                }
                Log.e(TAG, "Sync ($trigger) error: ${e.message}")
            } finally {
                keepGoing = syncQueued
                if (!keepGoing) {
                    isSyncing = false
                }
            }
        } while (keepGoing)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Clean up when plugin is unloaded.
     */
    fun cleanup() {
        try {
            syncJob?.cancel()
            periodicSyncJob?.cancel()
            pauseDebounceJob?.cancel()
            pluginScope.cancel()

            // Unregister prefs listener
            prefsListener?.let { listener ->
                try {
                    val ctx = CloudStreamApp.context ?: activity
                    ctx?.getSharedPrefs()?.unregisterOnSharedPreferenceChangeListener(listener)
                } catch (_: Exception) {}
            }
            prefsListener = null

            // Unregister lifecycle callbacks
            lifecycleCallbacks?.let { callbacks ->
                registeredApp?.unregisterActivityLifecycleCallbacks(callbacks)
            }
            lifecycleCallbacks = null
            registeredApp = null

            Log.d(TAG, "CloudSync plugin cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    private fun showToast(message: String) {
        val ctx = CloudStreamApp.context ?: activity ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}
