package com.eddie.usbmouse

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Emparejado y sesion del enlace por cable.
 *
 * Por ese socket viajan pulsaciones de teclado, o sea ejecucion de codigo en el
 * PC, y ademas TU TEXTO: contrasenas incluidas. Asi que no basta con saber quien
 * llama, hace falta que nadie por el medio pueda leer ni colar nada.
 *
 *  1. La clave sale del codigo por un KDF lento ([KDF_VUELTAS] vueltas de
 *     HMAC-SHA256). Un codigo de 60 bits usado como clave en crudo se puede
 *     romper a martillazos; estirado, no.
 *  2. El apreton es mutuo: los dos firman el numero aleatorio del otro y los dos
 *     comprueban.
 *  3. De ese apreton salen las claves de sesion, distintas por sentido. Todo lo
 *     que viene despues va cifrado y firmado con numero de orden.
 *
 * El punto 3 es el que cierra al hombre en medio: antes, quien se colara entre
 * los dos leia el texto tal cual y podia inyectar sus propias lineas sin saber
 * el codigo, porque solo se autenticaba el saludo y no lo que venia detras.
 */
object Pairing {

    /** Alfabeto sin I, L, O ni U: las que se confunden al copiar a mano. */
    private const val ABC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 12 simbolos de 32 = 60 bits. */
    const val LARGO = 12

    /** Lo que estira el codigo. Unas decimas de segundo aqui son anos alli. */
    private const val KDF_VUELTAS = 200_000
    private const val KDF_SAL = "usbmouse-kdf-v1"

    /** Tope de una linea de protocolo en claro. `K<texto>` es la unica larga. */
    const val MAX_LINEA = 1024

    private val rnd = SecureRandom()

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

    fun bonito(code: String): String = code.chunked(4).joinToString("-")

    /**
     * Vale de un solo uso: 16 en hexadecimal, tal cual lo genera el servidor.
     * No se normaliza —la clave sale de esos bytes exactos— y por eso hay que
     * distinguirlo de un codigo, que si se normaliza.
     */
    fun esVale(s: String): Boolean =
        s.length == 16 && s.all { it in '0'..'9' || it in 'a'..'f' }

    // ---------------------------------------------------------------- claves

    private fun mac(key: ByteArray, msg: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(msg)
    }

    fun hmac(key: ByteArray, msg: String): ByteArray = mac(key, msg.toByteArray(Charsets.UTF_8))

    private var cacheCode: String? = null
    private var cacheKey: ByteArray? = null

    /**
     * Estira el codigo. Se cachea porque tarda a proposito: repetirlo en cada
     * reintento del bucle de conexion pondria el movil a hervir.
     */
    @Synchronized
    fun clave(code: String): ByteArray {
        cacheKey?.let { if (cacheCode == code) return it }
        var k = code.toByteArray(Charsets.UTF_8) + KDF_SAL.toByteArray(Charsets.UTF_8)
        val sal = KDF_SAL.toByteArray(Charsets.UTF_8)
        repeat(KDF_VUELTAS) { k = mac(k, sal) }
        cacheCode = code
        cacheKey = k
        return k
    }

    fun hex(b: ByteArray, n: Int = b.size): String {
        val s = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            s.append("0123456789abcdef"[v ushr 4])
            s.append("0123456789abcdef"[v and 0xF])
        }
        return s.toString()
    }

    fun deHex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val a = Character.digit(s[i * 2], 16)
            val b = Character.digit(s[i * 2 + 1], 16)
            if (a < 0 || b < 0) return null
            out[i] = ((a shl 4) or b).toByte()
        }
        return out
    }

    fun nonce(): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return hex(b)
    }

    fun igual(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /** Lee una linea acotada: un servidor mudo no puede dejarnos sin memoria. */
    fun linea(inp: InputStream, max: Int = 4096): String? {
        val buf = StringBuilder(64)
        while (buf.length < max) {
            val c = inp.read()
            if (c < 0) return if (buf.isEmpty()) null else buf.toString()
            if (c == '\n'.code) return buf.toString().trimEnd('\r')
            buf.append(c.toChar())
        }
        return null
    }

    // ---------------------------------------------------------------- sesion

    /**
     * Sobre cerrado: cifra y firma cada linea con su numero de orden.
     *
     * El cifrado es un flujo hecho con HMAC-SHA256 como funcion pseudoaleatoria
     * (bloque i = HMAC(k, orden || i)), y encima va un HMAC aparte sobre el
     * criptograma — cifrar y luego firmar, en ese orden, que es el unico que no
     * tiene sorpresas. Las claves de los dos sentidos son distintas, asi que una
     * linea del PC no se puede devolver como si fuera del movil.
     *
     * Sin asignaciones por linea: los buffers y los dos Mac se reutilizan, que
     * esto lo atraviesa cada movimiento del dedo.
     */
    class Cerrador(kEnc: ByteArray, kMac: ByteArray) {
        private val enc = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(kEnc, "HmacSHA256")) }
        private val firm = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(kMac, "HmacSHA256")) }

        private var orden = 0L
        private val ctr = ByteArray(5)
        private val ks = ByteArray(32)
        private val ct = ByteArray(MAX_LINEA)
        private val tag = ByteArray(32)
        // "<orden 8> <ct hex> <tag 32 hex>\n"
        private val out = ByteArray(9 + MAX_LINEA * 2 + 1 + 32 + 1)

        private fun contador(n: Long, bloque: Int) {
            ctr[0] = (n ushr 24).toByte(); ctr[1] = (n ushr 16).toByte()
            ctr[2] = (n ushr 8).toByte(); ctr[3] = n.toByte()
            ctr[4] = bloque.toByte()
        }

        private fun escribeHex(dst: ByteArray, pos: Int, src: ByteArray, n: Int): Int {
            var p = pos
            for (i in 0 until n) {
                val v = src[i].toInt() and 0xFF
                dst[p++] = HEX[v ushr 4]
                dst[p++] = HEX[v and 0xF]
            }
            return p
        }

        /** Cierra [n] bytes de [pt] y devuelve cuantos bytes de [out] hay que enviar. */
        @Synchronized
        fun cerrar(pt: ByteArray, n: Int): Int {
            if (n > MAX_LINEA) return 0
            val o = orden++
            var i = 0
            var bloque = 0
            while (i < n) {
                contador(o, bloque++)
                enc.update(ctr, 0, 5)
                enc.doFinal(ks, 0)
                var j = 0
                while (j < 32 && i < n) { ct[i] = (pt[i].toInt() xor ks[j].toInt()).toByte(); i++; j++ }
            }
            contador(o, 0)
            firm.update(ctr, 0, 4)
            firm.update(ct, 0, n)
            firm.doFinal(tag, 0)

            var p = 0
            contador(o, 0)
            p = escribeHex(out, p, ctr, 4)
            out[p++] = ' '.code.toByte()
            p = escribeHex(out, p, ct, n)
            out[p++] = ' '.code.toByte()
            p = escribeHex(out, p, tag, 16)
            out[p++] = '\n'.code.toByte()
            return p
        }

        /** El buffer de salida. Solo valido hasta la siguiente llamada a [cerrar]. */
        val sobre: ByteArray get() = out

        companion object {
            private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        }
    }

    /** El lado que abre. Rechaza lo que no cuadre y lo que llegue fuera de orden. */
    class Abridor(kEnc: ByteArray, kMac: ByteArray) {
        private val enc = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(kEnc, "HmacSHA256")) }
        private val firm = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(kMac, "HmacSHA256")) }
        private var esperado = 0L

        /** Devuelve el texto o null si la linea es falsa, repetida o desordenada. */
        fun abrir(linea: String): String? {
            val p = linea.trim().split(" ")
            if (p.size != 3) return null
            val o = p[0].toLongOrNull(16) ?: return null
            // Estricto: repetir una linea vieja es volver a pulsar esa tecla.
            if (o != esperado) return null
            val ct = deHex(p[1]) ?: return null
            if (ct.size > MAX_LINEA) return null
            val ctr = ByteArray(5)
            ctr[0] = (o ushr 24).toByte(); ctr[1] = (o ushr 16).toByte()
            ctr[2] = (o ushr 8).toByte(); ctr[3] = o.toByte()
            firm.update(ctr, 0, 4)
            firm.update(ct, 0, ct.size)
            val tag = firm.doFinal()
            if (!MessageDigest.isEqual(tag.copyOf(16), deHex(p[2]) ?: return null)) return null
            val pt = ByteArray(ct.size)
            var i = 0
            var bloque = 0
            while (i < ct.size) {
                ctr[4] = bloque.toByte(); bloque++
                enc.update(ctr, 0, 5)
                val ks = enc.doFinal()
                var j = 0
                while (j < 32 && i < ct.size) {
                    pt[i] = (ct[i].toInt() xor ks[j].toInt()).toByte(); i++; j++
                }
            }
            esperado++
            return String(pt, Charsets.UTF_8)
        }
    }

    /** Lo que sale de un apreton correcto. */
    class Sesion(val cerrador: Cerrador, val abridor: Abridor)

    /**
     * Apreton mutuo. Devuelve la sesion, o null si el otro lado no demuestra que
     * conoce el codigo.
     *
     *   PC    -> movil   U2 <nonceS>
     *   movil -> PC      A <hmac(K, "S:nonceS:nonceC")> <nonceC>
     *   PC    -> movil   B <hmac(K, "C:nonceC:nonceS")>
     */
    fun handshake(inp: InputStream, out: OutputStream, code: String): Sesion? {
        val saludo = linea(inp) ?: return null
        val p = saludo.trim().split(" ")
        if (p.size != 2 || p[0] != "U2" || p[1].length != 32) return null
        val nonceS = p[1]
        val k = clave(code)

        val nonceC = nonce()
        out.write("A ${hex(hmac(k, "S:$nonceS:$nonceC"))} $nonceC\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        val resp = linea(inp) ?: return null
        val q = resp.trim().split(" ")
        if (q.size != 2 || q[0] != "B") return null
        if (!igual(q[1], hex(hmac(k, "C:$nonceC:$nonceS")))) return null

        // Claves de sesion: del apreton, no del codigo, y distintas por sentido.
        val base = hmac(k, "sess:$nonceS:$nonceC")
        return Sesion(
            Cerrador(hmac(base, "c2s-enc"), hmac(base, "c2s-mac")),
            Abridor(hmac(base, "s2c-enc"), hmac(base, "s2c-mac"))
        )
    }
}
