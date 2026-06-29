package com.cloudsync

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
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

    // ── Design Tokens ──────────────────────────────────────────────────────────
    private val CLR_BG          = Color.parseColor("#0F1117")   // Near-black background
    private val CLR_SURFACE     = Color.parseColor("#1A1D27")   // Card surface
    private val CLR_SURFACE2    = Color.parseColor("#22263A")   // Elevated surface
    private val CLR_ACCENT      = Color.parseColor("#6C63FF")   // Purple accent
    private val CLR_ACCENT2     = Color.parseColor("#9D8FFF")   // Lighter purple
    private val CLR_SUCCESS     = Color.parseColor("#4CAF50")
    private val CLR_DANGER      = Color.parseColor("#FF5252")
    private val CLR_TEXT        = Color.parseColor("#F0F0F8")   // Near-white
    private val CLR_TEXT_SEC    = Color.parseColor("#9B9BB4")   // Muted
    private val CLR_TEXT_HINT   = Color.parseColor("#55556A")   // Hint
    private val CLR_DIVIDER     = Color.parseColor("#2A2D40")

    /**
     * Perform initial setup: validate credentials, find/create remote record, and do first sync.
     */
    fun setupAndSync(
        context: Context,
        creds: SyncCredentials,
        onResult: (SyncResult) -> Unit
    ) {
        settingsScope.launch {
            try {
                if (creds.syncMethod == "pocketbase") {
                    withContext(Dispatchers.Main) { showToast("Validating PocketBase credentials...") }

                    val token = PocketBaseApiClient.authenticate(creds)
                    if (token == null) {
                        val result = SyncResult(false, "Invalid PocketBase credentials.")
                        withContext(Dispatchers.Main) { onResult(result) }
                        return@launch
                    }

                    withContext(Dispatchers.Main) { showToast("Finding sync record...") }
                    var recordId = PocketBaseApiClient.findExistingRecord(creds, token)
                    if (recordId == null) {
                        withContext(Dispatchers.Main) { showToast("Creating new sync record...") }
                        val deviceId = CloudSyncPlugin.getCredentials().deviceId.ifBlank {
                            GitHubSyncManager.generateDeviceId()
                        }
                        val initialPayload = SyncPayload(
                            version = 1,
                            lastSync = System.currentTimeMillis(),
                            deviceId = deviceId,
                            deviceName = creds.deviceName
                        )
                        recordId = PocketBaseApiClient.createRecord(creds, token, initialPayload)
                    }

                    if (recordId == null) {
                        val result = SyncResult(false, "Failed to create/find sync record.")
                        withContext(Dispatchers.Main) { onResult(result) }
                        return@launch
                    }

                    val newCreds = creds.copy(
                        pbRecordId = recordId,
                        deviceId = CloudSyncPlugin.getCredentials().deviceId.ifBlank { GitHubSyncManager.generateDeviceId() },
                        autoSync = true,
                        syncOnOpen = true,
                        syncOnPlaybackEnd = true
                    )
                    CloudSyncPlugin.saveCredentials(newCreds)

                    withContext(Dispatchers.Main) { showToast("Performing first sync...") }
                    val syncResult = PocketBaseSyncManager.fullSync(context, newCreds)
                    CloudSyncPlugin.saveLastSyncResult(syncResult)
                    withContext(Dispatchers.Main) { onResult(syncResult) }

                } else {
                    withContext(Dispatchers.Main) { showToast("Validating GitHub token...") }
                    val isValid = GitHubApiClient.validateToken(creds.token)
                    if (!isValid) {
                        val result = SyncResult(false, "Invalid GitHub token. Make sure it has 'gist' scope.")
                        withContext(Dispatchers.Main) { onResult(result) }
                        return@launch
                    }

                    withContext(Dispatchers.Main) { showToast("Finding sync gist...") }
                    var gistId = GitHubApiClient.findExistingGist(creds.token)
                    if (gistId == null) {
                        withContext(Dispatchers.Main) { showToast("Creating new sync gist...") }
                        val deviceId = GitHubSyncManager.generateDeviceId()
                        val initialPayload = SyncPayload(
                            version = 1,
                            lastSync = System.currentTimeMillis(),
                            deviceId = deviceId,
                            deviceName = creds.deviceName
                        )
                        gistId = GitHubApiClient.createGist(creds.token, initialPayload)
                    }

                    if (gistId == null) {
                        val result = SyncResult(false, "Failed to create/find sync gist.")
                        withContext(Dispatchers.Main) { onResult(result) }
                        return@launch
                    }

                    val newCreds = creds.copy(
                        gistId = gistId,
                        deviceId = CloudSyncPlugin.getCredentials().deviceId.ifBlank { GitHubSyncManager.generateDeviceId() },
                        autoSync = true,
                        syncOnOpen = true,
                        syncOnPlaybackEnd = true
                    )
                    CloudSyncPlugin.saveCredentials(newCreds)

                    withContext(Dispatchers.Main) { showToast("Performing first sync...") }
                    val syncResult = GitHubSyncManager.fullSync(context, newCreds)
                    CloudSyncPlugin.saveLastSyncResult(syncResult)
                    withContext(Dispatchers.Main) { onResult(syncResult) }
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
                    val result = SyncResult(false, "Not configured. Set up credentials first.")
                    withContext(Dispatchers.Main) { onResult(result) }
                    return@launch
                }

                withContext(Dispatchers.Main) { showToast("☁️ Syncing...") }

                val result = if (creds.syncMethod == "pocketbase") {
                    PocketBaseSyncManager.fullSync(context, creds)
                } else {
                    GitHubSyncManager.fullSync(context, creds)
                }

                CloudSyncPlugin.saveLastSyncResult(result)

                withContext(Dispatchers.Main) {
                    onResult(result)
                    if (result.success) {
                        if (result.itemsPulled > 0) CloudSyncPlugin.triggerUIRefresh()
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
                    withContext(Dispatchers.Main) { onResult(SyncResult(false, "Not configured")) }
                    return@launch
                }
                withContext(Dispatchers.Main) { showToast("📥 Pulling from cloud...") }
                val result = if (creds.syncMethod == "pocketbase") {
                    PocketBaseSyncManager.pullOnly(context, creds)
                } else {
                    GitHubSyncManager.pullOnly(context, creds)
                }
                CloudSyncPlugin.saveLastSyncResult(result)
                withContext(Dispatchers.Main) {
                    onResult(result)
                    if (result.success && result.itemsPulled > 0) CloudSyncPlugin.triggerUIRefresh()
                    showToast(if (result.success) "📥 ${result.message}" else "❌ ${result.message}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(SyncResult(false, "Pull failed: ${e.message}")) }
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
                    withContext(Dispatchers.Main) { onResult(SyncResult(false, "Not configured")) }
                    return@launch
                }
                withContext(Dispatchers.Main) { showToast("📤 Pushing to cloud...") }
                val result = if (creds.syncMethod == "pocketbase") {
                    PocketBaseSyncManager.pushOnly(context, creds)
                } else {
                    GitHubSyncManager.pushOnly(context, creds)
                }
                CloudSyncPlugin.saveLastSyncResult(result)
                withContext(Dispatchers.Main) {
                    onResult(result)
                    showToast(if (result.success) "📤 ${result.message}" else "❌ ${result.message}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(SyncResult(false, "Push failed: ${e.message}")) }
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
        showSyncToasts: Boolean? = null,
        deviceName: String? = null
    ) {
        val current = CloudSyncPlugin.getCredentials()
        val updated = current.copy(
            autoSync = autoSync ?: current.autoSync,
            syncOnOpen = syncOnOpen ?: current.syncOnOpen,
            syncOnPlaybackEnd = syncOnPlaybackEnd ?: current.syncOnPlaybackEnd,
            showSyncToasts = showSyncToasts ?: current.showSyncToasts,
            deviceName = deviceName ?: current.deviceName
        )
        CloudSyncPlugin.saveCredentials(updated)
    }

    /**
     * Clear all sync data and reset credentials.
     */
    fun resetSync(context: Context) {
        val creds = CloudSyncPlugin.getCredentials()
        if (creds.isConfigured() && creds.hasGist()) {
            settingsScope.launch {
                try { GitHubApiClient.deleteGist(creds.token, creds.gistId) }
                catch (e: Exception) { Log.w(TAG, "Failed to delete gist: ${e.message}") }
            }
        }
        CloudSyncPlugin.saveCredentials(SyncCredentials())
        showToast("CloudSync reset complete")
    }

    /**
     * Export current credentials as a compact Base64 string for easy transfer to another device.
     */
    fun exportConfigString(): String? {
        val creds = CloudSyncPlugin.getCredentials()
        if (!creds.isConfigured()) return null
        return try {
            val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(creds)
            Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    /**
     * Import credentials from an exported Base64 string.
     */
    fun importConfigString(
        context: Context,
        encoded: String,
        onResult: (SyncResult) -> Unit
    ) {
        settingsScope.launch {
            try {
                val json = String(Base64.decode(encoded.trim(), Base64.NO_WRAP), Charsets.UTF_8)
                val newCreds = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                    .readValue(json, SyncCredentials::class.java)
                if (!newCreds.isConfigured()) {
                    withContext(Dispatchers.Main) { onResult(SyncResult(false, "Invalid config string")) }
                    return@launch
                }
                val finalCreds = newCreds.copy(
                    deviceId = CloudSyncPlugin.getCredentials().deviceId.ifBlank { GitHubSyncManager.generateDeviceId() }
                )
                CloudSyncPlugin.saveCredentials(finalCreds)
                withContext(Dispatchers.Main) { showToast("Credentials imported! Syncing...") }
                val syncResult = if (finalCreds.syncMethod == "pocketbase") {
                    PocketBaseSyncManager.fullSync(context, finalCreds)
                } else {
                    GitHubSyncManager.fullSync(context, finalCreds)
                }
                CloudSyncPlugin.saveLastSyncResult(syncResult)
                withContext(Dispatchers.Main) {
                    if (syncResult.success && syncResult.itemsPulled > 0) CloudSyncPlugin.triggerUIRefresh()
                    onResult(syncResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed: ${e.message}")
                withContext(Dispatchers.Main) { onResult(SyncResult(false, "Import failed: ${e.message}")) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODERN UI — Settings Dialog
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Show the modern settings dialog.
     */
    fun showSettingsDialog(context: Context) {
        val creds = CloudSyncPlugin.getCredentials()

        val scrollView = ScrollView(context).apply {
            background = ColorDrawable(CLR_BG)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 16)
            setPadding(p, dp(context, 8), p, dp(context, 24))
            background = ColorDrawable(CLR_BG)
        }

        // ── Header ────────────────────────────────────────────────────────────
        root.addView(buildHeader(context))
        root.addView(spacer(context, 16))

        // ── Status Card ───────────────────────────────────────────────────────
        root.addView(buildStatusCard(context, creds))
        root.addView(spacer(context, 20))

        // ── Provider Section ──────────────────────────────────────────────────
        root.addView(sectionLabel(context, "SYNC PROVIDER"))
        root.addView(spacer(context, 8))

        // Radio pill group
        val rbGithub = buildRadioPill(context, "GitHub Gist", creds.syncMethod != "pocketbase")
        val rbPocketbase = buildRadioPill(context, "PocketBase", creds.syncMethod == "pocketbase")
        val providerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rbGithub, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(context, 6) })
            addView(rbPocketbase, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(providerRow)
        root.addView(spacer(context, 16))

        // ── Credentials Card ──────────────────────────────────────────────────
        root.addView(sectionLabel(context, "CREDENTIALS"))
        root.addView(spacer(context, 8))

        val credsCard = buildCard(context)

        // Device Name
        credsCard.addView(fieldLabel(context, "Device Name"))
        val deviceInput = buildInput(context, creds.deviceName.ifBlank { android.os.Build.MODEL }, "My Phone")
        credsCard.addView(deviceInput)
        credsCard.addView(spacer(context, 12))

        // GitHub fields
        val ghContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ghContainer.addView(fieldLabel(context, "GitHub Token"))
        val tokenInput = buildInput(
            context, creds.token, "ghp_xxxxxxxxxxxxxxxx",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        ghContainer.addView(tokenInput)
        credsCard.addView(ghContainer)

        // PocketBase fields
        val pbContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pbContainer.addView(fieldLabel(context, "PocketBase URL"))
        val pbUrlInput = buildInput(context, creds.pbUrl, "https://pb.example.com",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pbContainer.addView(pbUrlInput)
        pbContainer.addView(spacer(context, 8))
        pbContainer.addView(fieldLabel(context, "Email"))
        val pbEmailInput = buildInput(context, creds.pbEmail, "user@example.com",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        pbContainer.addView(pbEmailInput)
        pbContainer.addView(spacer(context, 8))
        pbContainer.addView(fieldLabel(context, "Password"))
        val pbPasswordInput = buildInput(context, creds.pbPassword, "••••••••",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pbContainer.addView(pbPasswordInput)
        credsCard.addView(pbContainer)

        root.addView(credsCard)
        root.addView(spacer(context, 20))

        // Provider visibility toggle
        fun syncProviderVisibility() {
            val isPb = rbPocketbase.isChecked
            ghContainer.visibility = if (!isPb) View.VISIBLE else View.GONE
            pbContainer.visibility = if (isPb) View.VISIBLE else View.GONE
            // Update pill appearance
            updatePillStyle(context, rbGithub, !isPb)
            updatePillStyle(context, rbPocketbase, isPb)
        }
        syncProviderVisibility()
        rbGithub.setOnClickListener { rbPocketbase.isChecked = false; rbGithub.isChecked = true; syncProviderVisibility() }
        rbPocketbase.setOnClickListener { rbGithub.isChecked = false; rbPocketbase.isChecked = true; syncProviderVisibility() }

        // ── Sync Behaviour Section ────────────────────────────────────────────
        root.addView(sectionLabel(context, "SYNC BEHAVIOUR"))
        root.addView(spacer(context, 8))

        val behavCard = buildCard(context)
        val autoSyncSwitch = buildModernSwitch(context, "Real-time Auto-Sync",
            "Syncs every 20 s during playback", creds.autoSync)
        val syncOnOpenSwitch = buildModernSwitch(context, "Sync on App Open",
            "Pull latest data when the app starts", creds.syncOnOpen)
        val syncOnPauseSwitch = buildModernSwitch(context, "Sync on Pause / Close",
            "Push progress when you pause or exit", creds.syncOnPlaybackEnd)
        val toastsSwitch = buildModernSwitch(context, "Show Sync Toasts",
            "Brief on-screen notifications for sync events", creds.showSyncToasts)

        behavCard.addView(autoSyncSwitch)
        behavCard.addView(divider(context))
        behavCard.addView(syncOnOpenSwitch)
        behavCard.addView(divider(context))
        behavCard.addView(syncOnPauseSwitch)
        behavCard.addView(divider(context))
        behavCard.addView(toastsSwitch)
        root.addView(behavCard)
        root.addView(spacer(context, 20))

        // ── Primary Actions ───────────────────────────────────────────────────
        root.addView(sectionLabel(context, "ACTIONS"))
        root.addView(spacer(context, 8))

        val saveBtn = buildPrimaryButton(context, "⚡  Setup & Sync")
        val syncBtn = buildSecondaryButton(context, "☁  Sync Now").apply {
            isEnabled = creds.isConfigured()
            alpha = if (creds.isConfigured()) 1f else 0.45f
        }

        val primaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(saveBtn, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply { rightMargin = dp(context, 6) })
            addView(syncBtn, LinearLayout.LayoutParams(0, dp(context, 48), 1f))
        }
        root.addView(primaryRow)
        root.addView(spacer(context, 8))

        // Force push / pull
        val pushBtn = buildOutlineButton(context, "📤  Backup").apply {
            isEnabled = creds.isConfigured()
            alpha = if (creds.isConfigured()) 1f else 0.45f
        }
        val pullBtn = buildOutlineButton(context, "📥  Restore").apply {
            isEnabled = creds.isConfigured()
            alpha = if (creds.isConfigured()) 1f else 0.45f
        }
        val forceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pushBtn, LinearLayout.LayoutParams(0, dp(context, 44), 1f).apply { rightMargin = dp(context, 6) })
            addView(pullBtn, LinearLayout.LayoutParams(0, dp(context, 44), 1f))
        }
        root.addView(forceRow)
        root.addView(spacer(context, 20))

        // ── Transfer Section ──────────────────────────────────────────────────
        root.addView(sectionLabel(context, "TRANSFER TO ANOTHER DEVICE"))
        root.addView(spacer(context, 8))

        val exportBtn = buildOutlineButton(context, "📤  Export Config").apply {
            isEnabled = creds.isConfigured()
            alpha = if (creds.isConfigured()) 1f else 0.45f
        }
        val importBtn = buildOutlineButton(context, "📥  Import Config")
        val transferRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(exportBtn, LinearLayout.LayoutParams(0, dp(context, 44), 1f).apply { rightMargin = dp(context, 6) })
            addView(importBtn, LinearLayout.LayoutParams(0, dp(context, 44), 1f))
        }
        root.addView(transferRow)
        root.addView(spacer(context, 20))

        // ── Danger Zone ───────────────────────────────────────────────────────
        root.addView(sectionLabel(context, "DANGER ZONE"))
        root.addView(spacer(context, 8))
        val resetBtn = buildDangerButton(context, "🗑  Reset & Clear Credentials")
        root.addView(resetBtn)

        scrollView.addView(root)

        // ── Build dialog ──────────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(context)
            .setView(scrollView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(CLR_BG))
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // ── Wire up listeners ─────────────────────────────────────────────────
        autoSyncSwitch.tag?.let { sw ->
            (sw as? Switch)?.setOnCheckedChangeListener { _, checked -> updatePreferences(autoSync = checked) }
        }

        fun getSwitch(row: View) = (row as? LinearLayout)?.getChildAt(1) as? Switch

        getSwitch(autoSyncSwitch)?.setOnCheckedChangeListener { _, c -> updatePreferences(autoSync = c) }
        getSwitch(syncOnOpenSwitch)?.setOnCheckedChangeListener { _, c -> updatePreferences(syncOnOpen = c) }
        getSwitch(syncOnPauseSwitch)?.setOnCheckedChangeListener { _, c -> updatePreferences(syncOnPlaybackEnd = c) }
        getSwitch(toastsSwitch)?.setOnCheckedChangeListener { _, c -> updatePreferences(showSyncToasts = c) }

        saveBtn.setOnClickListener {
            val device = deviceInput.text.toString().trim()
            val syncMethod = if (rbPocketbase.isChecked) "pocketbase" else "github"
            val newCreds = creds.copy(
                deviceName = device,
                syncMethod = syncMethod,
                token = tokenInput.text.toString().trim(),
                pbUrl = pbUrlInput.text.toString().trim(),
                pbEmail = pbEmailInput.text.toString().trim(),
                pbPassword = pbPasswordInput.text.toString().trim()
            )
            if (syncMethod == "pocketbase" && (newCreds.pbUrl.isEmpty() || newCreds.pbEmail.isEmpty() || newCreds.pbPassword.isEmpty())) {
                showToast("Please enter PocketBase URL, Email, and Password"); return@setOnClickListener
            }
            if (syncMethod == "github" && newCreds.token.isEmpty()) {
                showToast("Please enter a GitHub token"); return@setOnClickListener
            }
            saveBtn.isEnabled = false
            saveBtn.text = "Setting up…"
            setupAndSync(context, newCreds) { result ->
                Handler(Looper.getMainLooper()).post {
                    saveBtn.isEnabled = true
                    saveBtn.text = "⚡  Setup & Sync"
                    if (result.success) { showToast("✅ Setup successful!"); dialog.dismiss() }
                    else showToast("❌ ${result.message}")
                }
            }
        }

        syncBtn.setOnClickListener {
            syncBtn.isEnabled = false; syncBtn.text = "Syncing…"
            manualSync(context) { result ->
                Handler(Looper.getMainLooper()).post {
                    syncBtn.isEnabled = true; syncBtn.text = "☁  Sync Now"
                    if (result.success) dialog.dismiss()
                }
            }
        }

        pushBtn.setOnClickListener {
            pushBtn.isEnabled = false; pushBtn.text = "Backing up…"
            forcePush(context) { result ->
                Handler(Looper.getMainLooper()).post {
                    pushBtn.isEnabled = true; pushBtn.text = "📤  Backup"
                    if (result.success) dialog.dismiss()
                }
            }
        }

        pullBtn.setOnClickListener {
            pullBtn.isEnabled = false; pullBtn.text = "Restoring…"
            forcePull(context) { result ->
                Handler(Looper.getMainLooper()).post {
                    pullBtn.isEnabled = true; pullBtn.text = "📥  Restore"
                    if (result.success) dialog.dismiss()
                }
            }
        }

        exportBtn.setOnClickListener {
            val configStr = exportConfigString()
            if (configStr == null) { showToast("Nothing to export — not configured yet"); return@setOnClickListener }
            val exportEditText = EditText(context).apply {
                setText(configStr)
                setTextColor(CLR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isSingleLine = false; isClickable = true; isFocusable = true; setSelectAllOnFocus(true)
                setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                background = roundedBackground(CLR_SURFACE2, 12f)
                setHintTextColor(CLR_TEXT_HINT)
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
                setTextColor(CLR_TEXT); setHintTextColor(CLR_TEXT_HINT)
                isSingleLine = false
                setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                background = roundedBackground(CLR_SURFACE2, 12f)
            }
            AlertDialog.Builder(context)
                .setTitle("📥 Import Config")
                .setMessage("Paste the config string exported from your other device:")
                .setView(importEditText)
                .setPositiveButton("Import & Sync") { _, _ ->
                    val encoded = importEditText.text.toString().trim()
                    if (encoded.isBlank()) { showToast("Please paste a config string"); return@setPositiveButton }
                    importConfigString(context, encoded) { result ->
                        Handler(Looper.getMainLooper()).post {
                            if (result.success) { showToast("✅ Imported! ${result.message}"); dialog.dismiss() }
                            else showToast("❌ ${result.message}")
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        resetBtn.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Reset CloudSync")
                .setMessage("Are you sure you want to clear credentials and reset CloudSync?")
                .setPositiveButton("Reset") { _, _ -> resetSync(context); dialog.dismiss() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPONENT BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Gradient header with cloud icon and version */
    private fun buildHeader(context: Context): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#2A1F5E"), Color.parseColor("#16132E"))
            ).apply { cornerRadius = dp(context, 16).toFloat() }
            background = bg
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
        }

        val icon = TextView(context).apply {
            text = "☁️"
            textSize = 36f
            gravity = Gravity.CENTER
        }
        val title = TextView(context).apply {
            text = "CloudSync"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CLR_TEXT)
            gravity = Gravity.CENTER
        }
        val sub = TextView(context).apply {
            text = "Cross-device sync for CloudStream  •  v8"
            textSize = 12f
            setTextColor(CLR_TEXT_SEC)
            gravity = Gravity.CENTER
        }
        card.addView(icon)
        card.addView(spacer(context, 4))
        card.addView(title)
        card.addView(spacer(context, 2))
        card.addView(sub)
        return card
    }

    /** Status summary card */
    private fun buildStatusCard(context: Context, creds: SyncCredentials): View {
        val card = buildCard(context)

        val lastSync = CloudSyncPlugin.getLastSyncTime()
        val lastMsg  = CloudSyncPlugin.getLastSyncMessage()

        val isConfigured = creds.isConfigured()
        val dotColor = if (isConfigured) CLR_SUCCESS else CLR_TEXT_HINT
        val statusText = if (isConfigured) "Connected" else "Not Configured"

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)).apply {
                rightMargin = dp(context, 8)
            }
        }
        val statusLbl = TextView(context).apply {
            text = statusText
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isConfigured) CLR_SUCCESS else CLR_TEXT_SEC)
        }
        val methodBadge = TextView(context).apply {
            if (isConfigured) {
                text = " ${creds.syncMethod.uppercase()} "
                textSize = 10f
                setTextColor(CLR_ACCENT2)
                background = roundedBackground(Color.parseColor("#1A6C63FF"), 20f)
                setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(context, 8) }
            }
        }
        row.addView(dot)
        row.addView(statusLbl)
        if (isConfigured) row.addView(methodBadge)
        card.addView(row)
        card.addView(spacer(context, 8))

        val timeStr = if (lastSync > 0) {
            val diff = System.currentTimeMillis() - lastSync
            when {
                diff < 60_000     -> "Just now"
                diff < 3_600_000  -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                else              -> "${diff / 86_400_000}d ago"
            }
        } else "Never"

        val detailText = if (lastSync > 0) "Last sync: $timeStr  ·  $lastMsg"
        else "Open settings to configure your sync provider"

        val detail = TextView(context).apply {
            text = detailText
            textSize = 12f
            setTextColor(CLR_TEXT_SEC)
        }
        card.addView(detail)

        if (isConfigured && creds.deviceName.isNotBlank()) {
            card.addView(spacer(context, 4))
            val device = TextView(context).apply {
                text = "📱 ${creds.deviceName}"
                textSize = 11f
                setTextColor(CLR_TEXT_HINT)
            }
            card.addView(device)
        }
        return card
    }

    /** Section header label */
    private fun sectionLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CLR_ACCENT2)
            letterSpacing = 0.12f
        }
    }

    /** Field label above an input */
    private fun fieldLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(CLR_TEXT_SEC)
            setPadding(0, 0, 0, dp(context, 4))
        }
    }

    /** A rounded surface card */
    private fun buildCard(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 14)
            setPadding(p, p, p, p)
            background = roundedBackground(CLR_SURFACE, 16f)
        }
    }

    /** Styled EditText */
    private fun buildInput(
        context: Context,
        value: String,
        hintVal: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        return EditText(context).apply {
            this.inputType = inputType
            setText(value)
            hint = hintVal
            textSize = 14f
            setTextColor(CLR_TEXT)
            setHintTextColor(CLR_TEXT_HINT)
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = roundedBorderBackground(context, CLR_SURFACE2, CLR_DIVIDER, 10f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2); bottomMargin = dp(context, 2) }
        }
    }

    /** Radio-style pill button */
    private fun buildRadioPill(context: Context, label: String, selected: Boolean): RadioButton {
        return RadioButton(context).apply {
            text = label
            isChecked = selected
            textSize = 13f
            buttonDrawable = null // hide default radio circle
            gravity = Gravity.CENTER
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setTextColor(if (selected) CLR_TEXT else CLR_TEXT_SEC)
            background = if (selected)
                roundedBackground(CLR_ACCENT, 10f)
            else
                roundedBorderBackground(context, CLR_SURFACE, CLR_DIVIDER, 10f)
        }
    }

    /** Update pill button selection state */
    private fun updatePillStyle(context: Context, btn: RadioButton, selected: Boolean) {
        btn.setTextColor(if (selected) CLR_TEXT else CLR_TEXT_SEC)
        btn.background = if (selected)
            roundedBackground(CLR_ACCENT, 10f)
        else
            roundedBorderBackground(context, CLR_SURFACE, CLR_DIVIDER, 10f)
    }

    /** A switch row with title and subtitle */
    private fun buildModernSwitch(context: Context, title: String, subtitle: String, checked: Boolean): LinearLayout {
        val sw = Switch(context).apply {
            isChecked = checked
            // Tint thumb & track
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(CLR_ACCENT, CLR_TEXT_HINT)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#4D6C63FF"), CLR_SURFACE2)
            )
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val titleView = TextView(context).apply {
                text = title; textSize = 14f; setTextColor(CLR_TEXT)
            }
            val subtitleView = TextView(context).apply {
                text = subtitle; textSize = 11f; setTextColor(CLR_TEXT_SEC)
                setPadding(0, dp(context, 2), 0, 0)
            }
            addView(titleView); addView(subtitleView)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            tag = sw   // store ref for later listener attachment
            setOnClickListener { sw.isChecked = !sw.isChecked }
        }
    }

    /** Solid accent primary button */
    private fun buildPrimaryButton(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBackground(CLR_ACCENT, 12f)
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
        }
    }

    /** Surface secondary button */
    private fun buildSecondaryButton(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 13f
            setTextColor(CLR_ACCENT2)
            background = roundedBackground(CLR_SURFACE2, 12f)
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
        }
    }

    /** Outlined button */
    private fun buildOutlineButton(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 12f
            setTextColor(CLR_TEXT_SEC)
            background = roundedBorderBackground(context, CLR_SURFACE, CLR_DIVIDER, 10f)
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
        }
    }

    /** Danger / destructive button */
    private fun buildDangerButton(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 13f
            setTextColor(CLR_DANGER)
            background = roundedBorderBackground(context, CLR_SURFACE, Color.parseColor("#3DFF5252"), 12f)
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
        }
    }

    /** Thin horizontal divider */
    private fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(CLR_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp(context, 4); bottomMargin = dp(context, 4) }
        }
    }

    /** Transparent spacer */
    private fun spacer(context: Context, heightDp: Int): View {
        return View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)
            )
        }
    }

    // ── Drawing helpers ────────────────────────────────────────────────────────

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp
        }
    }

    private fun roundedBorderBackground(
        context: Context, fillColor: Int, strokeColor: Int, radiusDp: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radiusDp
            setStroke(dp(context, 1), strokeColor)
        }
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun showToast(message: String) {
        val ctx = CloudStreamApp.context ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}
