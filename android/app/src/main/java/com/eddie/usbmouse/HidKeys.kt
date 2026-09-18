package com.eddie.usbmouse

/**
 * Traduccion del protocolo a codigos HID (USB HID Usage Tables, pagina 0x07).
 *
 * Ojo con el teclado: un teclado HID no manda letras, manda POSICIONES de tecla.
 * La distribucion la pone el equipo del otro lado. Si ese equipo esta en QWERTY
 * espanol, la tecla que aqui se llama `;` escribira `ñ`. Por eso [charOf] da por
 * supuesto un anfitrion en US: es lo unico que se puede suponer sin preguntar, y
 * los acentos y la enye se quedan fuera (ver [unsupported]).
 *
 * Con el servidor por cable esto no pasaba: alli se mandaba el texto y `pynput`
 * lo escribia tal cual, respetando la distribucion. Es lo que se pierde al ser
 * un periferico de verdad en vez de un programa.
 */
object HidKeys {

    // modificadores, byte 0 del informe de teclado
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val GUI = 0x08

    /** Letras de `H<mods>,<tecla>` -> bit del modificador. */
    fun modMask(mods: String): Int {
        var m = 0
        for (c in mods) m = m or when (c) {
            'c' -> CTRL
            's' -> SHIFT
            'a' -> ALT
            'w' -> GUI
            else -> 0
        }
        return m
    }

    private val especiales = HashMap<String, Int>(64).apply {
        put("enter", 0x28); put("esc", 0x29); put("back", 0x2A); put("tab", 0x2B)
        put("space", 0x2C); put("caps", 0x39)
        put("right", 0x4F); put("left", 0x50); put("down", 0x51); put("up", 0x52)
        put("ins", 0x49); put("home", 0x4A); put("pgup", 0x4B)
        put("del", 0x4C); put("end", 0x4D); put("pgdn", 0x4E)
        // F1..F12 son contiguas; F13..F24 empiezan en otro sitio
        for (i in 1..12) put("f$i", 0x3A + i - 1)
        for (i in 13..24) put("f$i", 0x68 + i - 13)
    }

    /**
     * Marca de que el codigo devuelto necesita shift. Va en un bit alto y no en
     * un Pair para no asignar nada por tecla.
     */
    const val NEEDS_SHIFT = 0x100

    /**
     * Nombre del protocolo -> codigo, o 0 si aqui no existe. El bit
     * [NEEDS_SHIFT] viene puesto cuando la tecla solo se alcanza con shift.
     *
     * Se perdia: `charOf` ya decia que '?' es shift+'/', pero keyOf tiraba ese
     * dato y se mandaba '/' pelado. Hoy no se nota porque el teclado de la app
     * no tiene simbolos, pero rompe en cuanto se anada el primero.
     */
    fun keyOf(name: String): Int {
        if (name.length == 1) {
            val (c, shift) = charOf(name[0])
            return if (c == 0) 0 else if (shift) c or NEEDS_SHIFT else c
        }
        return especiales[name.lowercase()] ?: 0
    }

    /**
     * Caracter -> (codigo, hace falta shift). Distribucion US.
     * Devuelve 0 en el codigo si no se puede teclear.
     */
    fun charOf(c: Char): Pair<Int, Boolean> = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to false
        in 'A'..'Z' -> (0x04 + (c - 'A')) to true
        in '1'..'9' -> (0x1E + (c - '1')) to false
        '0' -> 0x27 to false
        ' ' -> 0x2C to false
        '\t' -> 0x2B to false
        '-' -> 0x2D to false; '_' -> 0x2D to true
        '=' -> 0x2E to false; '+' -> 0x2E to true
        '[' -> 0x2F to false; '{' -> 0x2F to true
        ']' -> 0x30 to false; '}' -> 0x30 to true
        '\\' -> 0x31 to false; '|' -> 0x31 to true
        ';' -> 0x33 to false; ':' -> 0x33 to true
        '\'' -> 0x34 to false; '"' -> 0x34 to true
        '`' -> 0x35 to false; '~' -> 0x35 to true
        ',' -> 0x36 to false; '<' -> 0x36 to true
        '.' -> 0x37 to false; '>' -> 0x37 to true
        '/' -> 0x38 to false; '?' -> 0x38 to true
        '!' -> 0x1E to true; '@' -> 0x1F to true; '#' -> 0x20 to true
        '$' -> 0x21 to true; '%' -> 0x22 to true; '^' -> 0x23 to true
        '&' -> 0x24 to true; '*' -> 0x25 to true; '(' -> 0x26 to true
        ')' -> 0x27 to true
        else -> 0 to false
    }

    /** Lo que de [s] no se puede teclear en HID, para poder avisar. */
    fun unsupported(s: String): String = s.filter { charOf(it).first == 0 }
}
