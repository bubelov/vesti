package org.vestifeed.sync

sealed class SyncResult {
    class Success(val newAndUpdatedEntries: Int) : SyncResult()
    class Failure(val cause: Exception) : SyncResult()
}
