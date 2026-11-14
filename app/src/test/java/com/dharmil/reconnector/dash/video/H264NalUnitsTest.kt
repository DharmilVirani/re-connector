package com.dharmil.reconnector.dash.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class H264NalUnitsTest {
    @Test
    fun fourByteLengthPrefixIsNotMistakenForAnnexBAt256Bytes() {
        assertLengthPrefixedNalPreserved(256)
    }

    @Test
    fun fourByteLengthPrefixIsNotMistakenForAnnexBAt257Bytes() {
        assertLengthPrefixedNalPreserved(257)
    }

    @Test
    fun rejectsAmbiguous257ByteLengthPrefixWhenFramingMetadataIsMissing() {
        val nal = ByteArray(257) { index -> (index * 31).toByte() }.also { it[0] = 0x65 }
        val framed = lengthPrefixed(nal)

        assertThrows(IllegalArgumentException::class.java) {
            H264NalUnits.toAnnexB(framed)
        }
        assertEquals(H264NalFraming.UNKNOWN, H264NalUnits.detectFraming(framed))
    }

    @Test
    fun rejectsRawNalThatAlsoFormsAValidLengthPrefixedNal() {
        val rawNal = byteArrayOf(0x65, 0x65) + ByteArray(100) { 0x55 }

        assertThrows(IllegalArgumentException::class.java) { H264NalUnits.toAnnexB(rawNal) }
    }

    @Test
    fun annexBIsPreserved() {
        val expected = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2)

        assertArrayEquals(expected, H264NalUnits.toAnnexB(expected))
    }

    @Test
    fun threeByteAnnexBIsPreservedWhenItsBytesCouldLookLikeALengthPrefix() {
        val nal = ByteArray(358) { 0x55 }.also {
            it[0] = 0x65
            it[1] = 0x41
        }
        val expected = byteArrayOf(0, 0, 1) + nal

        assertArrayEquals(expected, H264NalUnits.toAnnexB(expected, framing = H264NalFraming.ANNEX_B))
        assertThrows(IllegalArgumentException::class.java) { H264NalUnits.toAnnexB(expected) }
    }

    @Test
    fun avcConfigurationDetectsLengthPrefixAndNormalizesMultipleParameterSets() {
        val avcConfiguration = byteArrayOf(
            1, 66, 0, 30, 0xFF.toByte(), 0xE1.toByte(), 0, 2, 0x67, 0,
            1, 0, 2, 0x68, 0,
        )

        assertEquals(H264NalFraming.LENGTH_PREFIXED, H264NalUnits.detectFraming(avcConfiguration))
        assertEquals(4, H264NalUnits.avcNalLengthSize(avcConfiguration))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 0, 1, 0x68, 0),
            H264NalUnits.toAnnexB(avcConfiguration, codecConfig = true),
        )
    }

    @Test
    fun normalizesMultipleLengthPrefixedNals() {
        val frame = byteArrayOf(0, 0, 0, 2, 0x67, 1, 0, 0, 0, 2, 0x68, 2)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2),
            H264NalUnits.toAnnexB(frame, framing = H264NalFraming.LENGTH_PREFIXED),
        )
    }

    private fun assertLengthPrefixedNalPreserved(size: Int) {
        val nal = ByteArray(size) { index -> (index * 31).toByte() }.also { it[0] = 0x65 }
        val frame = H264NalUnits.toAnnexB(lengthPrefixed(nal), framing = H264NalFraming.LENGTH_PREFIXED)
        val packets = buildList {
            RtpPacketizer(initialSequence = 7, ssrc = 1).packetize(EncodedFrame(frame, 0L), ::add)
        }

        assertEquals(1, packets.size)
        assertArrayEquals(nal, packets.single().copyOfRange(12, packets.single().size))
    }

    private fun lengthPrefixed(nal: ByteArray): ByteArray = byteArrayOf(
        (nal.size ushr 24).toByte(),
        (nal.size ushr 16).toByte(),
        (nal.size ushr 8).toByte(),
        nal.size.toByte(),
    ) + nal
}
