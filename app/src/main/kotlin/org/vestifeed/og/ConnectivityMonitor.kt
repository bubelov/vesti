package org.vestifeed.og

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Reports whether the device currently has working internet.
 *
 * Pure interface so the OG fetcher can be unit-tested in the JVM
 * without `ConnectivityManager`. The fetcher consults this once per
 * iteration; there is no subscription, so a transient network change
 * is picked up on the next tick (within a few seconds).
 */
fun interface ConnectivityMonitor {
    fun isOnline(): Boolean
}

/**
 * Production implementation backed by [ConnectivityManager]. Returns
 * `true` only when the active network has both `NET_CAPABILITY_INTERNET`
 * and `NET_CAPABILITY_VALIDATED` — i.e. the OS has confirmed the user
 * can actually reach the public internet through it.
 *
 * This deliberately excludes captive portals (signed-out Wi-Fi that
 * hasn't gone through the splash page yet), freshly-enabled unvalidated
 * networks, "no network" states, and airplane mode. Including those
 * cases was what produced thousands of `UnknownHostException` log
 * entries during a single DNS blip in past releases.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isOnline(): Boolean {
        val manager = connectivityManager ?: return false
        val activeNetwork = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
