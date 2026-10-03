package org.vestifeed.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual class PlatformLock actual constructor() {
    private val delegate = java.util.concurrent.locks.ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()

    actual fun isHeldByCurrentThread(): Boolean = delegate.isHeldByCurrentThread
}
