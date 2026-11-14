package com.dharmil.reconnector.dash.video

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketizerTest {
    @Test
    fun singleNalProducesRtpHeaderAndFrameMarker() {
        val packets = packetizer().packetize(EncodedFrame(annexB(0x65, 1, 2, 3), 1_000_000L))

        assertEquals(1, packets.size)
        assertEquals(0x80, packets.single()[0].toInt() and 0xFF)
        assertEquals(0xE0, packets.single()[1].toInt() and 0xFF)
        assertEquals(0xFFFF, unsignedShort(packets.single(), 2))
        assertEquals(90_000L, unsignedInt(packets.single(), 4))
        assertEquals(0x01020304L, unsignedInt(packets.single(), 8))
        assertTrue(packets.single().copyOfRange(12, packets.single().size).contentEquals(byteArrayOf(0x65, 1, 2, 3)))
    }

    @Test
    fun largeNalUsesFuAAndReassemblesWithoutLoss() {
        val nal = ByteArray(2_501) { index -> (index * 37).toByte() }.also { it[0] = 0x65 }
        val packets = packetizer(maxPayloadBytes = 256)
            .packetize(EncodedFrame(annexB(nal), 2_000_000L))

        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.size <= 12 + 256 })
        assertEquals(28, packets.first()[12].toInt() and 0x1F)
        assertTrue((packets.first()[13].toInt() and 0x80) != 0)
        assertTrue((packets.last()[13].toInt() and 0x40) != 0)
        assertFalse((packets.dropLast(1).last()[1].toInt() and 0x80) != 0)
        assertTrue((packets.last()[1].toInt() and 0x80) != 0)

        val restored = ByteArrayOutputStream()
        restored.write((packets.first()[12].toInt() and 0xE0) or (packets.first()[13].toInt() and 0x1F))
        packets.forEach { packet -> restored.write(packet, 14, packet.size - 14) }
        assertTrue(nal.contentEquals(restored.toByteArray()))
    }

    @Test
    fun markerIsSetOnlyOnLastNalOfAccessUnit() {
        val frame = annexB(0x67, 1, 2) + annexB(0x68, 3) + annexB(0x65, 4, 5)
        val packets = packetizer(initialSequence = 1).packetize(EncodedFrame(frame, 500_000L))

        assertEquals(3, packets.size)
        assertTrue(packets.dropLast(1).all { (it[1].toInt() and 0x80) == 0 })
        assertTrue((packets.last()[1].toInt() and 0x80) != 0)
        assertEquals(listOf(1, 2, 3), packets.map { unsignedShort(it, 2) })
    }

    @Test
    fun sequenceAndTimestampWrapAtTheirRtpWidths() {
        val packetizer = RtpPacketizer(initialSequence = 0xFFFF, ssrc = 1)
        val first = packetizer.packetize(EncodedFrame(annexB(0x65, 1), 47_722_000_000L)).single()
        val second = packetizer.packetize(EncodedFrame(annexB(0x65, 2), 47_722_000_000L)).single()

        assertEquals(0xFFFF, unsignedShort(first, 2))
        assertEquals(0, unsignedShort(second, 2))
        assertEquals(12_704L, unsignedInt(first, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPayloadType() {
        RtpPacketizer(payloadType = 128)
    }

    private fun packetizer(initialSequence: Int = 0xFFFF, maxPayloadBytes: Int = 1_200) =
        RtpPacketizer(initialSequence, 0x01020304, maxPayloadBytes = maxPayloadBytes)

    private fun RtpPacketizer.packetize(frame: EncodedFrame): List<ByteArray> = buildList {
        packetize(frame, ::add)
    }

    private fun annexB(vararg nalBytes: Int): ByteArray = annexB(ByteArray(nalBytes.size) { nalBytes[it].toByte() })

    private fun annexB(nal: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + nal

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
}
