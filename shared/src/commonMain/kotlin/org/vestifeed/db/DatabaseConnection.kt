package org.vestifeed.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

/**
 * Opens the connection for [path], wrapped in the platform's serialization
 * layer where one is needed. On web the driver is already single-threaded and
 * asynchronous, so it is returned as-is.
 */
internal expect suspend fun openDatabaseConnection(
    driver: SQLiteDriver,
    path: String,
): SQLiteConnection

/**
 * Runs [block] as one transaction, serialized with every other statement where
 * the platform needs it.
 */
internal expect suspend fun <T> withTransaction(conn: SQLiteConnection, block: suspend () -> T): T
