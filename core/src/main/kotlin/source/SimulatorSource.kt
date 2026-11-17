// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBand
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import java.util.Random

/**
 * A synthetic band for running the whole receiver without hardware (§12): FM broadcast stations
 * with stereo and RDS, narrow FM bursts, AM, SSB and CW signals over a noise floor. A retune or a
 * change of sample rate takes effect at a block boundary, so no stale samples follow it.
 *
 * [speed] paces the stream against the clock: 1.0 is real time, 4.0 is four times faster, and a
 * huge value runs as fast as the consumer allows.
 */
class SimulatorSource(
    private val stations: List<SimStation> = SimBand.defaultStations(),
    initialFrequencyHz: Long = 100_000_000,
    initialSampleRate: Int = 960_000,
    private val speed: Double = 1.0,
    private val noiseLevel: Float = 0.002f,
    private val blockSize: Int = 16_384,
) : IqSource {
    init {
        require(initialFrequencyHz >= 0) { "frequency must not be negative" }
        requireRate(initialSampleRate)
        require(speed > 0) { "speed must be positive" }
        require(blockSize > 0) { "block size must be positive" }
    }

    override val info = SourceInfo(
        name = "Simulator",
        kind = SourceKind.SIMULATOR,
        sampleFormat = SampleFormat.S16LE,
        canTune = true,
        sampleRates = SAMPLE_RATES,
        gainModel = GainModel.None,
        options = emptySet(),
    )

    private val lock = Any()
    private var requestedCenterHz = initialFrequencyHz
    private var requestedRate = initialSampleRate
    private var requestedEpoch = 0

    @Volatile private var running = false
    private var thread: Thread? = null

    override val centerFrequencyHz: Long get() = synchronized(lock) { requestedCenterHz }

    override fun open(listener: SourceListener) {
        check(!running) { "already open" }
        running = true
        thread = Thread({ run(listener) }, "simulator").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    }

    /** @throws IllegalArgumentException if [hz] is negative. */
    override fun setCenterFrequency(hz: Long): Int {
        require(hz >= 0) { "Frequency $hz Hz is negative" }
        return synchronized(lock) {
            requestedCenterHz = hz
            ++requestedEpoch
        }
    }

    /** @throws IllegalArgumentException if [sps] is outside [MIN_SAMPLE_RATE]..[MAX_SAMPLE_RATE]. */
    override fun setSampleRate(sps: Int): Int {
        requireRate(sps)
        return synchronized(lock) {
            requestedRate = sps
            ++requestedEpoch
        }
    }

    override fun setGain(gain: GainSetting) {}

    override fun setPpm(ppm: Int) {}

    override fun setOption(option: SourceOption) {}

    private fun run(listener: SourceListener) {
        val pool = BlockPool(blockSize, preallocate = 4)
        val noise = noiseTable()
        val random = Random(1)
        var rate = 0
        var band: SimBand? = null
        var seq = 0L
        var emitted = 0L
        var startNs = System.nanoTime()
        try {
            listener.onStarted(info)
            while (running) {
                val centerHz: Long
                val epoch: Int
                val wantedRate: Int
                synchronized(lock) {
                    centerHz = requestedCenterHz
                    epoch = requestedEpoch
                    wantedRate = requestedRate
                }
                var current = band
                if (current == null || wantedRate != rate) {
                    rate = wantedRate
                    current = SimBand(stations, rate, blockSize)
                    band = current
                    emitted = 0
                    startNs = System.nanoTime()
                }
                val block = pool.obtain()
                val out = block.samples
                val count = BlockSizing.samplesPerBlock(rate, blockSize)
                var at = random.nextInt(NOISE_SIZE)
                for (n in 0 until count) {
                    out[2 * n] = noiseLevel * noise[2 * at]
                    out[2 * n + 1] = noiseLevel * noise[2 * at + 1]
                    if (++at == NOISE_SIZE) at = 0
                }
                current.render(out, count, centerHz)
                block.length = count
                block.seq = seq++
                block.centerHz = centerHz
                block.sampleRate = rate
                block.tuneEpoch = epoch
                emitted += count
                val wait = startNs + (WavIqFileSource.dueNanos(emitted, rate) / speed).toLong() - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                listener.onBlock(block)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            // A bug here or in the listener must end the stream with a report, not end the process.
            listener.onError(SourceError.Io("The simulator stopped: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            running = false
            listener.onStopped()
        }
    }

    companion object {
        /** The rates offered to the user; any rate from [MIN_SAMPLE_RATE] to [MAX_SAMPLE_RATE] works. */
        val SAMPLE_RATES = listOf(240_000, 960_000, 1_200_000, 2_400_000)
        const val MIN_SAMPLE_RATE = 48_000
        const val MAX_SAMPLE_RATE = 3_200_000
        private const val NOISE_SIZE = 1 shl 16

        private fun requireRate(sps: Int) =
            require(sps in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Sample rate $sps is outside $MIN_SAMPLE_RATE..$MAX_SAMPLE_RATE" }

        /** Complex Gaussian noise of unit power, replayed from a random position for every block. */
        private fun noiseTable(): FloatArray {
            val random = Random(42)
            return FloatArray(2 * NOISE_SIZE) { (random.nextGaussian() * 0.7071).toFloat() }
        }
    }
}
