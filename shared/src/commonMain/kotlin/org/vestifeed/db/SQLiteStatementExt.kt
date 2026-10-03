package org.vestifeed.db

import androidx.sqlite.SQLiteStatement
import io.ktor.http.Url
import kotlin.time.Instant
import org.vestifeed.util.toInstant
import org.vestifeed.util.toUrl

fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

fun SQLiteStatement.bindBooleanOrNull(index: Int, value: Boolean?) {
    if (value == null) bindNull(index) else bindInt(index, if (value) 1 else 0)
}

fun SQLiteStatement.bindLongOrNull(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

fun SQLiteStatement.bindHttpUrlOrNull(index: Int, value: Url?) {
    if (value == null) bindNull(index) else bindText(index, value.toString())
}

fun SQLiteStatement.bindInstantOrNull(index: Int, value: Instant?) {
    if (value == null) bindNull(index) else bindText(index, value.toString())
}

fun SQLiteStatement.getTextOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

fun SQLiteStatement.getBoolOrNull(index: Int): Boolean? =
    if (isNull(index)) null else getInt(index) != 0

fun SQLiteStatement.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

fun SQLiteStatement.getInstantOrNull(index: Int): Instant? =
    if (isNull(index)) null else getText(index).toInstant()

fun SQLiteStatement.getHttpUrlOrNull(index: Int): Url? =
    if (isNull(index)) null else getText(index).toUrl()
