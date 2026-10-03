package org.vestifeed.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide event bus for authentication invalidation.
 *
 * The HTTP layer reports failed or revoked credentials via [reportInvalidated],
 * and the UI layer observes [invalidationCount] to log the user out.
 *
 * A monotonically increasing counter on a [StateFlow] is used (rather than a
 * one-shot [kotlinx.coroutines.flow.SharedFlow]) so that signals emitted while
 * no one is collecting - for example a 401 from a background sync while the
 * Activity is stopped - are still delivered when a collector reattaches.
 * [StateFlow] replays the latest value to a new subscriber; the `filter { it
 * > 0 }` on the consumer side turns that replay into an invalidation event
 * exactly when the counter is non-zero (i.e. an invalidation actually
 * occurred) and ignores the initial zero value.
 *
 * The counter is reset to zero by [reset] after a successful login, so that
 * the next invalidation starts from a clean slate.
 */
object AuthEvents {
    private val _invalidationCount = MutableStateFlow(0L)

    /**
     * Number of times authentication has been reported as invalidated since
     * the last [reset]. Observe with `filter { it > 0 }` to skip the initial
     * zero value emitted at app start and to ignore resets.
     */
    val invalidationCount: StateFlow<Long> = _invalidationCount.asStateFlow()

    /**
     * Signal that the current credentials are no longer valid (for example a
     * Miniflux 401 response). Increments [invalidationCount] by one.
     */
    fun reportInvalidated() {
        _invalidationCount.update { it + 1 }
    }

    /**
     * Clear the invalidation counter. Call after a successful login so the
     * next invalidation starts from a clean slate.
     *
     * Subscribers using `filter { it > 0 }` will not re-trigger on the reset
     * itself (the zero is filtered out); they will trigger again on the next
     * [reportInvalidated] call.
     */
    fun reset() {
        _invalidationCount.update { 0L }
    }
}
