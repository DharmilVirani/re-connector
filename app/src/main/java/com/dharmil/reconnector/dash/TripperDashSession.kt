package com.dharmil.reconnector.dash

import androidx.annotation.WorkerThread
import com.dharmil.reconnector.dash.video.EncodedFrame
import com.dharmil.reconnector.dash.video.RtpPacketizer
import com.dharmil.reconnector.navigation.RoutePlan
import com.dharmil.reconnector.navigation.RouteProgress
import java.io.IOException
import java.security.SecureRandom

class TripperDashSession(
    private val ssid: String,
    private val transport: TripperTransport,
) {
    private val controlSendLock = Any()
    private var sequence = 0
    private val packetizer = RtpPacketizer()
    @Volatile private var streaming = false

    @WorkerThread
    fun authenticate(timeoutMillis: Long = AUTH_TIMEOUT_MILLIS) {
        require(ssid.isNotBlank())
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        var modulus: ByteArray? = null
        var exponent: ByteArray? = null
        var keySent = false
        send(TripperProtocol::authenticationRequest)

        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / NANOS_PER_MILLI)
                .coerceAtLeast(1L).coerceAtMost(MAX_RECEIVE_WAIT_MILLIS.toLong()).toInt()
            val incoming = try {
                transport.receiveControl(remaining)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (error: IOException) {
                throw error
            }
            if (!transport.isFromBike(incoming)) continue
            val segments = runCatching { TripperProtocol.parse(incoming.bytes) }.getOrNull() ?: continue
            for (segment in segments) {
                when {
                    segment.type == AUTH_TYPE && segment.subtype == AUTH_MODULUS_SUBTYPE -> modulus = segment.payload
                    segment.type == AUTH_TYPE && segment.subtype == AUTH_EXPONENT_SUBTYPE -> exponent = segment.payload
                    segment.type == AUTH_TYPE && segment.subtype == AUTH_STATUS_SUBTYPE -> {
                        val status = segment.payload.firstOrNull()?.toInt()?.and(0xFF)
                        if (status == AUTH_SUCCESS) return
                        if (status != null) throw IOException("Tripper authentication was rejected")
                    }
                    segment.type == FRAME_TYPE && segment.subtype == FRAME_DECODED_SUBTYPE ->
                        if (segment.payload.lastOrNull()?.toInt()?.and(0xFF) == PROJECTION_ENABLED) {
                        send(TripperProtocol::frameDecodedAck)
                    }
                }
            }
            if (!keySent && modulus != null && exponent != null) {
                val sessionKey = ByteArray(SESSION_KEY_BYTES).also(SECURE_RANDOM::nextBytes)
                try {
                    send { seq ->
                        TripperProtocol.authenticationPacket(ssid, checkNotNull(modulus), checkNotNull(exponent), sessionKey, seq)
                    }
                    keySent = true
                } finally {
                    sessionKey.fill(0)
                }
            }
        }
        throw IOException("Tripper authentication timed out")
    }

    @WorkerThread
    fun beginNavigation(route: RoutePlan, progress: RouteProgress?) {
        require(progress == null || route.id == progress.routeId)
        send(TripperProtocol::navigationContext)
        send(TripperProtocol::emptyRouteLists)
        repeat(ROUTE_CARD_PRESTART_REPEATS) { index ->
            send { seq -> TripperProtocol.routeCard(route.destinationName, seq) }
            if (index < ROUTE_CARD_PRESTART_REPEATS - 1) Thread.sleep(ROUTE_CARD_INTERVAL_MILLIS)
        }
        send(TripperProtocol::projectionFrame)
        send(TripperProtocol::startNavigation)
        send { seq -> TripperProtocol.routeCard(route.destinationName, seq, projectionEnabled = true) }
        sendNavigationInfo(route, progress)
        streaming = true
    }

    @WorkerThread
    fun sendEncodedFrame(frame: EncodedFrame) {
        if (!streaming) return
        send(TripperProtocol::projectionFrame)
        packetizer.packetize(frame, transport::sendVideo)
    }

    @WorkerThread
    fun sendNavigationInfo(route: RoutePlan, progress: RouteProgress?) {
        require(progress == null || route.id == progress.routeId)
        val nextManeuver = progress?.let { route.maneuvers.getOrNull(it.nextManeuverIndex) }
        val distanceToTurn = if (progress == null || nextManeuver == null) {
            0
        } else {
            (route.distanceAt(nextManeuver.pointIndex) - progress.distanceAlongRouteMeters)
                .coerceAtLeast(0.0).toInt()
        }
        val remaining = progress?.remainingMeters?.coerceAtLeast(0.0)?.toInt() ?: 0
        send { seq ->
            TripperProtocol.activeNavigation(
                sequence = seq,
                maneuverCode = CONTINUE_MANEUVER_CODE,
                distanceToManeuverMeters = distanceToTurn,
                remainingDistanceMeters = remaining,
            )
        }
    }

    @WorkerThread
    fun keepRouteCardAlive(route: RoutePlan) {
        if (streaming) send { seq -> TripperProtocol.routeCard(route.destinationName, seq, projectionEnabled = true) }
    }

    @WorkerThread
    fun handleIncoming(packet: ReceivedControlPacket) {
        if (!transport.isFromBike(packet)) return
        val segments = runCatching { TripperProtocol.parse(packet.bytes) }.getOrNull() ?: return
        for (segment in segments) {
            if (segment.type == FRAME_TYPE && segment.subtype == FRAME_DECODED_SUBTYPE &&
                segment.payload.lastOrNull()?.toInt()?.and(0xFF) == PROJECTION_ENABLED
            ) {
                send(TripperProtocol::frameDecodedAck)
            }
        }
    }

    @WorkerThread
    fun stopProjection() {
        streaming = false
        send(TripperProtocol::projectionStop)
        send(TripperProtocol::projectionOff)
    }

    private fun send(packetFactory: (Int) -> ByteArray) = synchronized(controlSendLock) {
        val currentSequence = sequence
        sequence = (sequence + 1) and SEQUENCE_MASK
        transport.sendControl(packetFactory(currentSequence))
    }

    private companion object {
        const val AUTH_TYPE = 0x07
        const val AUTH_MODULUS_SUBTYPE = 0x00
        const val AUTH_EXPONENT_SUBTYPE = 0x03
        const val AUTH_STATUS_SUBTYPE = 0x01
        const val FRAME_TYPE = 0x09
        const val FRAME_DECODED_SUBTYPE = 0x06
        const val AUTH_SUCCESS = 0x01
        const val PROJECTION_ENABLED = 0x55
        const val SESSION_KEY_BYTES = 32
        const val MAX_RECEIVE_WAIT_MILLIS = 500
        const val AUTH_TIMEOUT_MILLIS = 8_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val ROUTE_CARD_PRESTART_REPEATS = 4
        const val ROUTE_CARD_INTERVAL_MILLIS = 350L
        const val CONTINUE_MANEUVER_CODE = 0x0B
        const val SEQUENCE_MASK = 0xFF
        val SECURE_RANDOM = SecureRandom()
    }
}
