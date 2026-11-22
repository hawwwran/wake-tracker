package com.hawwwran.waketracker.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A published version of the app: a GitHub release with the APK attached. */
data class Release(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Of the APK, in hex, when GitHub lists one. */
    val sha256: String?,
    val notes: String,
)

object Releases {
    private const val TAG_PREFIX = "v"
    private const val DIGEST_PREFIX = "sha256:"
    private const val RULE = "---"

    /**
     * The highest version among the releases in GitHub's answer to "list releases". Drafts,
     * pre-releases, tags that are not `vX.Y.Z` and releases without an APK are passed over.
     * Null when there is none, or the answer is not what GitHub sends.
     */
    fun newest(body: String): Release? {
        val releases = try {
            JSONArray(body)
        } catch (_: JSONException) {
            return null
        }
        var newest: Release? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index)?.let(::parse) ?: continue
            if (newest == null || isNewer(release.version, newest.version)) newest = release
        }
        return newest
    }

    private fun parse(release: JSONObject): Release? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) return null
        val version = tag.removePrefix(TAG_PREFIX)
        if (numbers(version) == null) return null

        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val url = asset.optString("browser_download_url")
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true) || url.isEmpty()) continue
            val digest = asset.optString("digest")
            return Release(
                version = version,
                apkUrl = url,
                apkSize = asset.optLong("size"),
                sha256 = digest.takeIf { it.startsWith(DIGEST_PREFIX) }?.removePrefix(DIGEST_PREFIX)?.lowercase(),
                // optString turns JSON null into the word "null".
                notes = if (release.isNull("body")) "" else changes(release.optString("body")),
            )
        }
        return null
    }

    /**
     * What changed, out of a release's text: the part above the first horizontal rule. Below
     * it the release script puts what only a reader on GitHub needs.
     */
    private fun changes(body: String): String =
        body.lines().takeWhile { it.trim() != RULE }.joinToString("\n").trim()

    /** Compares `1.2.10` style versions number by number. Anything else is never newer. */
    fun isNewer(offered: String, installed: String): Boolean {
        val a = numbers(offered) ?: return false
        val b = numbers(installed) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() && it.all { number -> number >= 0 } }
    }
}
