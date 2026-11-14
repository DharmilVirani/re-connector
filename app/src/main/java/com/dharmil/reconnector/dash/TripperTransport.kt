package com.dharmil.reconnector.dash

import android.net.Network
import androidx.annotation.WorkerThread
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

data class ReceivedControlPacket(val bytes: ByteArray, val source: InetAddress)

class TripperTransport private constructor(
    private val bikeAddress: InetAddress,
    private val controlSocket: DatagramSocket,
    private val videoSocket: DatagramSocket,
    private val receiveSocket: DatagramSocket,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val receiveBuffer = ByteArray(MAX_RECEIVE_PACKET_BYTES)
    private val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

    @WorkerThread
    fun sendControl(packet: ByteArray) {
        check(!closed.get()) { "Tripper transport is closed" }
        require(packet.isNotEmpty() && packet.size <= MAX_CONTROL_PACKET_BYTES)
        controlSocket.send(DatagramPacket(packet, packet.size, bikeAddress, CONTROL_PORT))
    }

    @WorkerThread
    fun sendVideo(packet: ByteArray) {
        check(!closed.get()) { "Tripper transport is closed" }
        require(packet.size in RTP_HEADER_MIN_BYTES..MAX_VIDEO_PACKET_BYTES)
        videoSocket.send(DatagramPacket(packet, packet.size, bikeAddress, VIDEO_PORT))
    }

    @WorkerThread
    @Throws(IOException::class)
    @Synchronized
    fun receiveControl(timeoutMillis: Int = RECEIVE_TIMEOUT_MILLIS): ReceivedControlPacket {
        check(!closed.get()) { "Tripper transport is closed" }
        receiveSocket.soTimeout = timeoutMillis
        receivePacket.length = receiveBuffer.size
        receiveSocket.receive(receivePacket)
        return ReceivedControlPacket(
            receiveBuffer.copyOf(receivePacket.length),
            receivePacket.address,
        )
    }

    fun isFromBike(packet: ReceivedControlPacket): Boolean = packet.source == bikeAddress

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        controlSocket.close()
        videoSocket.close()
        receiveSocket.close()
    }

    companion object {
        private const val CONTROL_PORT = 2000
        private const val RESPONSE_PORT = 2002
        private const val VIDEO_PORT = 5000
        private const val RECEIVE_TIMEOUT_MILLIS = 500
        private const val RECEIVE_BUFFER_BYTES = 262_144
        private const val MAX_RECEIVE_PACKET_BYTES = 16_384
        private const val MAX_CONTROL_PACKET_BYTES = 65_535
        private const val MAX_VIDEO_PACKET_BYTES = 1_212
        private const val RTP_HEADER_MIN_BYTES = 13
        private const val IPV4_BYTES = 4

        fun open(network: Network, bikeAddress: InetAddress): TripperTransport {
            require(bikeAddress.address.size == IPV4_BYTES) { "Tripper address must be IPv4" }
            var control: DatagramSocket? = null
            var video: DatagramSocket? = null
            var receive: DatagramSocket? = null
            try {
                control = DatagramSocket(null).apply { network.bindSocket(this); bind(null) }
                video = DatagramSocket(null).apply { network.bindSocket(this); bind(null) }
                receive = DatagramSocket(null).apply {
                    network.bindSocket(this)
                    bind(InetSocketAddress(RESPONSE_PORT))
                    soTimeout = RECEIVE_TIMEOUT_MILLIS
                    receiveBufferSize = RECEIVE_BUFFER_BYTES
                }
                return TripperTransport(bikeAddress, control, video, receive)
            } catch (error: Throwable) {
                control?.close()
                video?.close()
                receive?.close()
                throw error
            }
        }
    }

}
