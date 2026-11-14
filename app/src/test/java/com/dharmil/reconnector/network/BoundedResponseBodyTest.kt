package com.dharmil.reconnector.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class BoundedResponseBodyTest {
    @Test
    fun readsResourceAtLimit() {
        assertEquals("tile", "tile".toResponseBody().limitedTo(4L).string())
    }

    @Test(expected = IOException::class)
    fun rejectsUnknownLengthResourceOverLimit() {
        unknownLength("tiles").limitedTo(4L).string()
    }

    private fun unknownLength(value: String) = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(value)
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = buffer
    }
}
