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
    private var lifecycleCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null
    private var registeredApp: android.app.Application? = null

    @Volatile
    private var isSyncing = false

    @Volatile
    private var syncQueued = false

    companion object {
        private const val TAG = "CloudSync"
        private const val SYNC_THROTTLE_MS = 10_000L // 10 seconds throttle
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

        Log.d(TAG, "CloudSync plugin loaded")

        // Auto-sync on plugin load (app open) if configured
        val creds = getCredentials()
        if (creds.isConfigured() && creds.syncOnOpen) {
            pluginScope.launch {
                try {
                    delay(2000) // Wait for app to initialize
                    val ctx = CloudStreamApp.context ?: activity ?: return@launch
                    Log.d(TAG, "Auto-sync on app open...")
                    val result = GitHubSyncManager.fullSync(ctx, creds)
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

        // Set up auto-sync listener for playback position changes
        setupAutoSyncListener(context)

        // Set up lifecycle callbacks to sync on app pause/close
        setupLifecycleCallbacks(context)
    }

    /**
     * Listen for SharedPreferences changes to video_pos_dur keys.
     * When a playback position changes, schedule a debounced sync.
     */
    private fun setupAutoSyncListener(context: Context) {
        val creds = getCredentials()
        if (!creds.isConfigured() || !creds.autoSync) return

        try {
            val prefs = context.getSharedPrefs()

            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null) return@OnSharedPreferenceChangeListener

                // Only trigger on playback-related changes
                if (key.contains("video_pos_dur") ||
                    key.contains("result_resume_watching") ||
                    key.contains("result_watch_state") ||
                    key.contains("result_episode") ||
                    key.contains("result_season")
                ) {
                    Log.d(TAG, "Detected playback change: $key")
                    scheduleSync(context)
                }
            }

            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            Log.d(TAG, "Auto-sync listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up auto-sync listener: ${e.message}")
        }
    }

    /**
     * Schedule a sync. Uses throttling to ensure sync happens during continuous changes.
     */
    private fun scheduleSync(context: Context, immediate: Boolean = false) {
        if (immediate) {
            syncJob?.cancel()
            syncJob = pluginScope.launch {
                executeSync(context)
            }
            return
        }

        // If a sync is already scheduled/waiting, let it run (throttle instead of debounce)
        if (syncJob?.isActive == true) {
            return
        }

        syncJob = pluginScope.launch {
            delay(SYNC_THROTTLE_MS)
            executeSync(context)
        }
    }

    private suspend fun executeSync(context: Context) {
        if (isSyncing) {
            syncQueued = true
            return
        }

        var keepGoing = false
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
                Log.d(TAG, "Auto-sync triggered")
                val result = GitHubSyncManager.fullSync(ctx, creds)
                saveLastSyncResult(result)
                Log.d(TAG, "Auto-sync result: ${result.message}")
                
                if (creds.showSyncToasts) {
                    withContext(Dispatchers.Main) {
                        showToast("☁️ Auto-Sync: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    syncQueued = false
                    throw e
                }
                Log.e(TAG, "Auto-sync error: ${e.message}")
            } finally {
                keepGoing = syncQueued
                if (!keepGoing) {
                    isSyncing = false
                }
            }
        } while (keepGoing)
    }

    /**
     * Set up lifecycle callbacks to trigger sync on app going to background.
     */
    private fun setupLifecycleCallbacks(context: Context) {
        val creds = getCredentials()
        if (!creds.isConfigured() || !creds.syncOnPlaybackEnd) return

        try {
            val app = (context as? android.app.Activity)?.application
                ?: (context.applicationContext as? android.app.Application)
                ?: return

            lifecycleCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityPaused(activity: android.app.Activity) {
                    // Trigger sync immediately when user leaves the app or pauses player
                    scheduleSync(activity, immediate = true)
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityStarted(activity: android.app.Activity) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            }

            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            registeredApp = app
            Log.d(TAG, "Lifecycle callbacks registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up lifecycle callbacks: ${e.message}")
        }
    }

    /**
     * Clean up when plugin is unloaded.
     */
    fun cleanup() {
        try {
            syncJob?.cancel()
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
