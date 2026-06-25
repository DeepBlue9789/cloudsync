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

    companion object {
        private const val TAG = "CloudSync"
        private const val SYNC_DEBOUNCE_MS = 2_000L // 2s debounce for auto-sync (reduced from 5s for faster backgrounding)
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
                    delay(3000) // Wait for app to fully initialize
                    val ctx = CloudStreamApp.context ?: activity ?: return@launch
                    Log.d(TAG, "Auto-sync on app open...")
                    val result = GitHubSyncManager.fullSync(ctx, creds)
                    saveLastSyncResult(result)
                    if (result.success) {
                        withContext(Dispatchers.Main) {
                            showToast("☁️ Synced: ${result.message}")
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
                if (isSyncing) return@OnSharedPreferenceChangeListener

                // Only trigger on playback-related changes
                if (key.contains("video_pos_dur") ||
                    key.contains("result_resume_watching") ||
                    key.contains("result_watch_state") ||
                    key.contains("result_episode") ||
                    key.contains("result_season")
                ) {
                    Log.d(TAG, "Detected playback change: $key")
                    scheduleDebouncedSync(context)
                }
            }

            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            Log.d(TAG, "Auto-sync listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up auto-sync listener: ${e.message}")
        }
    }

    /**
     * Schedule a debounced sync to batch rapid changes.
     */
    private fun scheduleDebouncedSync(context: Context) {
        syncJob?.cancel()
        syncJob = pluginScope.launch {
            delay(SYNC_DEBOUNCE_MS)
            if (isSyncing) return@launch

            isSyncing = true
            try {
                val creds = getCredentials()
                if (!creds.isConfigured()) return@launch

                val ctx = CloudStreamApp.context ?: context
                Log.d(TAG, "Debounced auto-sync triggered")
                val result = GitHubSyncManager.fullSync(ctx, creds)
                saveLastSyncResult(result)
                Log.d(TAG, "Auto-sync result: ${result.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-sync error: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
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
                    // Trigger sync when user leaves the app
                    scheduleDebouncedSync(activity)
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
