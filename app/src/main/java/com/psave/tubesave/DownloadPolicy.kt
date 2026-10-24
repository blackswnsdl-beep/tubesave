package com.psave.tubesave

/** Network-independent download compatibility and update policy. */
internal object DownloadPolicy {
    const val DEFAULT_EXTRACTOR_ARGS = "youtube:player_client=default"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 60 * 60 * 1000L

    // Old installed preferences and the old remote config both pin the failing client.
    fun extractorArgs(saved: String?): String = when (saved?.trim()) {
        null, "", "youtube:player_client=android_vr" -> DEFAULT_EXTRACTOR_ARGS
        else -> saved.trim()
    }

    fun shouldUpdate(lastSuccess: Long, lastAttempt: Long, failures: Int, now: Long): Boolean {
        val retryReady = lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= RETRY_MS
        val stale = lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= DAY_MS
        return retryReady && (stale || failures > 0)
    }
}
