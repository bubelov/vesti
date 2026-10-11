package org.vestifeed.ui.updates

import org.vestifeed.update.UpdateManifest

/** What happened when a downloaded update was handed to the system installer. */
sealed interface UpdateInstallResult {
    /** The system installer was launched; the user confirms from there. */
    data object Started : UpdateInstallResult

    /**
     * The OS requires the user to allow installs from this app first; the host
     * opened the relevant settings screen, and the caller should tell the user
     * to retry once it is granted.
     */
    data object NeedsPermission : UpdateInstallResult

    data class Failed(val message: String) : UpdateInstallResult
}

/**
 * The host's self-update channel. Android side-loads its own APK and so can
 * fetch a published manifest and hand a newer APK to the system installer;
 * desktop and the browser cannot install packages and leave
 * [org.vestifeed.ui.VestiPlatform.updates] null.
 */
interface UpdateService {
    /** The running build's version code, compared against the manifest's. */
    val currentVersionCode: Int

    /** The running build's time, breaking ties within one version code. */
    val currentBuildTime: Long

    /** A short "what am I running" label for the settings screen. */
    val currentVersionLabel: String

    /** Fetches the channel's manifest, or null when it cannot be read. */
    suspend fun fetchLatest(): UpdateManifest?

    /** Whether the user already dismissed the prompt for the build at [buildTime]. */
    fun isDismissed(buildTime: Long): Boolean

    /** Remembers that the prompt for the build at [buildTime] was dismissed. */
    fun dismiss(buildTime: Long)

    /**
     * Downloads [manifest]'s APK, reporting progress in `0f..1f` (null while the
     * size is unknown), and starts the system install flow.
     */
    suspend fun install(
        manifest: UpdateManifest,
        onProgress: (Double?) -> Unit,
    ): UpdateInstallResult
}
