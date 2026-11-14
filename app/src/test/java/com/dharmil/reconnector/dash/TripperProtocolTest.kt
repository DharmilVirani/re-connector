package com.dharmil.reconnector.dash

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TripperProtocolTest {
    @Test
    fun authenticationRequestPatchesSequenceWithoutChangingLength() {
        val packet = TripperProtocol.authenticationRequest(0x7F)

        assertEquals(22, packet.size)
        assertArrayEquals(hexBytes("0016000200000000020100054B3147207F0804000101"), packet)
        assertEquals(0x7F, packet[16].toInt() and 0xFF)
        assertEquals(22, ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF))
    }

    @Test
    fun parsesTypedSegmentsAndPayload() {
        val packet = byteArrayOf(
            0x00, 0x0F, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x07, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03,
        )

        val segment = TripperProtocol.parse(packet).single()

        assertEquals(0x07, segment.type)
        assertEquals(0x00, segment.subtype)
        assertArrayEquals(byteArrayOf(1, 2, 3), segment.payload)
    }

    @Test
    fun rejectsTruncatedSegmentPayload() {
        val packet = byteArrayOf(
            0x00, 0x0F, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x07, 0x00, 0x00, 0x04, 0x01, 0x02, 0x03,
        )

        assertThrows(IllegalArgumentException::class.java) { TripperProtocol.parse(packet) }
    }

    @Test
    fun encryptsSsidAndSessionKeyUsingDashPublicKeyFormat() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        val sessionKey = ByteArray(32) { it.toByte() }
        val packet = TripperProtocol.authenticationPacket(
            ssid = "RE_TEST",
            modulus = publicKey.modulus.toByteArray(),
            exponent = publicKey.publicExponent.toByteArray(),
            sessionKey = sessionKey,
            sequence = 42,
        )
        val decryptor = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, pair.private)
        }

        assertEquals(149, packet.size)
        assertArrayEquals(
            hexBytes("0095000200000000020100054B3147202A08000080"),
            packet.copyOfRange(0, 21),
        )
        assertEquals(42, packet[16].toInt() and 0xFF)
        assertArrayEquals(
            "RE_TEST".toByteArray(Charsets.UTF_8) + sessionKey,
            decryptor.doFinal(packet.copyOfRange(packet.size - 128, packet.size)),
        )
    }

    @Test
    fun rejectsOversizedDashPublicKeyBeforeRsaWork() {
        assertThrows(IllegalArgumentException::class.java) {
            TripperProtocol.authenticationPacket(
                ssid = "RE_TEST",
                modulus = ByteArray(130),
                exponent = byteArrayOf(0x01, 0x00, 0x01),
                sessionKey = ByteArray(32),
                sequence = 0,
            )
        }
    }

    @Test
    fun acceptsOtherBoundedOddRsaExponents() {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public as RSAPublicKey
        val packet = TripperProtocol.authenticationPacket(
            ssid = "RE_TEST",
            modulus = publicKey.modulus.toByteArray(),
            exponent = byteArrayOf(0x03),
            sessionKey = ByteArray(32),
            sequence = 0,
        )

        assertEquals(149, packet.size)
    }

    @Test
    fun routeCardUsesBoundedUtf8TitleAndProjectionFlag() {
        val packet = TripperProtocol.routeCard("Pune → Mumbai", sequence = 7, projectionEnabled = true)
        val markerIndex = packet.toList().windowed(4).indexOf(byteList(0x4B, 0x31, 0x47, 0x20))
        val titleLengthOffset = markerIndex + 7
        val titleLength = ((packet[titleLengthOffset].toInt() and 0xFF) shl 8) or
            (packet[titleLengthOffset + 1].toInt() and 0xFF)

        assertEquals(7, packet[markerIndex + 4].toInt() and 0xFF)
        assertEquals(
            "Pune → Mumbai\u0000",
            String(packet, titleLengthOffset + 2, titleLength, Charsets.UTF_8),
        )
        assertTrue(packet.toList().windowed(5).any { it == byteList(0x06, 0x05, 0x00, 0x01, 0x55) })
        assertEquals(packet.size, ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF))
    }

    @Test
    fun routeCardTruncationDoesNotSplitUtf8CodePoint() {
        val packet = TripperProtocol.routeCard("🚦".repeat(30), sequence = 0)
        val markerIndex = packet.toList().windowed(4).indexOf(byteList(0x4B, 0x31, 0x47, 0x20))
        val titleLengthOffset = markerIndex + 7
        val titleLength = ((packet[titleLengthOffset].toInt() and 0xFF) shl 8) or
            (packet[titleLengthOffset + 1].toInt() and 0xFF)

        assertEquals(60, titleLength - 1)
        assertEquals(
            "🚦".repeat(15) + "\u0000",
            String(packet, titleLengthOffset + 2, titleLength, Charsets.UTF_8),
        )
    }

    @Test
    fun activeNavigationPacketPatchesSequenceAndClampsDistances() {
        val packet = TripperProtocol.activeNavigation(
            sequence = 255,
            maneuverCode = 0x0B,
            distanceToManeuverMeters = 90_000,
            remainingDistanceMeters = 1_234,
        )
        val markerIndex = packet.toList().windowed(4).indexOf(byteList(0x4B, 0x31, 0x47, 0x20))

        assertEquals(9, (packet[2].toInt() and 0xFF) shl 8 or (packet[3].toInt() and 0xFF))
        assertEquals(255, packet[markerIndex + 4].toInt() and 0xFF)
        assertTrue(packet.toList().windowed(6).any {
            it == byteList(0x05, 0x04, 0x00, 0x02, 0xFF, 0xFF)
        })
        assertTrue(packet.toList().windowed(6).any {
            it == byteList(0x05, 0x09, 0x00, 0x02, 0x04, 0xD2)
        })
    }

    @Test
    fun controlPacketsUseDistinctSequencesAndProjectionOffTemplate() {
        val frame = TripperProtocol.projectionFrame(8)
        val stop = TripperProtocol.projectionStop(9)
        val off = TripperProtocol.projectionOff(10)

        assertEquals(8, frame[16].toInt() and 0xFF)
        assertEquals(9, stop[16].toInt() and 0xFF)
        assertEquals(10, off[16].toInt() and 0xFF)
        assertArrayEquals(hexBytes("0016000200000000020100054B3147200A06050001AA"), off)
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun byteList(vararg bytes: Int): List<Byte> = bytes.map(Int::toByte)

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
