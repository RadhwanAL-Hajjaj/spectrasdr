// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * In-process rtl_tcp / rsp_tcp server for tests. Streams a synthetic band (complex tones at
 * absolute frequencies plus noise) as seen by a tuner at the current centre frequency. Commands take
 * effect [pipelineDelaySamples] samples after they arrive, like a real driver's USB buffers, and
 * test mode replaces samples with the RTL2832U's incrementing byte counter.
 */
class FakeRtlTcpServer(
    private val tunerType: Int = 5,
    private val gainCount: Int = 29,
    private val extraHandshake: ByteArray = ByteArray(0),
    private val format: SampleFormat = SampleFormat.U8,
    private val sampleRate: Double = 1_000_000.0,
    private val pipelineDelaySamples: Int = 0,
    private val supportsTestMode: Boolean = true,
    startInTestMode: Boolean = false,
    /** Absolute frequency (Hz) and amplitude (full scale = 1) of each station. */
    private val stations: List<Pair<Double, Double>> = emptyList(),
    private val noiseRms: Double = 0.01,
    /** Close the connection after about this many samples; null streams until closed. */
    private val totalSamples: Long? = null,
    /** Stream at this many samples per second instead of as fast as possible. */
    private val pacedSamplesPerSecond: Double? = null,
    initialCenterHz: Long = 100_000_000,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    /** Every command received, in order, as (code, parameter). */
    val commands = CopyOnWriteArrayList<Pair<Int, Int>>()

    @Volatile private var closed = false
    @Volatile private var sampleIndex = 0L
    @Volatile private var nextApplyAt = Long.MAX_VALUE
    private val pending = ArrayDeque<Triple<Long, Int, Int>>()   // (applyAt, code, param)
    private val lock = Any()
    private var center = initialCenterHz.toDouble()
    private var testMode = startInTestMode
    private var gainDb = 0.0
    private var counter = 0
    private var curI = 0.0
    private var curQ = 0.0

    fun start(): FakeRtlTcpServer {
        Thread({ runCatching { server.accept().use { handle(it) } } }, "fake-rtltcp").apply {
            isDaemon = true
            start()
        }
        return this
    }

    /** Waits until at least [n] commands have arrived. */
    fun awaitCommands(n: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (commands.size < n) {
            check(System.currentTimeMillis() < deadline) { "expected $n commands, got ${commands.toList()}" }
            Thread.sleep(5)
        }
    }

    private fun handle(socket: Socket) {
        val out = socket.getOutputStream()
        val header = ByteArray(RtlTcpProtocol.HEADER_SIZE)
        "RTL0".toByteArray(Charsets.US_ASCII).copyInto(header)
        RtlTcpProtocol.writeIntBE(header, 4, tunerType)
        RtlTcpProtocol.writeIntBE(header, 8, gainCount)
        out.write(header)
        out.write(extraHandshake)
        out.flush()
        val input = DataInputStream(socket.getInputStream())
        Thread({
            val cmd = ByteArray(RtlTcpProtocol.COMMAND_SIZE)
            runCatching {
                while (!closed) {
                    input.readFully(cmd)
                    val code = cmd[0].toInt() and 0xFF
                    val param = RtlTcpProtocol.readIntBE(cmd, 1)
                    commands += code to param
                    synchronized(lock) {
                        pending.addLast(Triple(sampleIndex + pipelineDelaySamples, code, param))
                        nextApplyAt = pending.first().first
                    }
                }
            }
        }, "fake-rtltcp-cmd").apply {
            isDaemon = true
            start()
        }

        val rnd = Random(7)
        val chunk = 4096
        val bytes = ByteArray(chunk * format.bytesPerComplex)
        val startNs = System.nanoTime()
        while (!closed) {
            val limit = totalSamples
            if (limit != null && sampleIndex >= limit) break
            var b = 0
            for (k in 0 until chunk) {
                if (sampleIndex >= nextApplyAt) applyDue()
                when (format) {
                    SampleFormat.U8 -> if (testMode) {
                        bytes[b] = counter.toByte()
                        bytes[b + 1] = (counter + 1).toByte()
                        counter = (counter + 2) and 0xFF
                    } else {
                        generate(rnd)
                        bytes[b] = u8(curI)
                        bytes[b + 1] = u8(curQ)
                    }
                    SampleFormat.S16LE -> {
                        generate(rnd)
                        writeShortLE(bytes, b, s16(curI))
                        writeShortLE(bytes, b + 2, s16(curQ))
                    }
                }
                b += format.bytesPerComplex
                sampleIndex++
            }
            out.write(bytes, 0, b)
            val rate = pacedSamplesPerSecond
            if (rate != null) {
                val wait = startNs + (sampleIndex * 1e9 / rate).toLong() - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        }
        out.flush()
    }

    private fun applyDue() {
        synchronized(lock) {
            while (pending.isNotEmpty() && pending.first().first <= sampleIndex) {
                val (_, code, param) = pending.removeFirst()
                when (code) {
                    RtlTcpProtocol.SET_FREQ -> center = (param.toLong() and 0xFFFFFFFFL).toDouble()
                    RtlTcpProtocol.SET_TEST_MODE -> if (supportsTestMode) testMode = param != 0
                    RtlTcpProtocol.SET_GAIN -> gainDb = (param - 400) / 10.0   // 40.0 dB of tuner gain = unity
                    RtlTcpProtocol.SET_GAIN_MODE -> if (param == 0) gainDb = 0.0
                    RtlTcpProtocol.RSP_SET_IF_GAIN_R -> gainDb = -param.toDouble()
                    RtlTcpProtocol.RSP_SET_AGC -> if (param != 0) gainDb = 0.0
                }
            }
            nextApplyAt = if (pending.isEmpty()) Long.MAX_VALUE else pending.first().first
        }
    }

    private fun generate(rnd: Random) {
        var i = noiseRms * rnd.nextGaussian()
        var q = noiseRms * rnd.nextGaussian()
        val t = sampleIndex / sampleRate
        for (s in stations.indices) {
            val (freq, amplitude) = stations[s]
            val offset = freq - center
            if (abs(offset) < sampleRate / 2) {
                val ph = 2 * PI * offset * t
                i += amplitude * cos(ph)
                q += amplitude * sin(ph)
            }
        }
        val g = 10.0.pow(gainDb / 20)
        curI = i * g
        curQ = q * g
    }

    private fun u8(v: Double) = (v * 127.5 + 127.5).roundToInt().coerceIn(0, 255).toByte()

    private fun s16(v: Double) = (v * 32767).roundToInt().coerceIn(-32768, 32767)

    private fun writeShortLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v shr 8).toByte()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }
}
