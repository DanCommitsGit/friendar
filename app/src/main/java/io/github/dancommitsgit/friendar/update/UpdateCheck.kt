package io.github.dancommitsgit.friendar.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LATEST_RELEASE_API = "https://api.github.com/repos/DanCommitsGit/friendar/releases/latest"
private const val TIMEOUT_MS = 10_000

class Release(val version: String, val url: String)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: Release) : UpdateStatus
    data object Failed : UpdateStatus
}

/** Compares dotted numeric versions such as `1.2.3`; a leading `v` is ignored. */
fun isNewer(latest: String, current: String): Boolean {
    val a = versionParts(latest) ?: return false
    val b = versionParts(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionParts(version: String): List<Int>? =
    version.removePrefix("v").split(".").map { it.toIntOrNull() ?: return null }

/** Reads the newest published GitHub release; returns null when it can't be reached or read. */
suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            Release(json.getString("tag_name").removePrefix("v"), json.getString("html_url"))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

suspend fun checkForUpdate(currentVersion: String): UpdateStatus {
    val release = fetchLatestRelease() ?: return UpdateStatus.Failed
    return if (isNewer(release.version, currentVersion)) UpdateStatus.Available(release) else UpdateStatus.UpToDate
}
