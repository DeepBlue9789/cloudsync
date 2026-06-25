package com.cloudsync

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

/**
 * CloudSync MainAPI — provides a home page showing sync status and recent sync activity.
 *
 * This is NOT a content provider; it's a dashboard that shows sync information
 * within the CloudStream home screen interface.
 */
class CloudSync(val plugin: CloudSyncPlugin) : MainAPI() {
    override var name = "CloudSync"
    override var supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var mainUrl = "https://github.com"

    override val mainPage = mainPageOf(
        "" to "☁️ Sync Status"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val creds = CloudSyncPlugin.getCredentials()
        val items = mutableListOf<SearchResponse>()

        if (!creds.isConfigured()) {
            // Show setup instruction
            items.add(
                newMovieSearchResponse(
                    "⚙️ Setup Required — Open Extension Settings",
                    "$mainUrl/setup",
                    TvType.Others
                ) {
                    this.posterUrl = null
                }
            )
            items.add(
                newMovieSearchResponse(
                    "1️⃣ Get a GitHub Personal Access Token",
                    "$mainUrl/settings/tokens",
                    TvType.Others
                )
            )
            items.add(
                newMovieSearchResponse(
                    "2️⃣ Enter your token in CloudSync settings",
                    "$mainUrl/settings",
                    TvType.Others
                )
            )
            items.add(
                newMovieSearchResponse(
                    "3️⃣ Token needs 'gist' scope permission",
                    "$mainUrl/scopes",
                    TvType.Others
                )
            )
        } else {
            // Show sync status
            val lastSync = CloudSyncPlugin.getLastSyncTime()
            val lastMessage = CloudSyncPlugin.getLastSyncMessage()
            val timeAgo = if (lastSync > 0) {
                formatTimeAgo(lastSync)
            } else "Never"

            items.add(
                newMovieSearchResponse(
                    "✅ Sync Active — Last: $timeAgo",
                    "$mainUrl/status",
                    TvType.Others
                )
            )

            items.add(
                newMovieSearchResponse(
                    "📊 $lastMessage",
                    "$mainUrl/result",
                    TvType.Others
                )
            )

            // Show device info
            items.add(
                newMovieSearchResponse(
                    "📱 Device: ${creds.deviceName} (${creds.deviceId})",
                    "$mainUrl/device",
                    TvType.Others
                )
            )

            // Show sync settings status
            val autoLabel = if (creds.autoSync) "ON" else "OFF"
            val openLabel = if (creds.syncOnOpen) "ON" else "OFF"
            val playLabel = if (creds.syncOnPlaybackEnd) "ON" else "OFF"

            items.add(
                newMovieSearchResponse(
                    "⚙️ Auto: $autoLabel | On Open: $openLabel | On Pause: $playLabel",
                    "$mainUrl/settings",
                    TvType.Others
                )
            )

            // Try to show data summary
            try {
                val ctx = plugin.activity
                if (ctx != null) {
                    val summary = LocalDataManager.getSyncSummary(ctx)
                    items.add(
                        newMovieSearchResponse(
                            "📦 Local: ${summary["playbackPositions"]} positions, ${summary["watchHistory"]} history, ${summary["resumeWatching"]} resume",
                            "$mainUrl/data",
                            TvType.Others
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("CloudSync", "Could not get data summary: ${e.message}")
            }
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return null // Not a content provider
    }

    override suspend fun load(url: String): LoadResponse? {
        val creds = CloudSyncPlugin.getCredentials()

        // Provide helpful info when items are clicked
        return newMovieLoadResponse(
            "CloudSync — Cross-Device Playback Sync",
            url,
            TvType.Others,
            url
        ) {
            this.plot = buildString {
                appendLine("☁️ CloudSync — Sync your playback history between devices via GitHub")
                appendLine()
                if (!creds.isConfigured()) {
                    appendLine("━━━ SETUP INSTRUCTIONS ━━━")
                    appendLine()
                    appendLine("1. Go to github.com/settings/tokens")
                    appendLine("2. Create a Personal Access Token (classic)")
                    appendLine("3. Select 'gist' scope")
                    appendLine("4. Copy the token")
                    appendLine("5. Open CloudSync extension settings in CloudStream")
                    appendLine("6. Paste your token")
                    appendLine("7. Set your device name")
                    appendLine("8. Tap 'Save & Sync'")
                    appendLine()
                    appendLine("Your data is stored in a private GitHub Gist.")
                } else {
                    appendLine("━━━ SYNC STATUS ━━━")
                    appendLine()
                    appendLine("Device: ${creds.deviceName}")
                    appendLine("Last sync: ${formatTimeAgo(CloudSyncPlugin.getLastSyncTime())}")
                    appendLine("Status: ${CloudSyncPlugin.getLastSyncMessage()}")
                    appendLine()
                    appendLine("━━━ WHAT GETS SYNCED ━━━")
                    appendLine()
                    appendLine("✅ Exact playback position (millisecond precision)")
                    appendLine("✅ Watch history & bookmarks")
                    appendLine("✅ Continue watching entries")
                    appendLine("✅ Episode & season selection")
                    appendLine()
                    appendLine("━━━ HOW IT WORKS ━━━")
                    appendLine()
                    appendLine("• Playback positions are synced in real-time")
                    appendLine("• Half-watched movies resume from exact timestamp")
                    appendLine("• Conflicts resolved by latest timestamp")
                    appendLine("• Data stored securely in your private GitHub Gist")
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false // Not a content provider
    }

    private fun formatTimeAgo(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }
}
