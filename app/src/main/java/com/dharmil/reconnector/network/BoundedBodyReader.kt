package com.dharmil.reconnector.network

import okhttp3.ResponseBody
import okio.Buffer

internal fun ResponseBody.readUtf8Limited(maxBytes: Long): String {
    require(maxBytes >= 0L)
    val source = source()
    val buffer = Buffer()
    while (buffer.size <= maxBytes) {
        val remaining = maxBytes + 1L - buffer.size
        if (source.read(buffer, minOf(READ_CHUNK_BYTES, remaining)) == -1L) break
    }
    require(buffer.size <= maxBytes) { "Response exceeds size limit" }
    return buffer.readUtf8()
}

private const val READ_CHUNK_BYTES = 8_192L
