package org.vestifeed.backend

import okio.IOException

/**
 * A non-2xx Miniflux response. The validator in `vestiHttpClient` throws this
 * instead of a bare [IOException] so a caller can react to a specific status
 * code:
 *
 * - `/v1/entries/ids` answers 404 on servers older than Miniflux 2.3.2, which
 *   have no such endpoint (see [Miniflux.getEntryIds]);
 * - the bulk entries update answers 400 on the same old servers, which do not
 *   understand the `starred` field (see [Miniflux.markEntriesAsBookmarked]).
 */
class MinifluxHttpException(
    val statusCode: Int,
    val url: String,
    message: String,
) : IOException(message)
