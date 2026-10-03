package org.vestifeed.og

/**
 * Reports whether the device currently has working internet.
 *
 * Pure interface so the OG fetcher can be unit-tested without
 * `ConnectivityManager`. The fetcher consults this once per iteration; there
 * is no subscription, so a transient network change is picked up on the next
 * tick (within a few seconds).
 *
 * The Android-backed implementation stays in the app module, since it needs
 * `android.net.ConnectivityManager`.
 */
fun interface ConnectivityMonitor {
    fun isOnline(): Boolean
}
