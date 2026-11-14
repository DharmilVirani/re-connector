package com.dharmil.reconnector.dash.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class H264AccessUnitAssemblerTest {
    @Test
    fun combinesPartialChunksAndRetainsKeyFrameFlagFromFirstChunk() {
        val assembler = H264AccessUnitAssembler(PARTIAL_FRAME)

        assertNull(assembler.append(byteArrayOf(1, 2), 100L, PARTIAL_FRAME or KEY_FRAME))
        val frame = assembler.append(byteArrayOf(3, 4), 100L, 0)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame?.bytes)
        assertEquals(100L, frame?.presentationTimeUs)
        assertEquals(PARTIAL_FRAME or KEY_FRAME, frame?.flags)
    }

    @Test
    fun dropsTheRestOfAnOversizedPartialAccessUnit() {
        val assembler = H264AccessUnitAssembler(PARTIAL_FRAME, maxAccessUnitBytes = 3)
        assertNull(assembler.append(byteArrayOf(1, 2), 100L, PARTIAL_FRAME))
        assertThrows(IllegalArgumentException::class.java) {
            assembler.append(byteArrayOf(3, 4), 100L, PARTIAL_FRAME)
        }
        assertNull(assembler.append(byteArrayOf(5), 100L, PARTIAL_FRAME))
        assertNull(assembler.append(byteArrayOf(6), 100L, 0))
        assertArrayEquals(byteArrayOf(7), assembler.append(byteArrayOf(7), 200L, 0)?.bytes)
    }

    @Test
    fun abortDiscardsPendingPartialAccessUnitAfterMalformedChunk() {
        val assembler = H264AccessUnitAssembler(PARTIAL_FRAME)
        assertNull(assembler.append(byteArrayOf(1, 2), 100L, PARTIAL_FRAME))

        assembler.abort(PARTIAL_FRAME)

        assertNull(assembler.append(byteArrayOf(3, 4), 100L, 0))
        assertArrayEquals(byteArrayOf(5), assembler.append(byteArrayOf(5), 200L, 0)?.bytes)
    }

    private companion object {
        const val KEY_FRAME = 1
        const val PARTIAL_FRAME = 8
    }
}
