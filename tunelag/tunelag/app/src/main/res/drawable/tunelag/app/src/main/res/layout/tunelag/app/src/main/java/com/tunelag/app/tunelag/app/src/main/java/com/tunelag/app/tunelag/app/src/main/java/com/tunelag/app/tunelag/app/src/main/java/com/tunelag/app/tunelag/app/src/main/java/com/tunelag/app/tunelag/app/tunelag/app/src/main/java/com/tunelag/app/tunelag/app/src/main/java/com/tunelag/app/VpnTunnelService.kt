package com.tunelag.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Túnel local. Solo entra el tráfico del juego, y solo se descarta su UDP
 * hacia los servidores detectados, durante ráfagas de 250-800 ms.
 */
class VpnTunnelService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var tunReader: FileInputStream? = null
    private var tunWriter: FileOutputStream? = null
    private val writerLock = Any()

    @Volatile private var alive = false
    @Volatile private var lagOn = false
    @Volatile private var burstLocked = false
    @Volatile private var lastBurstMs = 0

    private lateinit var finder: GameTrafficFinder
    private lateinit var bubble: FloatingBubble
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()

    private inner class UdpFlow(
        val socket: DatagramSocket,
        val clientIp: ByteArray, val clientPort: Int,
        val remoteIp: ByteArray, val remotePort: Int,
        val ipv6: Boolean
    )

    private inner class TcpFlow(
        val clientIp: ByteArray, val clientPort: Int,
        val remoteIp: ByteArray, val remotePort: Int,
        val ipv6: Boolean,
        val clientIsn: Long
    ) {
        @Volatile var socket: Socket? = null
        val ourIsn: Long = (Random.nextInt() and 0x7FFFFFFF).toLong()
        @Volatile var sentBytes = 0L
        @Volatile var receivedBytes = 0L
        @Volatile var done = false
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        finder = GameTrafficFinder(this)
        finder.resolvePackages()
        bubble = FloatingBubble(
            this,
            onTap = { toggleLag() },
            onLongPress = {
                AppSettings.get(this).edit().putBoolean(AppSettings.KEY_BUBBLE, false).apply()
                bubble.remove()
                EventLog.record("burbuja oculta")
            }
        )
        alive = true
        RuntimeState.tunnelUp = true
        EventLog.record("servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LAG -> toggleLag()
            ACTION_TOGGLE_BUBBLE -> {
                val on = !AppSettings.get(this).getBoolean(AppSettings.KEY_BUBBLE, false)
                AppSettings.get(this).edit().putBoolean(AppSettings.KEY_BUBBLE, on).apply()
                if (on) bubble.show() else bubble.remove()
                refreshNotification()
            }
            ACTION_REFRESH -> {
                refreshNotification()
                return START_STICKY
            }
        }

        if (tunFd == null) {
            if (!openTunnel()) {
                stopSelf()
                return START_NOT_STICKY
            }
            thread(isDaemon = true) { readLoop() }
            thread(isDaemon = true) { discoverLoop() }
            thread(isDaemon = true) { autoLoop() }
            if (AppSettings.get(this).getBoolean(AppSettings.KEY_BUBBLE, false) &&
                Settings.canDrawOverlays(this)
            ) {
                bubble.show()
            }
            if (AppSettings.get(this).getBoolean(AppSettings.KEY_AUTO, false)) toggleLag()
        }
        return START_STICKY
    }

    private fun openTunnel(): Boolean {
        try {
            val builder = Builder()
                .setSession("TunelLag")
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0/0", 0)
                .addAddress("fd00:8::2", 128)
                .addRoute("::/0", 0)
                .setMtu(1500)
            var scoped = false
            for (pkg in GameTrafficFinder.GAME_PACKAGES) {
                try {
                    builder.addAllowedApplication(pkg)
                    scoped = true
                } catch (_: Exception) {
                }
            }
            if (!scoped) EventLog.record("sin juego: se tuneliza todo")
            val fd = builder.establish() ?: run {
                EventLog.record("establish() denegado")
                return false
            }
            tunFd = fd
            tunReader = FileInputStream(fd.fileDescriptor)
            tunWriter = FileOutputStream(fd.fileDescriptor)
            EventLog.record("túnel establecido")
            return true
        } catch (e: Exception) {
            EventLog.record("fallo al establecer: ${e.message}")
            return false
        }
    }

    private fun discoverLoop() {
        while (alive) {
            finder.collect()
            RuntimeState.foundEndpoints = finder.found.size
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun autoLoop() {
        while (alive) {
            if (!AppSettings.get(this).getBoolean(AppSettings.KEY_AUTO, false)) {
                pause(Random.nextLong(1500, 2500))
                continue
            }
            if (!lagOn) burst()
            pause(Random.nextLong(4000, 15000))
        }
    }

    private fun applyLag(on: Boolean) {
        lagOn = on
        RuntimeState.lagActive = on
        bubble.markActive(on)
        refreshNotification()
    }

    private fun burst() {
        if (burstLocked) return
        burstLocked = true
        val range = AppSettings.burstRange(this)
        lastBurstMs = Random.nextInt(range.first, range.last + 1).coerceAtMost(800)
        applyLag(true)
        try {
            Thread.sleep(lastBurstMs.toLong())
        } catch (_: InterruptedException) {
        }
        applyLag(false)
        burstLocked = false
    }

    fun toggleLag() {
        if (lagOn) {
            applyLag(false)
            burstLocked = false
            return
        }
        if (burstLocked) return
        burstLocked = true
        thread(isDaemon = true) {
            val range = AppSettings.burstRange(this)
            lastBurstMs = Random.nextInt(range.first, range.last + 1).coerceAtMost(800)
            applyLag(true)
            Thread.sleep(lastBurstMs.toLong())
            applyLag(false)
            burstLocked = false
        }
    }

    private fun readLoop() {
        val buf = ByteArray(65536)
        while (alive) {
            val n = try {
                tunReader?.read(buf) ?: -1
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) {
                if (!alive) break
                continue
            }
            val pkt = buf.copyOf(n)
            try {
                when ((pkt[0].toInt() shr 4) and 0xF) {
                    4 -> handleIpv4(pkt)
                    6 -> handleIpv6(pkt)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleIpv4(p: ByteArray) {
        if (p.size < 28) return
        val headerLen = (p[0].toInt() and 0x0F) * 4
        if (headerLen < 20 || p.size < headerLen + 8) return
        when (p[9].toInt() and 0xFF) {
            17 -> handleUdp(p, headerLen, p.copyOfRange(12, 16), p.copyOfRange(16, 20), false)
            6 -> handleTcp(p, headerLen, p.copyOfRange(12, 16), p.copyOfRange(16, 20), false)
        }
    }

    private fun handleIpv6(p: ByteArray) {
        if (p.size < 48) return
        when (p[6].toInt() and 0xFF) {
            17 -> handleUdp(p, 40, p.copyOfRange(8, 24), p.copyOfRange(24, 40), true)
            6 -> handleTcp(p, 40, p.copyOfRange(8, 24), p.copyOfRange(24, 40), true)
        }
    }

    private fun ipToString(a: ByteArray): String = try {
        InetAddress.getByAddress(a).hostAddress ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun isGameEndpoint(ip: String, port: Int): Boolean {
        if (finder.found.isEmpty()) return port >= 1024 && port != 53
        return finder.found.contains("$ip:$port")
    }

    // ---------------- UDP ----------------

    private fun handleUdp(
        p: ByteArray, off: Int, srcIp: ByteArray, dstIp: ByteArray, ipv6: Boolean
    ) {
        val srcPort = PacketBuilder.readU16(p, off)
        val dstPort = PacketBuilder.readU16(p, off + 2)
        val dst = ipToString(dstIp)
        if (lagOn && isGameEndpoint(dst, dstPort)) return

        val payload = p.copyOfRange(off + 8, p.size)
        val key = "${ipToString(srcIp)}:$srcPort>$dst:$dstPort"
        var flow = udpFlows[key]
        if (flow == null) {
            val s = DatagramSocket()
            protect(s)
            flow = UdpFlow(s, srcIp, srcPort, dstIp, dstPort, ipv6)
            udpFlows[key] = flow
            val ref = flow
            thread(isDaemon = true) { readUdpReplies(ref) }
        }
        try {
            flow.socket.send(
                DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), dstPort)
            )
        } catch (e: Exception) {
            udpFlows.remove(key)
            flow.socket.close()
        }
    }

    private fun readUdpReplies(f: UdpFlow) {
        val buf = ByteArray(65536)
        while (alive && !f.socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                f.socket.receive(packet)
            } catch (e: Exception) {
                break
            }
            val remote = ipToString(f.remoteIp)
            if (AppSettings.get(this).getBoolean(AppSettings.KEY_BIDI, false) && lagOn &&
                isGameEndpoint(remote, f.remotePort)
            ) continue
            val out = PacketBuilder.buildUdpPacket(
                f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                buf.copyOfRange(packet.offset, packet.offset + packet.length), f.ipv6
            )
            writeTunnel(out)
        }
    }

    // ---------------- TCP ----------------

    private fun handleTcp(
        p: ByteArray, off: Int, srcIp: ByteArray, dstIp: ByteArray, ipv6: Boolean
    ) {
        val srcPort = PacketBuilder.readU16(p, off)
        val dstPort = PacketBuilder.readU16(p, off + 2)
        val flags = p[off + 13].toInt() and 0xFF
        val seq = readU32(p, off + 4)
        val key = "${ipToString(srcIp)}:$srcPort>${ipToString(dstIp)}:$dstPort"

        if (flags and PacketBuilder.FLAG_SYN != 0 && flags and PacketBuilder.FLAG_ACK == 0) {
            val flow = TcpFlow(srcIp, srcPort, dstIp, dstPort, ipv6, seq)
            tcpFlows[key] = flow
            thread(isDaemon = true) { connectTcp(flow, key) }
            return
        }

        val flow = tcpFlows[key] ?: return
        if (flags and PacketBuilder.FLAG_RST != 0) {
            tcpFlows.remove(key)
            releaseTcp(flow)
            return
        }

        val dataOffset = ((p[off + 12].toInt() shr 4) and 0xF) * 4
        if (p.size > off + dataOffset) {
            val data = p.copyOfRange(off + dataOffset, p.size)
            if (data.isNotEmpty()) {
                try {
                    flow.socket?.outputStream?.write(data)
                    flow.receivedBytes += data.size
                    writeTunnel(
                        PacketBuilder.buildTcpPacket(
                            flow.remoteIp, flow.clientIp, flow.remotePort, flow.clientPort,
                            flow.ourIsn + 1 + flow.sentBytes, flow.clientIsn + 1 + flow.receivedBytes,
                            PacketBuilder.FLAG_ACK, EMPTY, ipv6, false
                        )
                    )
                } catch (e: Exception) {
                    tcpFlows.remove(key)
                    releaseTcp(flow)
                }
            }
        }

        if (flags and PacketBuilder.FLAG_FIN != 0) {
            tcpFlows.remove(key)
            try {
                writeTunnel(
                    PacketBuilder.buildTcpPacket(
                        flow.remoteIp, flow.clientIp, flow.remotePort, flow.clientPort,
                        flow.ourIsn + 1 + flow.sentBytes, flow.clientIsn + 1 + flow.receivedBytes,
                        PacketBuilder.FLAG_ACK or PacketBuilder.FLAG_FIN, EMPTY, ipv6, false
                    )
                )
            } catch (_: Exception) {
            }
            releaseTcp(flow)
        }
    }

    private fun connectTcp(flow: TcpFlow, key: String) {
        val socket = Socket()
        try {
            protect(socket)
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(InetAddress.getByAddress(flow.remoteIp), flow.remotePort), 8000
            )
            flow.socket = socket
            writeTunnel(
                PacketBuilder.buildTcpPacket(
                    flow.remoteIp, flow.clientIp, flow.remotePort, flow.clientPort,
                    flow.ourIsn, flow.clientIsn + 1,
                    PacketBuilder.FLAG_SYN or PacketBuilder.FLAG_ACK, EMPTY, flow.ipv6, true
                )
            )
        } catch (e: Exception) {
            writeTunnel(
                PacketBuilder.buildTcpPacket(
                    flow.remoteIp, flow.clientIp, flow.remotePort, flow.clientPort,
                    0, flow.clientIsn + 1,
                    PacketBuilder.FLAG_RST or PacketBuilder.FLAG_ACK, EMPTY, flow.ipv6, false
                )
            )
            tcpFlows.remove(key)
            releaseTcp(flow)
            return
        }

        val buf = ByteArray(16384)
        while (alive && !flow.done) {
            val n = try {
                socket.inputStream.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) break
            val segment = PacketBuilder.buildTcpPacket(
                flow.remoteIp, flow.clientIp, flow.remotePort, flow.clientPort,
                flow.ourIsn + 1 + flow.sentBytes, flow.clientIsn + 1 + flow.receivedBytes,
                PacketBuilder.FLAG_ACK or PacketBuilder.FLAG_PSH, buf.copyOf(n), flow.ipv6, false
            )
            flow.sentBytes += n
            writeTunnel(segment)
        }
        if (!flow.done) {
            try {
                writeTunnel(
                    PacketBuilder.buildTcpPacket(
                        flow.remoteIp, flow.clientIp, flow.remotePort, flow.clientPort,
                        flow.ourIsn + 1 + flow.sentBytes, flow.clientIsn + 1 + flow.receivedBytes,
                        PacketBuilder.FLAG_ACK or PacketBuilder.FLAG_FIN, EMPTY, flow.ipv6, false
                    )
                )
            } catch (_: Exception) {
            }
        }
        tcpFlows.remove(key)
        releaseTcp(flow)
    }

    // ---------------- utilidades ----------------

    private fun readU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun writeTunnel(pkt: ByteArray) {
        try {
            synchronized(writerLock) { tunWriter?.write(pkt) }
        } catch (_: Exception) {
        }
    }

    private fun releaseTcp(flow: TcpFlow) {
        flow.done = true
        try {
            flow.socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    // ---------------- notificación ----------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TunelLag", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainScreen::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, VpnTunnelService::class.java).setAction(ACTION_TOGGLE_LAG),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, VpnTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            lagOn -> "Cortando ${lastBurstMs}ms"
            RuntimeState.foundEndpoints > 0 -> "Listo · ${RuntimeState.foundEndpoints} endpoints"
            else -> "Esperando tráfico del juego"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TunelLag")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Lag", toggle)
            .addAction(0, "Parar", stop)
            .build()
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        RuntimeState.tunnelUp = false
        RuntimeState.lagActive = false
        lagOn = false
        bubble.remove()
        for ((_, f) in udpFlows) {
            try {
                f.socket.close()
            } catch (_: Exception) {
            }
        }
        for ((_, f) in tcpFlows) releaseTcp(f)
        udpFlows.clear()
        tcpFlows.clear()
        try {
            tunReader?.close()
            tunWriter?.close()
            tunFd?.close()
        } catch (_: Exception) {
        }
        tunFd = null
        EventLog.record("servicio detenido")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.tunelag.app.STOP"
        const val ACTION_TOGGLE_LAG = "com.tunelag.app.TOGGLE"
        const val ACTION_TOGGLE_BUBBLE = "com.tunelag.app.BUBBLE"
        const val ACTION_REFRESH = "com.tunelag.app.REFRESH"
        private const val CHANNEL_ID = "tunelag"
        private const val NOTIFICATION_ID = 41
        private val EMPTY = ByteArray(0)
    }
}
