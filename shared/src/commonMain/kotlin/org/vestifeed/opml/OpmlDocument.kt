package org.vestifeed.opml

data class OpmlDocument(
    val version: OpmlVersion,
    val outlines: List<OpmlOutline>,
)

enum class OpmlVersion(val value: String) {
    V_1_0("1.0"),
    V_1_1("1.1"),
    V_2_0("2.0"),
}

data class OpmlOutline(
    val text: String,
    val outlines: List<OpmlOutline>,

    // Common optional properties
    val xmlUrl: String?,
    val htmlUrl: String?,

    // App-specific extensions
    val extOpenEntriesInBrowser: Boolean?,
    val extShowPreviewImages: Boolean?,
    val extBlockedWords: String?,
)

fun opmlVersion(value: String): Result<OpmlVersion> {
    return runCatching {
        OpmlVersion.values().single { it.value == value }
    }
}

fun OpmlDocument.leafOutlines(): List<OpmlOutline> {
    return outlines.map { it.leafOutlines() }.flatten()
}

private fun OpmlOutline.leafOutlines(): List<OpmlOutline> {
    return if (outlines.isEmpty()) {
        listOf(this)
    } else {
        outlines.map { it.leafOutlines() }.flatten()
    }
}
