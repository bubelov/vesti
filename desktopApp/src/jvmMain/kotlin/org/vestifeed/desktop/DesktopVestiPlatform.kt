package org.vestifeed.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import org.vestifeed.ui.VestiPlatform

/**
 * Desktop implementation of the shared UI's platform services. Opening a link
 * hands it to the system browser; sharing copies the text to the clipboard,
 * since desktop has no share sheet (the same fallback the browser target uses).
 */
class DesktopVestiPlatform : VestiPlatform {

    // Desktop has no in-app browser, so the flag is ignored and links always go
    // to the system browser.
    override fun openUrl(url: String, useBuiltInBrowser: Boolean) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }

    override fun shareText(text: String) {
        runCatching {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }
}
