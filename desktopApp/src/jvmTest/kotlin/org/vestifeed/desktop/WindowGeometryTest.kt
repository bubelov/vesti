package org.vestifeed.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored window geometry is the only thing the desktop host reads from
 * disk at startup, and a bad value there means an unusable window, so the
 * validation is exercised directly rather than through a launch.
 */
class WindowGeometryTest {

    private val directory: File = Files.createTempDirectory("vesti-window-test").toFile()
    private val file: File = File(directory, "window.properties")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun savesAndLoadsTheFloatingSize() {
        WindowGeometry(width = 1400, height = 900, maximized = false).save(file)

        assertEquals(WindowGeometry(1400, 900, maximized = false), WindowGeometry.load(file))
    }

    @Test
    fun savesAndLoadsTheMaximizedFlag() {
        WindowGeometry(width = 1100, height = 800, maximized = true).save(file)

        assertEquals(WindowGeometry(1100, 800, maximized = true), WindowGeometry.load(file))
    }

    @Test
    fun anAbsentFileIsAFirstLaunch() {
        assertNull(WindowGeometry.load(File(directory, "missing.properties")))
    }

    @Test
    fun anUnreadableFileIsAFirstLaunch() {
        file.writeText("this is not a properties file\u0000\u0001 and it never will be")

        assertNull(WindowGeometry.load(file))
    }

    @Test
    fun missingKeysAreAFirstLaunch() {
        file.writeText("width=1200\n")

        assertNull(WindowGeometry.load(file))
    }

    @Test
    fun implausibleSizesAreIgnored() {
        listOf(
            "width=0\nheight=800\n",
            "width=-1200\nheight=800\n",
            "width=1200\nheight=0\n",
            "width=1200\nheight=999999\n",
            "width=notanumber\nheight=800\n",
        ).forEach { contents ->
            file.writeText(contents)
            assertNull(WindowGeometry.load(file), "should be rejected: $contents")
        }
    }

    @Test
    fun aSizeAtTheMinimumIsAccepted() {
        WindowGeometry(width = 320, height = 240, maximized = false).save(file)

        assertEquals(320, WindowGeometry.load(file)?.width)
        assertEquals(240, WindowGeometry.load(file)?.height)
    }

    @Test
    fun anUnparseableMaximizedFlagMeansNotMaximized() {
        file.writeText("width=1200\nheight=800\nmaximized=yes please\n")

        assertEquals(false, WindowGeometry.load(file)?.maximized)
    }

    @Test
    fun savingReplacesThePreviousGeometry() {
        WindowGeometry(1200, 800, maximized = false).save(file)
        WindowGeometry(1500, 1000, maximized = true).save(file)

        assertEquals(WindowGeometry(1500, 1000, maximized = true), WindowGeometry.load(file))
    }

    @Test
    fun savingLeavesNoTemporaryFileBehind() {
        WindowGeometry(1200, 800, maximized = false).save(file)

        assertEquals(
            listOf("window.properties"),
            directory.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }

    @Test
    fun savingCreatesTheDataDirectory() {
        val nested = File(directory, "a/b/window.properties")

        WindowGeometry(1200, 800, maximized = false).save(nested)

        assertTrue(nested.isFile)
        assertEquals(1200, WindowGeometry.load(nested)?.width)
    }
}
