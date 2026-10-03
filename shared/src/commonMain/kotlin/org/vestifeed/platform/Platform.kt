package org.vestifeed.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking IO. On the JVM and Android it is the dedicated IO
 * pool; a single-threaded web target uses the default dispatcher.
 */
expect val ioDispatcher: CoroutineDispatcher

/**
 * A reentrant mutual-exclusion lock, the common stand-in for the platform's own
 * lock. The JVM and Android use a `ReentrantLock`; the single-threaded web
 * target needs no real locking beyond a depth counter.
 */
expect class PlatformLock() {
    fun lock()
    fun unlock()
    fun isHeldByCurrentThread(): Boolean
}
