package com.eddie.usbmouse

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Localiza el PC sin que el usuario escriba ninguna IP.
 * Lanza un sondeo UDP a toda la subred y se queda con quien conteste.
 * Sirve tanto en anclaje USB (192.168.42.x) como en Wi-Fi.
 */
object Discovery {

    const val PORT = 8778

    fun find(timeoutMs: Int = 1200): Pair<String, Int>? {
        var sock: DatagramSocket? = null
        try {
            val s = DatagramSocket()
            sock = s
            s.broadcast = true
            s.soTimeout = timeoutMs
            val probe = "USBMOUSE?".toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), PORT))

            val buf = ByteArray(64)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val reply = DatagramPacket(buf, buf.size)
                s.receive(reply)
                val text = String(reply.data, 0, reply.length, Charsets.US_ASCII)
                if (text.startsWith("USBMOUSE:")) {
                    val port = text.substringAfter(":").trim().toIntOrNull() ?: 8777
                    val ip = reply.address?.hostAddress ?: continue
                    return ip to port
                }
            }
        } catch (_: Exception) {
            // timeout o red caida: se reintenta en el siguiente ciclo
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        return null
    }
}
