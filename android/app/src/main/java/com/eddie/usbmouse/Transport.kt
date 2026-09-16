package com.eddie.usbmouse

/**
 * Por donde salen las ordenes. Hay dos implementaciones y el resto de la app no
 * distingue cual esta puesta:
 *
 *  - [MouseClient]: socket TCP por el cable, con `server.py` al otro lado.
 *    Necesita un PC con el servidor corriendo.
 *  - [BtHid]: el movil SE PRESENTA como un raton y un teclado Bluetooth de
 *    verdad. No hace falta nada al otro lado: vale contra una tele, una tablet,
 *    una consola o un PC sin instalar nada.
 *
 * La firma es la de MouseClient, que ya existia, para no tocar a quien llama.
 */
interface Transport {

    val isConnected: Boolean

    /** Nombre corto del destino para el lector de la cabecera. */
    val label: String

    // ruta caliente
    fun move(dx: Float, dy: Float)
    fun scroll(dx: Float, dy: Float)

    // acciones discretas
    fun click(b: Char)
    fun button(b: Char, down: Boolean)
    fun text(s: String)
    fun key(name: String)
    fun keyHold(name: String, down: Boolean)
    fun combo(mods: String, name: String)

    fun close()
}
