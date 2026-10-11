package org.vestifeed.update

/** The result of comparing the running build against a channel manifest. */
sealed interface UpdateStatus {
    /** Nothing to compare: no channel on this host, or the check failed. */
    data object Unknown : UpdateStatus

    data object UpToDate : UpdateStatus

    data class Available(val manifest: UpdateManifest) : UpdateStatus
}

/**
 * Decides whether [manifest] describes a build newer than the running one.
 *
 * A higher version code always wins. Within the same version code — a nightly
 * keeps the release's [UpdateManifest.versionCode] — a later build time does.
 * That is what lets a nightly notice the next nightly rolled out under it.
 */
fun evaluateUpdate(
    currentVersionCode: Int,
    currentBuildTime: Long,
    manifest: UpdateManifest?,
): UpdateStatus {
    if (manifest == null || manifest.apk.isBlank()) return UpdateStatus.Unknown
    val newer = manifest.versionCode > currentVersionCode ||
        (manifest.versionCode == currentVersionCode && manifest.buildTime > currentBuildTime)
    return if (newer) UpdateStatus.Available(manifest) else UpdateStatus.UpToDate
}
