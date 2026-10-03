package org.vestifeed.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import org.vestifeed.platform.PlatformLock

/**
 * Serializes every statement executed on a [SQLiteConnection].
 *
 * The app shares one connection between the UI's reads and the background
 * sync / OpenGraph-image writers, all of which run on the multi-threaded
 * [kotlinx.coroutines.Dispatchers.IO]. Android renders query results into a
 * `CursorWindow` that is refilled lazily as the cursor advances; a write
 * committed on another thread between two refills changes the window's row
 * count underneath the cursor, so the next `step()`/`get*()` throws
 * `IllegalStateException: Couldn't read row N, col M from CursorWindow`.
 *
 * Holding [lock] for the whole lifetime of a prepared statement (from
 * [prepare] until the statement is closed) guarantees a read cursor can never
 * be interleaved with a concurrent write. The lock is reentrant, so a
 * statement prepared inside [transaction] (or any nested statement) does not
 * deadlock.
 *
 * The same lock also makes the app correct on drivers whose connections are
 * not thread-safe at all, e.g. `BundledSQLiteDriver` (which is why this lives
 * here instead of relying on `AndroidSQLiteDriver`'s advertised connection
 * pool).
 */
internal class LockingSQLiteConnection(
    private val delegate: SQLiteConnection,
) : SQLiteConnection by delegate {

    private val lock = PlatformLock()

    override fun prepare(sql: String): SQLiteStatement {
        lock.lock()
        try {
            return LockingSQLiteStatement(delegate.prepare(sql), lock)
        } catch (t: Throwable) {
            lock.unlock()
            throw t
        }
    }

    override fun close() {
        lock.lock()
        try {
            delegate.close()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Runs [block] as a single atomic unit that no other statement can
     * interleave with. Nested [prepare] calls re-enter [lock] and therefore
     * join the same transaction instead of deadlocking.
     */
    suspend fun <T> transaction(block: suspend () -> T): T {
        lock.lock()
        try {
            delegate.execSQL("BEGIN TRANSACTION;")
            try {
                val result = block()
                delegate.execSQL("COMMIT;")
                return result
            } catch (e: Throwable) {
                try {
                    delegate.execSQL("ROLLBACK;")
                } catch (_: Throwable) {
                    // Ignored: the original exception is already on its way out.
                }
                throw e
            }
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Runs [block] in a transaction.
 *
 * When the connection is a [LockingSQLiteConnection] this takes the
 * serialization lock for the whole transaction, so concurrent writers cannot
 * observe (or mutate) the in-flight state. The fallback keeps the historical
 * manual BEGIN/COMMIT behaviour for bare connections (e.g. hand-built test
 * doubles).
 */
internal suspend fun <T> SQLiteConnection.transaction(block: suspend () -> T): T {
    if (this is LockingSQLiteConnection) {
        return this.transaction(block)
    }
    execSQL("BEGIN TRANSACTION;")
    try {
        val result = block()
        execSQL("COMMIT;")
        return result
    } catch (e: Throwable) {
        try {
            execSQL("ROLLBACK;")
        } catch (_: Throwable) {
            // Ignored: the original exception is already on its way out.
        }
        throw e
    }
}

private class LockingSQLiteStatement(
    private val delegate: SQLiteStatement,
    private val lock: PlatformLock,
) : SQLiteStatement by delegate {

    private var closed = false

    override fun close() {
        try {
            delegate.close()
        } finally {
            if (!closed) {
                closed = true
                lock.unlock()
            }
        }
    }
}
