package com.eddie.usbmouse

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Localiza candidatos a PC sin que el usuario escriba ninguna IP. Vale tanto en
 * anclaje USB (192.168.42.x) como en Wi-Fi.
 *
 * Es una PISTA, no una decision de confianza, y por eso no hay firmas aqui:
 *
 *  - Firmar el sondeo era regalar un verificador. Bastaba con escuchar el
 *    broadcast una vez para llevarse `hmac(codigo, nonce)` a casa y probar
 *    codigos sin limite y sin volver a tocar la red; el freno del servidor no
 *    pinta nada contra eso.
 *  - Firmar la respuesta tampoco servia, porque la firma no ataba la IP: un
 *    intermediario reenviaba el sondeo al PC de verdad y devolvia su respuesta
 *    valida desde su propia direccion.
 *
 * Quien decide de verdad es el apreton de manos del TCP, que es mutuo y ademas
 * deja la sesion cifrada y firmada. Un impostor que conteste aqui no consigue
 * nada: no puede completar el apreton, y reenviar el de otro no le sirve porque
 * las claves de sesion salen de los dos numeros aleatorios y el no los controla.
 */
object Discovery {

    const val PORT = 8778

    /** Hasta [max] direcciones que dicen tener un servidor. Se prueban por orden. */
    fun find(timeoutMs: Int = 1200, max: Int = 4): List<Pair<String, Int>> {
        val salida = ArrayList<Pair<String, Int>>(max)
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
            while (System.currentTimeMillis() < deadline && salida.size < max) {
                val reply = DatagramPacket(buf, buf.size)
                s.receive(reply)
                val text = String(reply.data, 0, reply.length, Charsets.US_ASCII).trim()
                if (!text.startsWith("USBMOUSE:")) continue
                val port = text.substringAfter(":").trim().toIntOrNull() ?: continue
                if (port !in 1..65535) continue
                val ip = reply.address?.hostAddress ?: continue
                // Se recogen VARIOS: si un impostor contesta antes, el PC de
                // verdad sigue en la lista y el apreton descarta al otro.
                if (salida.none { it.first == ip && it.second == port }) salida.add(ip to port)
            }
        } catch (_: Exception) {
            // timeout o red caida: se reintenta en el siguiente ciclo
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
        return salida
    }
}
