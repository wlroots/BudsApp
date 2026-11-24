package io.github.wlroots.budsapp.bt

/** Blocking byte-stream transport used by [BudsConnection]. */
interface BudsTransport {
    val label: String

    /** Opens the connection. Throws on failure. */
    fun open()

    /** Blocking read; returns -1 on EOF. */
    fun read(buffer: ByteArray): Int

    fun write(data: ByteArray)

    fun close()
}
