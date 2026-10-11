package org.vestifeed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateStatusTest {

    private val manifest = UpdateManifest(
        versionCode = 24,
        versionName = "0.4.3",
        buildTime = 1_000_000L,
        apk = "https://vestifeed.org/releases/vesti-nightly.apk",
    )

    @Test
    fun noManifest_isUnknown() {
        assertEquals(UpdateStatus.Unknown, evaluateUpdate(24, 0L, null))
    }

    @Test
    fun manifestWithoutApk_isUnknown() {
        assertEquals(UpdateStatus.Unknown, evaluateUpdate(24, 0L, manifest.copy(apk = "")))
    }

    @Test
    fun newerVersionCode_isAvailable() {
        assertEquals(
            UpdateStatus.Available(manifest),
            evaluateUpdate(currentVersionCode = 23, currentBuildTime = 5_000_000L, manifest = manifest),
        )
    }

    @Test
    fun newerBuildWithinTheSameVersion_isAvailable() {
        assertEquals(
            UpdateStatus.Available(manifest),
            evaluateUpdate(currentVersionCode = 24, currentBuildTime = 999_999L, manifest = manifest),
        )
    }

    @Test
    fun sameBuild_isUpToDate() {
        assertEquals(
            UpdateStatus.UpToDate,
            evaluateUpdate(currentVersionCode = 24, currentBuildTime = 1_000_000L, manifest = manifest),
        )
    }

    @Test
    fun olderBuildWithinTheSameVersion_isUpToDate() {
        // A rolled-back channel must never look like an update.
        assertEquals(
            UpdateStatus.UpToDate,
            evaluateUpdate(currentVersionCode = 24, currentBuildTime = 1_000_001L, manifest = manifest),
        )
    }

    @Test
    fun parsesManifestJson() {
        val parsed = parseUpdateManifest(
            """{"versionCode":24,"versionName":"0.4.3","buildTime":1728600000000,"apk":"https://x/y.apk","extra":1}""",
        )
        assertEquals(24, parsed?.versionCode)
        assertEquals(1728600000000L, parsed?.buildTime)
        assertEquals("https://x/y.apk", parsed?.apk)
    }

    @Test
    fun rejectsNonManifestText() {
        assertNull(parseUpdateManifest("<html>not found</html>"))
    }
}
