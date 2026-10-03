package org.vestifeed.parser

sealed class FeedResult {
    data class Success(val feed: Feed) : FeedResult()
    data class UnsupportedMediaType(val mediaType: String) : FeedResult()
    object UnsupportedFeedType : FeedResult()
    data class IOError(val cause: Throwable) : FeedResult()
    data class ParserError(val cause: Throwable) : FeedResult()
}
