package org.vestifeed.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL

internal actual suspend fun openDatabaseConnection(
    driver: SQLiteDriver,
    path: String,
): SQLiteConnection = driver.open(path)

/**
 * How many [withTransaction] calls are currently on the stack. The browser is
 * single-threaded, so a plain counter is enough. The outermost call owns the
 * SQL transaction; a nested call joins it instead of issuing a second `BEGIN`,
 * which SQLite rejects with "cannot start a transaction within a transaction".
 */
private var transactionDepth = 0

internal actual suspend fun <T> withTransaction(conn: SQLiteConnection, block: suspend () -> T): T {
    if (transactionDepth > 0) {
        transactionDepth++
        try {
            return block()
        } finally {
            transactionDepth--
        }
    }

    conn.execSQL("BEGIN TRANSACTION;")
    transactionDepth = 1
    try {
        val result = block()
        conn.execSQL("COMMIT;")
        return result
    } catch (e: Throwable) {
        try {
            conn.execSQL("ROLLBACK;")
        } catch (_: Throwable) {
            // Ignored: the original exception is already on its way out.
        }
        throw e
    } finally {
        transactionDepth = 0
    }
}
