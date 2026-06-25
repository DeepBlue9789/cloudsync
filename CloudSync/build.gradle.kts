@file:Suppress("UnstableApiUsage")

dependencies {
    implementation("com.google.android.material:material:1.14.0")

    val compileOnly by configurations
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

// use an integer for version numbers
version = 2

cloudstream {
    description = "Cross-device playback sync via GitHub — syncs exact playback position, watch history, and resume state so half-watched movies and episodes pick up right where you left off on any device."
    authors = listOf("CloudSyncDev")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3

    tvTypes = listOf("Movie", "TvSeries", "Anime", "OVA", "Cartoon")

    language = "en"

    iconUrl = ""
}
