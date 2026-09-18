package com.eddie.usbmouse

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Localiza el PC sin que el usuario escriba ninguna IP.
 * Lanza un sondeo UDP a toda la subred y se queda con quien conteste.
 * Sirve tanto en anclaje USB (192.168.42.x) como en Wi-Fi.
 *
 * El sondeo va firmado y la respuesta tambien, las dos con el codigo de
 * emparejamiento:
 *
 *  - Firmar la respuesta es lo que impide que un impostor que conteste antes que
 *    el PC se lleve la conexion y con ella todo lo que se teclee.
 *  - Firmar el sondeo es lo que impide que el PC sea un oraculo: si contestara a
 *    cualquiera con una firma, bastaria pedirsela una vez y romper el codigo sin
 *    prisa y sin volver a tocar la red.
 */
object Discovery {

    const val PORT = 8778

    fun find(code: String, timeoutMs: Int = 1200): Pair<String, Int>? {
        if (code.length < Pairing.LARGO) return null
        var sock: DatagramSocket? = null
        try {
            val s = DatagramSocket()
            sock = s
            s.broadcast = true
            s.soTimeout = timeoutMs
            val nonce = Pairing.nonce()
            val firma = Pairing.hex(Pairing.hmac(code, "D:$nonce"))
            val probe = "USBMOUSE? $nonce $firma".toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), PORT))

            val buf = ByteArray(160)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val reply = DatagramPacket(buf, buf.size)
                s.receive(reply)
                val text = String(reply.data, 0, reply.length, Charsets.US_ASCII).trim()
                if (!text.startsWith("USBMOUSE:")) continue
                // "USBMOUSE:<puerto> <firma>"
                val trozos = text.split(" ")
                if (trozos.size != 2) continue
                val port = trozos[0].substringAfter(":").trim().toIntOrNull() ?: continue
                if (port !in 1..65535) continue
                // Se sigue escuchando en vez de rendirse: en una red con un
                // impostor, su respuesta llega antes pero la del PC llega.
                if (!Pairing.igual(trozos[1], Pairing.hex(Pairing.hmac(code, "R:$nonce:$port")))) {
                    continue
                }
                val ip = reply.address?.hostAddress ?: continue
                return ip to port
            }
        } catch (_: Exception) {
            // timeout o red caida: se reintenta en el siguiente ciclo
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        return null
    }
}
