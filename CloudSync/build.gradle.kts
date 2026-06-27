@file:Suppress("UnstableApiUsage")

dependencies {
    implementation("com.google.android.material:material:1.14.0")

    val compileOnly by configurations
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

// use an integer for version numbers
version = 5

cloudstream {
    description = "Seamless cross-device sync for CloudStream. Syncs exact playback position (millisecond precision), watch history, continue watching, episode/season progress, and source preferences between all your devices via a private GitHub Gist. Just pause on one device and pick up right where you left off on another — no extra restarts needed."
    authors = listOf("CloudSyncDev")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "OVA", "Cartoon")

    language = "en"

    iconUrl = "https://raw.githubusercontent.com/DeepBlue9789/cloudsync/main/icon.png"
}

tasks.named("make") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        copy {
            from(File(buildDir, "CloudSync.cs3"))
            from(File(buildDir, "plugins.json"))
            into(project.rootDir)
        }
    }
}
