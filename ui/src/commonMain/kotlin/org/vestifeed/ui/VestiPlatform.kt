package org.vestifeed.ui

/**
 * The platform services the shared Compose UI needs from its host. Android
 * implements these with a Custom Tab / share intent; the browser target opens
 * a new tab / uses the Web Share API.
 */
interface VestiPlatform {
    /**
     * Opens [url] in a browser. When [useBuiltInBrowser] is true the host uses
     * its in-app browser (an Android Custom Tab); otherwise it hands the link to
     * the system browser. Hosts without an in-app browser ignore the flag.
     */
    fun openUrl(url: String, useBuiltInBrowser: Boolean)

    /** Shares [text] through the platform share sheet. */
    fun shareText(text: String)
}
