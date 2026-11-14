package com.dharmil.reconnector.network

import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedBodyReaderTest {
    @Test
    fun readsBodiesWithinLimit() {
        assertEquals("route", "route".toResponseBody().readUtf8Limited(5L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBodiesOverLimit() {
        "route".toResponseBody().readUtf8Limited(4L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedBodyWithoutContentLength() {
        unknownLengthBody("route").readUtf8Limited(4L)
    }

    private fun unknownLengthBody(value: String) = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(value)
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = buffer
    }
}
