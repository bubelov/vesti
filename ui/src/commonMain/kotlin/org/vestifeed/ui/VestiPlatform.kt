package org.vestifeed.ui

/**
 * The platform services the shared Compose UI needs from its host. Android
 * implements these with a Custom Tab / share intent; the browser target opens
 * a new tab / uses the Web Share API.
 */
interface VestiPlatform {
    /** Opens [url] in the platform's browser (or an in-app Custom Tab on Android). */
    fun openUrl(url: String)

    /** Shares [text] through the platform share sheet. */
    fun shareText(text: String)
}
