package com.hawwwran.waketracker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    private fun release(tag: String, apk: String? = "wake-tracker.apk", draft: Boolean = false, prerelease: Boolean = false, body: String? = "- A change") = """
        {
          "tag_name": "$tag", "draft": $draft, "prerelease": $prerelease,
          "body": ${if (body == null) "null" else "\"$body\""},
          "assets": [
            {"name": "notes.txt", "browser_download_url": "https://example.test/$tag/notes.txt", "size": 12},
            ${if (apk == null) "" else """{"name": "$apk", "browser_download_url": "https://example.test/$tag/$apk", "size": 4096,
              "digest": "sha256:ABCDEF0123456789"}"""}
          ]
        }
    """.trimIndent().replace(",\n            \n", "\n")

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun `the release with the highest version wins, whatever the order`() {
        val newest = Releases.newest(list(release("android/v0.9.0"), release("android/v0.10.0"), release("android/v0.2.5")))!!
        assertEquals("0.10.0", newest.version)
        assertEquals("https://example.test/android/v0.10.0/wake-tracker.apk", newest.apkUrl)
        assertEquals(4096, newest.apkSize)
        assertEquals("abcdef0123456789", newest.sha256)
        assertEquals("- A change", newest.notes)
    }

    @Test
    fun `drafts, pre-releases, other tags and releases without an APK are passed over`() {
        val body = list(
            release("android/v9.0.0", draft = true),
            release("android/v8.0.0", prerelease = true),
            release("nightly-7"),
            release("server/v7.0.0"),
            release("v6.5.0"),
            release("android/v6.0.0-rc1"),
            release("android/v5.0.0", apk = null),
            release("android/v1.0.0"),
        )
        assertEquals("1.0.0", Releases.newest(body)!!.version)
    }

    @Test
    fun `a release without notes or checksum is still a release`() {
        val body = """[{"tag_name": "android/v1.2.3", "body": null,
            "assets": [{"name": "app.apk", "browser_download_url": "https://example.test/app.apk", "size": 5}]}]"""
        val release = Releases.newest(body)!!
        assertEquals("", release.notes)
        assertNull(release.sha256)
    }

    @Test
    fun `only what stands above the rule is shown as the changes`() {
        val withNotes = release("android/v1.0.0", body = "- One\\n- Two\\n\\n---\\n\\nFor a first install, download the APK.")
        assertEquals("- One\n- Two", Releases.newest(list(withNotes))!!.notes)

        val withoutNotes = release("android/v1.0.0", body = "---\\n\\nFor a first install, download the APK.")
        assertEquals("", Releases.newest(list(withoutNotes))!!.notes)
    }

    @Test
    fun `no release, or no answer GitHub would give, is no release`() {
        assertNull(Releases.newest("[]"))
        assertNull(Releases.newest("""{"message": "API rate limit exceeded"}"""))
        assertNull(Releases.newest("<html>captive portal</html>"))
        assertNull(Releases.newest(""))
    }

    @Test
    fun `versions compare by their numbers`() {
        assertTrue(Releases.isNewer("0.10.0", "0.9.9"))
        assertTrue(Releases.isNewer("1.0.0", "0.99.99"))
        assertTrue(Releases.isNewer("1.0.1", "1.0"))
        assertFalse(Releases.isNewer("1.0.0", "1.0.0"))
        assertFalse(Releases.isNewer("1.0", "1.0.0"))
        assertFalse(Releases.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `a version that is not numbers is never newer`() {
        assertFalse(Releases.isNewer("2.0.0-beta", "1.0.0"))
        assertFalse(Releases.isNewer("", "1.0.0"))
        assertFalse(Releases.isNewer("2.0.0", "debug"))
    }
}
