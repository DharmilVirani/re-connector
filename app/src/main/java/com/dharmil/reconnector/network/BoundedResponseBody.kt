package com.dharmil.reconnector.network

import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

internal fun ResponseBody.limitedTo(maxBytes: Long): ResponseBody {
    require(maxBytes >= 0L)
    if (contentLength() > maxBytes) throw IOException("Map resource exceeds size limit")
    val upstream = source()
    val bounded = object : ForwardingSource(upstream) {
        private var bytesRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, minOf(byteCount, maxBytes - bytesRead + 1L))
            if (read > 0L) {
                bytesRead += read
                if (bytesRead > maxBytes) throw IOException("Map resource exceeds size limit")
            }
            return read
        }
    }.buffer()
    return object : ResponseBody() {
        override fun contentType() = this@limitedTo.contentType()
        override fun contentLength() = this@limitedTo.contentLength()
        override fun source() = bounded
    }
}
