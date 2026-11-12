// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockPool
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleConverter
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Settings for [RtlTcpClient]. */
data class RtlTcpConfig(
    val host: String,
    val port: Int,
    val kind: SourceKind = SourceKind.RTL_TCP,
    val name: String = "rtl_tcp $host:$port",
    val initialFrequencyHz: Long = 100_000_000,
    val initialSampleRate: Int = 2_400_000,
    /** Commands the server accepts (the Android driver's supportedTcpCommands); null = everything. */
    val supportedCommands: Set<Int>? = null,
    /** Look for an rsp_tcp extended-mode RSP0 structure after the header. */
    val detectExtended: Boolean = true,
    /** Send ANDROID_EXIT before closing (Android SDR driver). */
    val sendExitOnClose: Boolean = false,
    val retuneSync: RetuneSyncMode = RetuneSyncMode.AUTO,
    val blockSize: Int = 16_384,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 5_000,
    val receiveBufferBytes: Int = 8 * 1024 * 1024,
)

/**
 * IQ source for rtl_tcp servers, the Android SDR driver's local server and rsp_tcp (§5). A reader
 * thread receives samples; a single command thread writes commands in order.
 */
class RtlTcpClient(private val config: RtlTcpConfig) : IqSource {
    @Volatile private var currentInfo = rtlInfo(emptyList())
    override val info: SourceInfo get() = currentInfo

    @Volatile private var currentCenterHz = config.initialFrequencyHz
    override val centerFrequencyHz: Long get() = currentCenterHz

    /** The 12-byte header received from the server, once connected. */
    @Volatile
    var dongle: RtlTcpProtocol.DongleInfo? = null
        private set

    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var streaming = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var sync: RetuneSync = ImmediateSync()
    @Volatile private var format = SampleFormat.U8
    @Volatile private var sampleRate = config.initialSampleRate
    private var readerThread: Thread? = null
    private val commands: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rtltcp-cmd").apply { isDaemon = true } }
    private val cmdBuf = ByteArray(RtlTcpProtocol.COMMAND_SIZE)
    private val pool = BlockPool(config.blockSize, preallocate = 8)

    private val tuneLock = Any()
    @Volatile private var requestedEpoch = 0
    private var requestedCenterHz = config.initialFrequencyHz
    private var requestedRate = config.initialSampleRate

    @Volatile private var lastGain: GainSetting? = null
    @Volatile private var lastPpm: Int? = null
    private val lastOptions = ConcurrentHashMap<OptionKind, SourceOption>()

    override fun open(listener: SourceListener) {
        check(!running && !closed) { "an RtlTcpClient can be opened once" }
        running = true
        readerThread = Thread({ readerLoop(listener) }, "rtltcp-reader").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (running && config.sendExitOnClose) {
            // The reader is still running, so the socket stays open until the exit command is written.
            runCatching { commands.submit { writeCommand(RtlTcpProtocol.ANDROID_EXIT, 0) }.get(1, TimeUnit.SECONDS) }
        }
        running = false
        commands.shutdownNow()
        runCatching { socket?.close() }
        readerThread?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    }

    override fun setCenterFrequency(hz: Long): Int =
        retune(listOf(TimedCommand(RtlTcpProtocol.SET_FREQ, freqParam(hz)))) {
            requestedCenterHz = hz
            currentCenterHz = hz
        }

    override fun setSampleRate(sps: Int) {
        retune(listOf(TimedCommand(RtlTcpProtocol.SET_SAMPLE_RATE, sps))) {
            requestedRate = sps
            sampleRate = sps
        }
    }

    override fun setGain(gain: GainSetting) {
        synchronized(tuneLock) {
            lastGain = gain
            submit(gainCommands(gain))
        }
    }

    override fun setPpm(ppm: Int) {
        synchronized(tuneLock) {
            lastPpm = ppm
            submit(listOf(TimedCommand(RtlTcpProtocol.SET_FREQ_CORRECTION, ppm)))
        }
    }

    override fun setOption(option: SourceOption) {
        synchronized(tuneLock) {
            lastOptions[option.kind] = option
            val cmds = optionCommands(option)
            // Direct sampling and offset tuning change what the samples mean, so they count as retunes.
            if (option is SourceOption.DirectSampling || option is SourceOption.OffsetTuning) retune(cmds) {} else submit(cmds)
        }
    }

    private fun retune(cmds: List<TimedCommand>, update: () -> Unit): Int {
        // Wrapping, epoch and queueing share one lock so wire order matches epoch order.
        return synchronized(tuneLock) {
            val wrapped = sync.wrap(cmds)
            update()
            requestedEpoch += 1
            submit(wrapped)
            requestedEpoch
        }
    }

    private fun submit(cmds: List<TimedCommand>) {
        if (cmds.isEmpty()) return
        runCatching {
            commands.execute {
                try {
                    for (c in cmds) {
                        if (c.delayBeforeMs > 0) Thread.sleep(c.delayBeforeMs)
                        writeCommand(c.command, c.param)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun writeCommand(command: Int, param: Int) {
        if (!supports(command)) return
        val out = output ?: return
        try {
            out.write(RtlTcpProtocol.encodeCommand(command, param, cmdBuf))
            out.flush()
        } catch (_: IOException) {
            // The reader thread reports the lost connection.
        }
    }

    private fun supports(command: Int) = config.supportedCommands?.contains(command) ?: true

    private fun gainCommands(gain: GainSetting): List<TimedCommand> = when (gain) {
        GainSetting.Auto -> listOf(TimedCommand(RtlTcpProtocol.SET_GAIN_MODE, 0))
        is GainSetting.TunerTenthsDb -> listOf(
            TimedCommand(RtlTcpProtocol.SET_GAIN_MODE, 1),
            TimedCommand(RtlTcpProtocol.SET_GAIN, gain.tenthsDb),
        )
        is GainSetting.TunerIndex -> listOf(
            TimedCommand(RtlTcpProtocol.SET_GAIN_MODE, 1),
            TimedCommand(RtlTcpProtocol.SET_GAIN_BY_INDEX, gain.index),
        )
        is GainSetting.RspLna -> listOf(TimedCommand(RtlTcpProtocol.RSP_SET_LNA_STATE, gain.state))
        is GainSetting.RspIfgr -> listOf(TimedCommand(RtlTcpProtocol.RSP_SET_IF_GAIN_R, gain.db))
        is GainSetting.RspAgc -> listOf(
            TimedCommand(RtlTcpProtocol.RSP_SET_AGC, if (gain.enabled) 1 else 0),
            TimedCommand(RtlTcpProtocol.RSP_SET_AGC_SETPOINT, gain.setpointDbfs),
        )
    }

    private fun optionCommands(option: SourceOption): List<TimedCommand> = when (option) {
        is SourceOption.BiasT -> listOf(TimedCommand(RtlTcpProtocol.SET_BIAS_TEE, if (option.enabled) 1 else 0))
        is SourceOption.DirectSampling -> listOf(TimedCommand(RtlTcpProtocol.SET_DIRECT_SAMPLING, option.mode))
        is SourceOption.OffsetTuning -> listOf(TimedCommand(RtlTcpProtocol.SET_OFFSET_TUNING, if (option.enabled) 1 else 0))
        is SourceOption.RspAntenna -> listOf(TimedCommand(RtlTcpProtocol.RSP_SET_ANTENNA, option.port))
        is SourceOption.RspNotches -> listOf(TimedCommand(RtlTcpProtocol.RSP_SET_NOTCH, option.mask))
    }

    private fun readerLoop(listener: SourceListener) {
        try {
            val s = Socket()
            socket = s
            try {
                s.receiveBufferSize = config.receiveBufferBytes
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
            } catch (e: IOException) {
                if (!closed) listener.onError(SourceError.ConnectFailed("Cannot connect to ${config.host}:${config.port}: ${e.message}"))
                return
            }
            output = s.getOutputStream()
            s.soTimeout = config.connectTimeoutMs
            streaming = false
            val input = DataInputStream(s.getInputStream())
            val header = ByteArray(RtlTcpProtocol.HEADER_SIZE)
            input.readFully(header)
            val d = RtlTcpProtocol.parseHeader(header)
            if (!d.magic.all { it.code in 0x21..0x7e }) {
                listener.onError(SourceError.ProtocolError("Not an rtl_tcp server (unexpected header)"))
                return
            }
            dongle = d
            val leftover = handshake(input, d)
            val replayEpoch = synchronized(tuneLock) {
                sync = chooseSync()
                applyInitialSettings()
            }
            listener.onStarted(currentInfo)
            s.soTimeout = config.readTimeoutMs
            streaming = true
            streamLoop(input, leftover, listener, replayEpoch)
        } catch (e: EOFException) {
            if (!closed) listener.onError(SourceError.StreamEnded("The server closed the connection"))
        } catch (e: SocketTimeoutException) {
            if (!closed) {
                listener.onError(
                    if (streaming) {
                        SourceError.StreamEnded("No samples from the server for ${config.readTimeoutMs / 1000.0} s")
                    } else {
                        SourceError.ConnectFailed("${config.host}:${config.port} accepted the connection but sent nothing; it may be busy with another client")
                    },
                )
            }
        } catch (e: IOException) {
            if (!closed) listener.onError(SourceError.Io(e.message ?: "Connection error"))
        } finally {
            running = false
            runCatching { socket?.close() }
            listener.onStopped()
        }
    }

    /** Reads whatever follows the header; sets [format] and [info]; returns sample bytes read early. */
    private fun handshake(input: DataInputStream, d: RtlTcpProtocol.DongleInfo): ByteArray {
        format = SampleFormat.U8
        currentInfo = rtlInfo(d.tunerType.gainsTenthsDb)
        return ByteArray(0)
    }

    private fun chooseSync(): RetuneSync = when (config.retuneSync) {
        RetuneSyncMode.AUTO, RetuneSyncMode.IMMEDIATE -> ImmediateSync()
    }

    /** Re-applies every setting as one synchronised retune, so the first blocks are already correct. */
    private fun applyInitialSettings(): Int {
        val cmds = mutableListOf(TimedCommand(RtlTcpProtocol.SET_SAMPLE_RATE, sampleRate))
        lastOptions[OptionKind.DIRECT_SAMPLING]?.let { cmds += optionCommands(it) }
        lastOptions[OptionKind.OFFSET_TUNING]?.let { cmds += optionCommands(it) }
        lastPpm?.let { cmds += TimedCommand(RtlTcpProtocol.SET_FREQ_CORRECTION, it) }
        lastGain?.let { cmds += gainCommands(it) }
        cmds += TimedCommand(RtlTcpProtocol.SET_FREQ, freqParam(currentCenterHz))
        val epoch = retune(cmds) {}
        lastOptions.values
            .filter { it.kind != OptionKind.DIRECT_SAMPLING && it.kind != OptionKind.OFFSET_TUNING }
            .forEach { submit(optionCommands(it)) }
        return epoch
    }

    private fun streamLoop(input: DataInputStream, leftover: ByteArray, listener: SourceListener, replayEpoch: Int) {
        val buf = ByteArray(64 * 1024)
        System.arraycopy(leftover, 0, buf, 0, leftover.size)
        var have = leftover.size
        val bps = format.bytesPerComplex
        // Retunes before the replay never carried markers; start just below the replay's epoch.
        var observedEpoch = replayEpoch - 1
        var activeEpoch = replayEpoch - 1
        var activeCenterHz = currentCenterHz
        var activeRate = sampleRate
        var pendingCenterHz = activeCenterHz
        var pendingRate = activeRate
        var discarding = false
        var block: IqBlock? = null
        var seq = 0L
        try {
            while (running) {
                val n = input.read(buf, have, buf.size - have)
                if (n < 0) throw EOFException()
                have += n
                val whole = have - have % bps
                var pos = 0
                while (pos < whole) {
                    if (requestedEpoch != observedEpoch) {
                        val newEpoch = synchronized(tuneLock) {
                            pendingCenterHz = requestedCenterHz
                            pendingRate = requestedRate
                            requestedEpoch
                        }
                        sync.onRetuneRequested(newEpoch - observedEpoch, bps, pendingRate)
                        observedEpoch = newEpoch
                        discarding = true
                        block?.let { if (it.length > 0) listener.onBlock(it) else it.release() }
                        block = null
                    }
                    if (discarding) {
                        val boundary = sync.scan(buf, pos, whole - pos)
                        if (boundary < 0) {
                            pos = whole
                            continue
                        }
                        pos += boundary
                        discarding = false
                        activeEpoch = observedEpoch
                        activeCenterHz = pendingCenterHz
                        activeRate = pendingRate
                        continue
                    }
                    val b = block ?: pool.obtain().also {
                        it.seq = seq++
                        it.centerHz = activeCenterHz
                        it.sampleRate = activeRate
                        it.tuneEpoch = activeEpoch
                        block = it
                    }
                    val got = SampleConverter.convert(format, buf, pos, whole - pos, b.samples, b.length, b.capacity - b.length)
                    b.length += got
                    pos += got * bps
                    if (b.length == b.capacity) {
                        listener.onBlock(b)
                        block = null
                    }
                }
                val rest = have - whole
                System.arraycopy(buf, whole, buf, 0, rest)
                have = rest
            }
        } finally {
            block?.release()
        }
    }

    private fun rtlInfo(gains: List<Int>) = SourceInfo(
        name = config.name,
        kind = config.kind,
        sampleFormat = SampleFormat.U8,
        canTune = true,
        sampleRates = RTL_SAMPLE_RATES,
        gainModel = GainModel.TunerSteps(gains),
        options = buildSet {
            if (supports(RtlTcpProtocol.SET_BIAS_TEE)) add(OptionKind.BIAS_T)
            if (supports(RtlTcpProtocol.SET_DIRECT_SAMPLING)) add(OptionKind.DIRECT_SAMPLING)
            if (supports(RtlTcpProtocol.SET_OFFSET_TUNING)) add(OptionKind.OFFSET_TUNING)
        },
    )

    companion object {
        val RTL_SAMPLE_RATES = listOf(
            250_000, 1_024_000, 1_536_000, 1_792_000, 1_920_000, 2_048_000,
            2_160_000, 2_400_000, 2_560_000, 2_880_000, 3_200_000,
        )

        /** Frequencies travel as unsigned 32-bit values. */
        fun freqParam(hz: Long): Int = (hz and 0xFFFFFFFFL).toInt()
    }
}
