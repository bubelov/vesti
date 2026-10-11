package org.vestifeed.ui.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vestifeed.ui.downloadToFile
import org.vestifeed.update.UpdateManifest
import org.vestifeed.update.parseUpdateManifest

/**
 * The Android self-update channel: it reads a manifest published next to the
 * nightly APK and, when it describes a newer build, downloads it and hands it
 * to the system package installer.
 *
 * The app is side-loaded, so there is no store to push an update; this is the
 * only way an installed build learns about a newer one. Installing an APK
 * requires the user to have allowed it once for this app, which lives in the
 * per-app "install unknown apps" settings.
 */
class AndroidUpdateService(
    private val context: Context,
    private val manifestUrl: String,
    override val currentBuildTime: Long,
    private val userAgent: String,
) : UpdateService {

    private val prefs = context.getSharedPreferences("vesti.updates", Context.MODE_PRIVATE)

    private val packageInfo =
        context.packageManager.getPackageInfo(context.packageName, 0)

    override val currentVersionCode: Int = packageInfo.longVersionCode.toInt()

    override val currentVersionLabel: String = buildString {
        append(packageInfo.versionName ?: "?")
        if (currentBuildTime > 0) {
            append(" (")
            append(BUILD_DATE_FORMAT.format(Instant.ofEpochMilli(currentBuildTime)))
            append(")")
        }
    }

    override suspend fun fetchLatest(): UpdateManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            connection.inputStream.use { it.readBytes().decodeToString() }
                .also { connection.disconnect() }
        }.getOrNull()?.let(::parseUpdateManifest)
    }

    override fun isDismissed(buildTime: Long): Boolean =
        prefs.getLong(KEY_DISMISSED, 0L) == buildTime

    override fun dismiss(buildTime: Long) {
        prefs.edit().putLong(KEY_DISMISSED, buildTime).apply()
    }

    override suspend fun install(
        manifest: UpdateManifest,
        onProgress: (Double?) -> Unit,
    ): UpdateInstallResult {
        // Ask for the install permission before spending a download on it.
        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallPermissionSettings()
            return UpdateInstallResult.NeedsPermission
        }

        val apk = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "vesti-update.apk")
            // Always re-download: a cached older APK would install the wrong build.
            downloadToFile(manifest.apk, file, onProgress, reuseExisting = false)?.let(::File)
        } ?: return UpdateInstallResult.Failed("Could not download the update")

        return launchInstaller(apk)
    }

    private fun launchInstaller(apk: File): UpdateInstallResult = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        UpdateInstallResult.Started
    }.getOrElse {
        UpdateInstallResult.Failed("Could not start the installer")
    }

    private fun openInstallPermissionSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        const val KEY_DISMISSED = "dismissedBuildTime"

        val BUILD_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
