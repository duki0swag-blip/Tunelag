package com.tunelag.app

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Localiza los servidores del juego leyendo /proc/net/udp por UID.
 * Sin root. Si la ROM restringe /proc, el servicio cae al filtro por puerto.
 */
class GameTrafficFinder(private val ctx: Context) {

    private val gameUids = ArrayList<Int>()
    val found = HashSet<String>()

    fun resolvePackages() {
        val pm = ctx.packageManager
        for (pkg in GAME_PACKAGES) {
            try {
                gameUids.add(pm.getApplicationInfo(pkg, 0).uid)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        if (gameUids.isEmpty()) EventLog.record("juego no instalado o no visible")
    }

    fun collect() {
        if (gameUids.isEmpty()) return
        val batch = HashSet<String>()
        for (path in arrayOf("/proc/net/udp", "/proc/net/udp6")) {
            val f = File(path)
            if (!f.canRead()) continue
            try {
                f.useLines { rows ->
                    rows.drop(1).forEach { row ->
                        val cols = row.trim().split(Regex("\\s+"))
                        if (cols.size < 8) return@forEach
                        val uid = cols[7].toIntOrNull() ?: return@forEach
                        if (uid !in gameUids) return@forEach
                        val remote = cols[2]
                        val sep = remote.indexOf(':')
                        if (sep <= 0) return@forEach
                        val port = remote.substring(sep + 1).toIntOrNull(16) ?: return@forEach
                        if (port == 0) return@forEach
                        val ip = decodeHexIp(remote.substring(0, sep)) ?: return@forEach
                        batch.add("$ip:$port")
                    }
                }
            } catch (_: Exception) {
            }
        }
        synchronized(found) {
            found.clear()
            found.addAll(batch)
        }
    }

    private fun decodeHexIp(hex: String): String? {
        try {
            if (hex.length == 8) {
                val bytes = ByteArray(4)
                for (i in 0 until 4) {
                    bytes[i] = hex.substring((3 - i) * 2, (3 - i) * 2 + 2).toInt(16).toByte()
                }
                return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}." +
                    "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
            }
            if (hex.length == 32) {
                val sb = StringBuilder()
                for (word in 0 until 4) {
                    for (byteIdx in 0 until 4) {
                        val part = hex.substring(word * 8 + (3 - byteIdx) * 2, word * 8 + (3 - byteIdx) * 2 + 2)
                        if (sb.isNotEmpty()) sb.append(':')
                        sb.append(part.padStart(4, '0'))
                    }
                }
                return sb.toString()
            }
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        val GAME_PACKAGES = arrayOf("com.dts.freefireth", "com.dts.freefiremax")
    }
}
