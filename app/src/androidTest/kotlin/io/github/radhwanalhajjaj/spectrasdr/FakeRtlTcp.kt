// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small rtl_tcp server for the emulator tests: the 12-byte greeting, then unsigned 8-bit IQ of
 * one carrier in noise at the pace of a real dongle. It notes the commands it gets and obeys none.
 */
class FakeRtlTcp(port: Int = 0, private val sampleRate: Int = 2_400_000) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }
    private val clients = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort

    /** Command codes received, in order. */
    val commands = CopyOnWriteArrayList<Int>()

    @Volatile
    var connections = 0
        private set

    init {
        thread(isDaemon = true, name = "fake-rtl_tcp") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                connections++
                clients += socket
                thread(isDaemon = true, name = "fake-rtl_tcp-stream") {
                    runCatching { stream(socket) }
                    runCatching { socket.close() }
                }
            }
        }
    }

    /** Cuts the connections that are open, as a server restart or a network loss would. */
    fun dropClients() {
        for (c in clients) runCatching { c.close() }
        clients.clear()
    }

    override fun close() {
        runCatching { server.close() }
        dropClients()
    }

    private fun stream(socket: Socket) {
        socket.tcpNoDelay = true
        val out = socket.getOutputStream()
        out.write(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(), 0, 0, 0, 5, 0, 0, 0, 29))
        thread(isDaemon = true, name = "fake-rtl_tcp-commands") {
            runCatching {
                val input = DataInputStream(socket.getInputStream())
                while (true) {
                    val code = input.readUnsignedByte()
                    input.readInt()
                    commands += code
                }
            }
        }
        val samples = sampleRate / 100          // 10 ms; the carrier at fs/8 fits a whole number of turns
        val block = ByteArray(2 * samples)
        val random = Random(7)
        for (n in 0 until samples) {
            val phase = 2 * PI * n / 8
            val i = 0.3 * cos(phase) + 0.02 * random.nextGaussian()
            val q = 0.3 * sin(phase) + 0.02 * random.nextGaussian()
            block[2 * n] = (127.5 + 127.5 * i).toInt().coerceIn(0, 255).toByte()
            block[2 * n + 1] = (127.5 + 127.5 * q).toInt().coerceIn(0, 255).toByte()
        }
        val start = System.nanoTime()
        var sent = 0L
        while (!socket.isClosed) {
            out.write(block)
            sent += samples
            val wait = start + sent * 1_000_000_000L / sampleRate - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }
}
