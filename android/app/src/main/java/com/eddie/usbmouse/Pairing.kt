package com.eddie.usbmouse

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Emparejamiento del enlace por cable.
 *
 * Hacia falta porque lo que viaja por ese socket son pulsaciones de teclado, o
 * sea ejecucion de codigo en el PC. Sin esto, cualquiera en la misma Wi-Fi podia
 * abrir el puerto 8777 a mano y escribir lo que quisiera; y por el tunel de adb
 * podia hacerlo cualquier app del movil con solo permiso de INTERNET.
 *
 * El codigo NUNCA viaja por la red. Los dos lados se demuestran que lo conocen
 * firmando el numero aleatorio del otro (HMAC-SHA256), y es **mutuo** a
 * proposito: el movil tiene que comprobar al PC igual que el PC al movil, porque
 * si no, quien conteste antes al sondeo UDP se lleva todo lo que se teclee,
 * contrasenas incluidas.
 */
object Pairing {

    /**
     * Alfabeto sin I, L, O ni U: las cuatro que se confunden al copiar un codigo
     * a mano (1/I/l, 0/O) o forman palabras que no queremos generar por azar.
     */
    private const val ABC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 12 simbolos de 32 = 60 bits. De sobra, y se teclea una sola vez. */
    const val LARGO = 12

    private val rnd = SecureRandom()

    /**
     * Deja el codigo como lo espera el otro lado: mayusculas, sin guiones ni
     * espacios, y con las confusiones tipicas corregidas. Asi da igual que se
     * escriba "abcd-efgh-jkmn" o "ABCDEFGHJKMN".
     */
    fun normal(raw: String): String {
        val sb = StringBuilder(LARGO)
        for (ch in raw.uppercase()) {
            val c = when (ch) {
                'I', 'L' -> '1'
                'O' -> '0'
                'U' -> 'V'
                else -> ch
            }
            if (ABC.indexOf(c) >= 0) sb.append(c)
        }
        return sb.toString()
    }

    /** Con guiones cada cuatro, que es como se lee sin perder el sitio. */
    fun bonito(code: String): String =
        code.chunked(4).joinToString("-")

    fun hmac(code: String, msg: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return m.doFinal(msg.toByteArray(Charsets.UTF_8))
    }

    fun hex(b: ByteArray): String {
        val s = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            s.append("0123456789abcdef"[v ushr 4])
            s.append("0123456789abcdef"[v and 0xF])
        }
        return s.toString()
    }

    fun nonce(): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return hex(b)
    }

    /** Comparacion en tiempo constante: `==` sobre el hex filtra el prefijo acertado. */
    fun igual(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /**
     * Lee una linea acotada. Sin el tope, un servidor falso que no mande nunca
     * un fin de linea nos hace crecer el buffer hasta quedarnos sin memoria.
     */
    fun linea(inp: InputStream, max: Int = 512): String? {
        val buf = StringBuilder(64)
        while (buf.length < max) {
            val c = inp.read()
            if (c < 0) return if (buf.isEmpty()) null else buf.toString()
            if (c == '\n'.code) return buf.toString().trimEnd('\r')
            buf.append(c.toChar())
        }
        return null
    }

    /**
     * Apreton de manos mutuo. Devuelve true solo si los dos lados han probado
     * que conocen el codigo. El socket debe traer ya su `soTimeout` puesto: un
     * servidor que se queda callado no puede dejarnos colgados.
     *
     *   PC  -> movil   U1 <nonceS>
     *   movil -> PC    A <hmac(S:nonceS:nonceC)> <nonceC>
     *   PC  -> movil   B <hmac(C:nonceC:nonceS)>
     */
    fun handshake(inp: InputStream, out: OutputStream, code: String): Boolean {
        val saludo = linea(inp) ?: return false
        val p = saludo.trim().split(" ")
        if (p.size != 2 || p[0] != "U1") return false
        val nonceS = p[1]
        if (nonceS.length != 32) return false

        val nonceC = nonce()
        val mio = hex(hmac(code, "S:$nonceS:$nonceC"))
        out.write("A $mio $nonceC\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        val resp = linea(inp) ?: return false
        val q = resp.trim().split(" ")
        if (q.size != 2 || q[0] != "B") return false
        return igual(q[1], hex(hmac(code, "C:$nonceC:$nonceS")))
    }
}
