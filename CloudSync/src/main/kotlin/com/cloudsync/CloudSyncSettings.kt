package com.cloudsync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.Color
import android.util.TypedValue
import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.*

/**
 * Settings helper for CloudSync extension.
 *
 * CloudStream extensions expose settings via the plugin's settings interface.
 * This object manages the settings logic and sync operations triggered from settings.
 */
object CloudSyncSettings {
    private const val TAG = "CloudSync-Settings"
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Perform initial setup: validate token, find/create gist, and do first sync.
     */
    fun setupAndSync(
        context: Context,
        token: String,
        deviceName: String,
        onResult: (SyncResult) -> Unit
    ) {
        settingsScope.launch {
            try {
                // Step 1: Validate token
                withContext(Dispatchers.Main) {
                    showToast("Validating GitHub token...")
                }

                val isValid = GitHubApiClient.validateToken(token)
                if (!isValid) {
                    val result = SyncResult(false, "Invalid GitHub token. Make sure it has 'gist' scope.")
                    withContext(Dispatchers.Main) { onResult(result) }
                    return@launch
                }

                // Step 2: Find or create gist
                withContext(Dispatchers.Main) {
                    showToast("Finding sync gist...")
                }

                var gistId = GitHubApiClient.findExistingGist(token)
                if (gistId == null) {
                    withContext(Dispatchers.Main) {
                        showToast("Creating new sync gist...")
                    }

                    val deviceId = GitHubSyncManager.generateDeviceId()
                    val initialPayload = SyncPayload(
                        version = 1,
                        lastSync = System.currentTimeMillis(),
                        deviceId = deviceId,
                        deviceName = deviceName
                    )
                    gistId = GitHubApiClient.createGist(token, initialPayload)
                }

                if (gistId == null) {
                    val result = SyncResult(false, "Failed to create/find sync gist. Check your token permissions.")
                    withContext(Dispatchers.Main) { onResult(result) }
                    return@launch
                }

                // Step 3: Save credentials
                val creds = SyncCredentials(
                    token = token,
                    gistId = gistId,
                    deviceId = CloudSyncPlugin.getCredentials().deviceId.ifBlank {
                        GitHubSyncManager.generateDeviceId()
                    },
                    deviceName = deviceName.ifBlank { "Device" },
                    autoSync = true,
                    syncOnOpen = true,
                    syncOnPlaybackEnd = true
                )
                CloudSyncPlugin.saveCredentials(creds)

                // Step 4: Perform first sync
                withContext(Dispatchers.Main) {
                    showToast("Performing first sync...")
                }

                val syncResult = GitHubSyncManager.fullSync(context, creds)
                CloudSyncPlugin.saveLastSyncResult(syncResult)

                withContext(Dispatchers.Main) {
                    onResult(syncResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Setup error: ${e.message}")
                val result = SyncResult(false, "Setup failed: ${e.message}")
                withContext(Dispatchers.Main) { onResult(result) }
            }
        }
    }

    /**
     * Manual sync trigger from settings.
     */
    fun manualSync(context: Context, onResult: (SyncResult) -> Unit) {
        settingsScope.launch {
            try {
                val creds = CloudSyncPlugin.getCredentials()
                if (!creds.isConfigured()) {
                    val result = SyncResult(false, "Not configured. Enter your GitHub token first.")
                    withContext(Dispatchers.Main) { onResult(result) }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    showToast("☁️ Syncing...")
                }

                val result = GitHubSyncManager.fullSync(context, creds)
                CloudSyncPlugin.saveLastSyncResult(result)

                withContext(Dispatchers.Main) {
                    onResult(result)
                    if (result.success) {
                        showToast("☁️ ${result.message}")
                    } else {
                        showToast("❌ ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual sync error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(SyncResult(false, "Sync failed: ${e.message}"))
                    showToast("❌ Sync failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Force pull from remote (restore).
     */
    fun forcePull(context: Context, onResult: (SyncResult) -> Unit) {
        settingsScope.launch {
            try {
                val creds = CloudSyncPlugin.getCredentials()
                if (!creds.isConfigured()) {
                    withContext(Dispatchers.Main) {
                        onResult(SyncResult(false, "Not configured"))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { showToast("📥 Pulling from cloud...") }

                val result = GitHubSyncManager.pullOnly(context, creds)
                CloudSyncPlugin.saveLastSyncResult(result)

                withContext(Dispatchers.Main) {
                    onResult(result)
                    showToast(if (result.success) "📥 ${result.message}" else "❌ ${result.message}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(SyncResult(false, "Pull failed: ${e.message}"))
                }
            }
        }
    }

    /**
     * Force push to remote (backup).
     */
    fun forcePush(context: Context, onResult: (SyncResult) -> Unit) {
        settingsScope.launch {
            try {
                val creds = CloudSyncPlugin.getCredentials()
                if (!creds.isConfigured()) {
                    withContext(Dispatchers.Main) {
                        onResult(SyncResult(false, "Not configured"))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { showToast("📤 Pushing to cloud...") }

                val result = GitHubSyncManager.pushOnly(context, creds)
                CloudSyncPlugin.saveLastSyncResult(result)

                withContext(Dispatchers.Main) {
                    onResult(result)
                    showToast(if (result.success) "📤 ${result.message}" else "❌ ${result.message}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(SyncResult(false, "Push failed: ${e.message}"))
                }
            }
        }
    }

    /**
     * Update sync preferences (auto-sync, sync on open, etc.)
     */
    fun updatePreferences(
        autoSync: Boolean? = null,
        syncOnOpen: Boolean? = null,
        syncOnPlaybackEnd: Boolean? = null,
        deviceName: String? = null
    ) {
        val current = CloudSyncPlugin.getCredentials()
        val updated = current.copy(
            autoSync = autoSync ?: current.autoSync,
            syncOnOpen = syncOnOpen ?: current.syncOnOpen,
            syncOnPlaybackEnd = syncOnPlaybackEnd ?: current.syncOnPlaybackEnd,
            deviceName = deviceName ?: current.deviceName
        )
        CloudSyncPlugin.saveCredentials(updated)
    }

    /**
     * Clear all sync data and reset credentials.
     */
    fun resetSync(context: Context) {
        val creds = CloudSyncPlugin.getCredentials()

        // Optionally delete the gist
        if (creds.isConfigured() && creds.hasGist()) {
            settingsScope.launch {
                try {
                    GitHubApiClient.deleteGist(creds.token, creds.gistId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete gist: ${e.message}")
                }
            }
        }

        // Clear credentials
        CloudSyncPlugin.saveCredentials(SyncCredentials())
        showToast("CloudSync reset complete")
    }

    /**
     * Export current credentials as a compact Base64 string for easy transfer to another device.
     * Format: Base64(JSON with token, gistId, deviceName)
     */
    fun exportConfigString(): String? {
        val creds = CloudSyncPlugin.getCredentials()
        if (!creds.isConfigured()) return null
        return try {
            val json = """{"token":"${creds.token}","gistId":"${creds.gistId}","deviceName":"${creds.deviceName}"}"""
            Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    /**
     * Import credentials from an exported Base64 string.
     * Validates the token before saving.
     */
    fun importConfigString(
        context: Context,
        encoded: String,
        onResult: (SyncResult) -> Unit
    ) {
        settingsScope.launch {
            try {
                val json = String(Base64.decode(encoded.trim(), Base64.NO_WRAP), Charsets.UTF_8)
                // Simple JSON field extraction (avoids extra dependencies)
                val configMap = try {
                    com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                        .readValue(json, Map::class.java) as Map<*, *>
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        onResult(SyncResult(false, "Invalid config string format"))
                    }
                    return@launch
                }
                val token = configMap["token"] as? String ?: ""
                val gistId = configMap["gistId"] as? String ?: ""
                val deviceName = configMap["deviceName"] as? String ?: "Device"

                if (token.isBlank()) {
                    withContext(Dispatchers.Main) {
                        onResult(SyncResult(false, "Invalid config string — token missing"))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { showToast("Validating token...") }

                val isValid = GitHubApiClient.validateToken(token)
                if (!isValid) {
                    withContext(Dispatchers.Main) {
                        onResult(SyncResult(false, "Token in config string is invalid or expired"))
                    }
                    return@launch
                }

                val deviceId = CloudSyncPlugin.getCredentials().deviceId.ifBlank {
                    GitHubSyncManager.generateDeviceId()
                }
                // Use a fresh device name for this device (keep original as fallback)
                val newCreds = SyncCredentials(
                    token = token,
                    gistId = gistId,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    autoSync = true,
                    syncOnOpen = true,
                    syncOnPlaybackEnd = true
                )
                CloudSyncPlugin.saveCredentials(newCreds)

                withContext(Dispatchers.Main) { showToast("Credentials imported! Syncing...") }

                val syncResult = GitHubSyncManager.fullSync(context, newCreds)
                CloudSyncPlugin.saveLastSyncResult(syncResult)

                withContext(Dispatchers.Main) { onResult(syncResult) }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(SyncResult(false, "Import failed: ${e.message}"))
                }
            }
        }
    }

    /**
     * Show custom settings layout dynamically in a dialog.
     */
    fun showSettingsDialog(context: Context) {
        val builder = AlertDialog.Builder(context)
        
        // Root scroll view for scrollability
        val scrollView = ScrollView(context)
        
        // Container layout
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(context, 16)
            setPadding(padding, padding, padding, padding)
        }
        
        // Title
        val titleView = TextView(context).apply {
            text = "☁️ CloudSync Settings"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(context, 16))
        }
        layout.addView(titleView)
        
        // Status Card
        val creds = CloudSyncPlugin.getCredentials()
        val statusView = TextView(context).apply {
            val lastSync = CloudSyncPlugin.getLastSyncTime()
            val lastMsg = CloudSyncPlugin.getLastSyncMessage()
            val syncText = if (lastSync > 0) {
                val diff = System.currentTimeMillis() - lastSync
                val timeAgo = when {
                    diff < 60_000 -> "Just now"
                    diff < 3_600_000 -> "${diff / 60_000}m ago"
                    diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                    else -> "${diff / 86_400_000}d ago"
                }
                "Last Sync: $timeAgo\nResult: $lastMsg"
            } else {
                "Last Sync: Never\nStatus: Unconfigured"
            }
            text = syncText
            textSize = 14f
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            setTextColor(Color.LTGRAY)
        }
        layout.addView(statusView)
        layout.addView(createSpacer(context, 12))
        
        // Token Input
        layout.addView(createLabel(context, "GitHub Token (classic, 'gist' scope):"))
        val tokenInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(creds.token)
            hint = "ghp_xxxxxxxxxxxxxxxxxxxxxx"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        layout.addView(tokenInput)
        layout.addView(createSpacer(context, 8))
        
        // Device Name Input
        layout.addView(createLabel(context, "Device Name (identifies sync source):"))
        val deviceInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(creds.deviceName.ifBlank { android.os.Build.MODEL })
            hint = "My Phone"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        layout.addView(deviceInput)
        layout.addView(createSpacer(context, 12))
        
        // Switches
        val autoSyncSwitch = createSwitch(context, "Real-time Auto-Sync", creds.autoSync)
        layout.addView(autoSyncSwitch)
        
        val syncOnOpenSwitch = createSwitch(context, "Sync on App Open", creds.syncOnOpen)
        layout.addView(syncOnOpenSwitch)
        
        val syncOnPlaybackEndSwitch = createSwitch(context, "Sync on Playback Pause/End", creds.syncOnPlaybackEnd)
        layout.addView(syncOnPlaybackEndSwitch)
        
        layout.addView(createSpacer(context, 16))
        
        // Buttons
        val actionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        
        val saveBtn = Button(context).apply {
            text = "Setup & Sync"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(context, 4)
            }
        }
        
        val syncBtn = Button(context).apply {
            text = "Sync Now"
            isEnabled = creds.isConfigured()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(context, 4)
            }
        }
        
        actionLayout.addView(saveBtn)
        actionLayout.addView(syncBtn)
        layout.addView(actionLayout)
        layout.addView(createSpacer(context, 8))
        
        // Push / Pull Buttons
        val forceLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        
        val pushBtn = Button(context).apply {
            text = "Force Backup"
            isEnabled = creds.isConfigured()
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(context, 4)
            }
        }
        
        val pullBtn = Button(context).apply {
            text = "Force Restore"
            isEnabled = creds.isConfigured()
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(context, 4)
            }
        }
        
        forceLayout.addView(pushBtn)
        forceLayout.addView(pullBtn)
        layout.addView(forceLayout)
        layout.addView(createSpacer(context, 12))

        // ── Transfer Setup Section ────────────────────────────────────────────
        val transferLabel = createLabel(context, "📲 Transfer setup to another device:")
        layout.addView(transferLabel)
        layout.addView(createSpacer(context, 4))

        val transferLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val exportBtn = Button(context).apply {
            text = "Export Config"
            isEnabled = creds.isConfigured()
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(context, 4)
            }
        }

        val importBtn = Button(context).apply {
            text = "Import Config"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(context, 4)
            }
        }

        transferLayout.addView(exportBtn)
        transferLayout.addView(importBtn)
        layout.addView(transferLayout)
        layout.addView(createSpacer(context, 12))

        // Reset Button
        val resetBtn = Button(context).apply {
            text = "Reset Extension Credentials"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 12f
        }
        layout.addView(resetBtn)
        
        scrollView.addView(layout)
        builder.setView(scrollView)
        
        val dialog = builder.create()
        dialog.show()
        
        // Button Listeners
        saveBtn.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            val device = deviceInput.text.toString().trim()
            if (token.isEmpty()) {
                showToast("Please enter a token")
                return@setOnClickListener
            }
            
            saveBtn.isEnabled = false
            saveBtn.text = "Setting up..."
            
            setupAndSync(context, token, device) { result ->
                Handler(Looper.getMainLooper()).post {
                    saveBtn.isEnabled = true
                    saveBtn.text = "Setup & Sync"
                    if (result.success) {
                        showToast("Setup successful: ${result.message}")
                        dialog.dismiss()
                    } else {
                        showToast("Setup failed: ${result.message}")
                    }
                }
            }
        }
        
        syncBtn.setOnClickListener {
            syncBtn.isEnabled = false
            syncBtn.text = "Syncing..."
            manualSync(context) { result ->
                Handler(Looper.getMainLooper()).post {
                    syncBtn.isEnabled = true
                    syncBtn.text = "Sync Now"
                    if (result.success) {
                        dialog.dismiss()
                    }
                }
            }
        }
        
        pushBtn.setOnClickListener {
            pushBtn.isEnabled = false
            pushBtn.text = "Backing up..."
            forcePush(context) { result ->
                Handler(Looper.getMainLooper()).post {
                    pushBtn.isEnabled = true
                    pushBtn.text = "Force Backup"
                    if (result.success) {
                        dialog.dismiss()
                    }
                }
            }
        }
        
        pullBtn.setOnClickListener {
            pullBtn.isEnabled = false
            pullBtn.text = "Restoring..."
            forcePull(context) { result ->
                Handler(Looper.getMainLooper()).post {
                    pullBtn.isEnabled = true
                    pullBtn.text = "Force Restore"
                    if (result.success) {
                        dialog.dismiss()
                    }
                }
            }
        }
        
        resetBtn.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Reset CloudSync")
                .setMessage("Are you sure you want to clear credentials and reset CloudSync?")
                .setPositiveButton("Yes") { _, _ ->
                    resetSync(context)
                    dialog.dismiss()
                }
                .setNegativeButton("No", null)
                .show()
        }

        exportBtn.setOnClickListener {
            val configStr = exportConfigString()
            if (configStr == null) {
                showToast("Nothing to export — not configured yet")
                return@setOnClickListener
            }
            // Show dialog with copyable config string
            val exportEditText = EditText(context).apply {
                setText(configStr)
                setTextColor(Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                isSingleLine = false
                isClickable = true
                isFocusable = true
                setSelectAllOnFocus(true)
                setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            }
            AlertDialog.Builder(context)
                .setTitle("📤 Export Config")
                .setMessage("Copy this string and paste it into \"Import Config\" on your other device:")
                .setView(exportEditText)
                .setPositiveButton("Copy to Clipboard") { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CloudSync Config", configStr))
                    showToast("✅ Config copied to clipboard!")
                }
                .setNegativeButton("Close", null)
                .show()
        }

        importBtn.setOnClickListener {
            val importEditText = EditText(context).apply {
                hint = "Paste exported config string here"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                isSingleLine = false
                setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            }
            AlertDialog.Builder(context)
                .setTitle("📥 Import Config")
                .setMessage("Paste the config string exported from your other device:")
                .setView(importEditText)
                .setPositiveButton("Import & Sync") { _, _ ->
                    val encoded = importEditText.text.toString().trim()
                    if (encoded.isBlank()) {
                        showToast("Please paste a config string")
                        return@setPositiveButton
                    }
                    importConfigString(context, encoded) { result ->
                        Handler(Looper.getMainLooper()).post {
                            if (result.success) {
                                showToast("✅ Imported! ${result.message}")
                                dialog.dismiss()
                            } else {
                                showToast("❌ ${result.message}")
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
    
    private fun createLabel(context: Context, textVal: String): TextView {
        return TextView(context).apply {
            text = textVal
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(context, 4), 0, 0)
        }
    }
    
    private fun createSpacer(context: Context, heightDp: Int): View {
        return View(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp))
        }
    }
    
    private fun createSwitch(context: Context, textVal: String, isCheckedVal: Boolean): Switch {
        return Switch(context).apply {
            text = textVal
            isChecked = isCheckedVal
            setTextColor(Color.WHITE)
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            setOnCheckedChangeListener { _, checked ->
                when (textVal) {
                    "Real-time Auto-Sync" -> updatePreferences(autoSync = checked)
                    "Sync on App Open" -> updatePreferences(syncOnOpen = checked)
                    "Sync on Playback Pause/End" -> updatePreferences(syncOnPlaybackEnd = checked)
                }
            }
        }
    }

    private fun showToast(message: String) {
        val ctx = CloudStreamApp.context ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}
