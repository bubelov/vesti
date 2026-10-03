package org.vestifeed.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL

internal actual suspend fun openDatabaseConnection(
    driver: SQLiteDriver,
    path: String,
): SQLiteConnection = LockingSQLiteConnection(driver.open(path))

internal actual suspend fun <T> withTransaction(conn: SQLiteConnection, block: suspend () -> T): T {
    if (conn is LockingSQLiteConnection) return conn.transaction(block)

    conn.execSQL("BEGIN TRANSACTION;")
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
    }
}
