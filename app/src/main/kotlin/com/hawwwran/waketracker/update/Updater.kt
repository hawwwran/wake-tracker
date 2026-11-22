package com.hawwwran.waketracker.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.hawwwran.waketracker.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The app is in no store: it updates itself from the releases of its GitHub repository,
 * which .project-manager/release publishes.
 *
 * It asks GitHub which release is the newest, at most twice a day and on demand, and shows
 * a bar while a newer one is waiting. On the owner's request it downloads the APK, checks
 * it against the size and SHA-256 GitHub lists, and hands it to Android's installer. The
 * installer asks the owner and accepts only an APK signed with the same key as the installed
 * app (docs/signing.md), so a file that is not ours cannot replace the app, and it keeps the
 * app's data: an update never touches the record.
 */
class Updater(private val app: Application, private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State

        data class Available(val release: Release) : State

        data class Downloading(val release: Release, val fraction: Float) : State

        /** Downloaded and verified. [waitingForPermission] while the owner is on the settings page that lets this app install. */
        data class Ready(val release: Release, val file: File, val waitingForPermission: Boolean = false) : State

        data class DownloadFailed(val release: Release) : State
    }

    enum class Check { RUNNING, UP_TO_DATE, FOUND, UNREACHABLE }

    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val folder = File(app.cacheDir, "update")

    private val current = MutableStateFlow(remembered())
    val state: StateFlow<State> = current

    /** How the last check went, for the status page. Null before the first one of this run. */
    private val lastCheck = MutableStateFlow<Check?>(null)
    val check: StateFlow<Check?> = lastCheck

    private var download: Job? = null

    init {
        // What is left of an update that has been installed.
        if (current.value is State.Idle) scope.launch(Dispatchers.IO) { folder.deleteRecursively() }
    }

    /** On every return to the app; asks GitHub only when the last answer is old. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong(CHECKED_AT, 0) >= CHECK_EVERY_MS) checkNow()
    }

    fun checkNow() {
        if (lastCheck.value == Check.RUNNING) return
        lastCheck.value = Check.RUNNING
        scope.launch(Dispatchers.IO) {
            val body = fetch("${source()}/repos/$REPO/releases?per_page=50")
            if (body == null) {
                lastCheck.value = Check.UNREACHABLE
                return@launch
            }
            val offered = Releases.newest(body)?.takeIf { Releases.isNewer(it.version, BuildConfig.VERSION_NAME) }
            remember(offered)
            val now = current.value
            current.value = when {
                // A download under way, or done, for that same version is not thrown away.
                offered != null && now.release()?.version == offered.version -> now
                offered != null -> State.Available(offered)
                now is State.Downloading || now is State.Ready -> now
                else -> State.Idle
            }
            lastCheck.value = if (offered != null) Check.FOUND else Check.UP_TO_DATE
        }
    }

    /** Downloads the waiting release and opens the installer for it. */
    fun downloadAndInstall() {
        val release = when (val now = current.value) {
            is State.Available -> now.release
            is State.DownloadFailed -> now.release
            else -> return
        }
        current.value = State.Downloading(release, 0f)
        download = scope.launch(Dispatchers.IO) {
            var finished = false
            try {
                val file = fetchApk(release) { fraction -> current.value = State.Downloading(release, fraction) }
                finished = true
                if (file == null) {
                    current.value = State.DownloadFailed(release)
                } else {
                    current.value = State.Ready(release, file)
                    withContext(Dispatchers.Main) { install() }
                }
            } finally {
                // Cancelled by the owner: back to where it was offered.
                if (!finished) current.value = State.Available(release)
            }
        }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    /**
     * Opens Android's installer for the downloaded update, or first the settings page where
     * the owner allows this app to install. Main thread.
     */
    fun install() {
        val ready = current.value as? State.Ready ?: return
        if (!ready.file.isFile) {
            current.value = State.Available(ready.release)
            return
        }
        try {
            if (!app.packageManager.canRequestPackageInstalls()) {
                current.value = ready.copy(waitingForPermission = true)
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            current.value = ready.copy(waitingForPermission = false)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", ready.file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no installer or settings page to open", e)
        }
    }

    /** Back in the app: when the owner has just allowed installing, the install goes on by itself. */
    fun onResume() {
        val ready = current.value as? State.Ready ?: return
        if (ready.waitingForPermission && app.packageManager.canRequestPackageInstalls()) install()
    }

    private suspend fun fetchApk(release: Release, onProgress: (Float) -> Unit): File? {
        folder.mkdirs()
        val target = File(folder, "wake-tracker-${release.version}.apk")
        // Already here from an attempt that stopped at the installer.
        if (target.isFile && isTheUpdate(target, release)) return target
        folder.listFiles()?.forEach { it.delete() }

        val partial = File(folder, target.name + ".part")
        try {
            val connection = open(release.apkUrl)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (release.apkSize > 0) onProgress((done.toFloat() / release.apkSize).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (isTheUpdate(partial, release) && partial.renameTo(target)) return target
            Log.w(TAG, "the download of ${release.version} is not the APK GitHub lists; discarded")
            return null
        } catch (e: IOException) {
            Log.w(TAG, "downloading ${release.version} failed", e)
            return null
        } finally {
            partial.delete()
        }
    }

    /** The file is what GitHub lists, and an APK of this app that Android would take as an update. */
    private fun isTheUpdate(file: File, release: Release): Boolean {
        if (release.apkSize > 0 && file.length() != release.apkSize) return false
        if (release.sha256 != null && !sha256(file).equals(release.sha256, ignoreCase = true)) return false
        val archive = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        return archive.packageName == app.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) >= PackageInfoCompat.getLongVersionCode(installed)
    }

    private fun fetch(url: String): String? =
        try {
            val connection = open(url)
            try {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.use { it.readBytes().decodeToString() } else null
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "asking for releases failed", e)
            null
        }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "wake-tracker-updater")
        }

    /** The newer release found by the last check, kept so the bar is there at the next start without asking again. */
    private fun remembered(): State {
        val version = prefs.getString(VERSION, null) ?: return State.Idle
        val apkUrl = prefs.getString(APK_URL, null)
        if (apkUrl.isNullOrEmpty() || !Releases.isNewer(version, BuildConfig.VERSION_NAME)) return State.Idle
        return State.Available(
            Release(
                version = version,
                apkUrl = apkUrl,
                apkSize = prefs.getLong(APK_SIZE, 0),
                sha256 = prefs.getString(SHA256, null),
                notes = prefs.getString(NOTES, "").orEmpty(),
            ),
        )
    }

    private fun remember(release: Release?) {
        prefs.edit {
            putLong(CHECKED_AT, System.currentTimeMillis())
            putString(VERSION, release?.version)
            putString(APK_URL, release?.apkUrl)
            putLong(APK_SIZE, release?.apkSize ?: 0)
            putString(SHA256, release?.sha256)
            putString(NOTES, release?.notes)
        }
    }

    /**
     * GitHub's API. A debug build asks the address in files/update-source instead when that
     * file exists, so the whole flow can be tried against a local server (README, "Trying an
     * update without releasing").
     */
    private fun source(): String {
        if (!BuildConfig.DEBUG) return GITHUB_API
        val override = File(app.filesDir, "update-source")
        return override.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: GITHUB_API
    }

    companion object {
        private const val TAG = "WakeTracker"
        private const val GITHUB_API = "https://api.github.com"
        private const val REPO = "hawwwran/wake-tracker"
        private const val APK_TYPE = "application/vnd.android.package-archive"
        private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

        private const val CHECKED_AT = "checked_at"
        private const val VERSION = "version"
        private const val APK_URL = "apk_url"
        private const val APK_SIZE = "apk_size"
        private const val SHA256 = "sha256"
        private const val NOTES = "notes"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** The release a state is about; none while there is nothing newer. */
fun Updater.State.release(): Release? = when (this) {
    Updater.State.Idle -> null
    is Updater.State.Available -> release
    is Updater.State.Downloading -> release
    is Updater.State.Ready -> release
    is Updater.State.DownloadFailed -> release
}
