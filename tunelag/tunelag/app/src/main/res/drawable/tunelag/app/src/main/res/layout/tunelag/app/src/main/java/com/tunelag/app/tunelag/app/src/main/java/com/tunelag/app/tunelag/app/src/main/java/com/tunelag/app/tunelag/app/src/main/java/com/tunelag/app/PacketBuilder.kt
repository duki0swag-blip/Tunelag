package com.tunelag.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Construye paquetes IP con checksums correctos.
 * Hace falta para reinyectar respuestas UDP y segmentos TCP en el túnel.
 */
object PacketBuilder {

    private val ipCounter = AtomicInteger(1)

    fun readU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun fold(sum0: Int): Int {
        var s = sum0
        while ((s shr 16) != 0) s = (s and 0xFFFF) + (s shr 16)
        return s.inv() and 0xFFFF
    }

    private fun sumWords(b: ByteArray, off: Int, len: Int): Int {
        var s = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    private fun pseudoHeader(
        srcIp: ByteArray, dstIp: ByteArray, len: Int, proto: Int, ipv6: Boolean
    ): Int {
        var s = sumWords(srcIp, 0, srcIp.size) + sumWords(dstIp, 0, dstIp.size)
        s += len
        s += proto
        if (ipv6) s += 6
        return s
    }

    private fun writeIpHeader(
        p: ByteArray, srcIp: ByteArray, dstIp: ByteArray,
        payloadLen: Int, proto: Int, ipv6: Boolean
    ): Int {
        if (ipv6) {
            p[0] = 0x60
            putU16(p, 4, payloadLen)
            p[6] = proto.toByte()
            p[7] = 64
            System.arraycopy(srcIp, 0, p, 8, 16)
            System.arraycopy(dstIp, 0, p, 24, 16)
            return 40
        }
        p[0] = 0x45
        putU16(p, 2, 20 + payloadLen)
        putU16(p, 4, ipCounter.incrementAndGet() and 0xFFFF)
        putU16(p, 6, 0x4000)
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        putU16(p, 10, fold(sumWords(p, 0, 20)))
        return 20
    }

    fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        payload: ByteArray, ipv6: Boolean
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen = if (ipv6) 40 else 20
        val p = ByteArray(ipLen + udpLen)
        val o = writeIpHeader(p, srcIp, dstIp, udpLen, 17, ipv6)
        putU16(p, o, srcPort)
        putU16(p, o + 2, dstPort)
        putU16(p, o + 4, udpLen)
        putU16(p, o + 6, 0)
        System.arraycopy(payload, 0, p, o + 8, payload.size)
        var cs = fold(pseudoHeader(srcIp, dstIp, udpLen, 17, ipv6) + sumWords(p, o, udpLen))
        if (cs == 0) cs = 0xFFFF
        putU16(p, o + 6, cs)
        return p
    }

    fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray,
        ipv6: Boolean, withMss: Boolean
    ): ByteArray {
        val hdr = if (withMss) 24 else 20
        val ipLen = if (ipv6) 40 else 20
        val p = ByteArray(ipLen + hdr + payload.size)
        val o = writeIpHeader(p, srcIp, dstIp, hdr + payload.size, 6, ipv6)
        putU16(p, o, srcPort)
        putU16(p, o + 2, dstPort)
        putU32(p, o + 4, seq and 0xFFFFFFFFL)
        putU32(p, o + 8, ack and 0xFFFFFFFFL)
        p[o + 12] = ((hdr / 4) shl 4).toByte()
        p[o + 13] = flags.toByte()
        putU16(p, o + 14, 65535)
        putU16(p, o + 16, 0)
        putU16(p, o + 18, 0)
        if (withMss) {
            p[o + 20] = 2
            p[o + 21] = 4
            putU16(p, o + 22, 1460)
        }
        System.arraycopy(payload, 0, p, o + hdr, payload.size)
        putU16(
            p, o + 16,
            fold(pseudoHeader(srcIp, dstIp, hdr + payload.size, 6, ipv6) + sumWords(p, o, hdr + payload.size))
        )
        return p
    }

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10
}
