// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import kotlin.math.abs

/** A transmitter in the simulated band. [level] is its amplitude at the receiver (full scale = 1.0). */
sealed interface SimStation {
    val frequencyHz: Long
    val level: Float

    /** FM broadcast in stereo with RDS: a tone per channel (0 Hz = silent), station name [name] and radio text [text]. */
    data class FmBroadcast(
        override val frequencyHz: Long,
        val name: String,
        val text: String = "",
        val pi: Int = 0x1000,
        val leftHz: Double = 1_000.0,
        val rightHz: Double = 400.0,
        override val level: Float = 0.2f,
    ) : SimStation

    /** Narrow FM transmissions of [burstSeconds] separated by [pauseSeconds] of silence (0 = always on). */
    data class NarrowFm(
        override val frequencyHz: Long,
        val toneHz: Double = 1_000.0,
        val deviationHz: Double = 3_000.0,
        val burstSeconds: Double = 2.0,
        val pauseSeconds: Double = 2.0,
        override val level: Float = 0.05f,
    ) : SimStation {
        init {
            require(burstSeconds > 0 && pauseSeconds >= 0) { "bursts must last and pauses must not be negative" }
        }
    }

    /** AM carrier modulated to [depth] by a tone. */
    data class Am(
        override val frequencyHz: Long,
        val toneHz: Double = 800.0,
        val depth: Double = 0.5,
        override val level: Float = 0.05f,
    ) : SimStation

    /** Single-sideband two-tone signal; [frequencyHz] is the suppressed carrier. */
    data class Ssb(
        override val frequencyHz: Long,
        val upper: Boolean = true,
        val tone1Hz: Double = 700.0,
        val tone2Hz: Double = 1_900.0,
        override val level: Float = 0.03f,
    ) : SimStation

    /** A CW carrier keyed in dots of [dotSeconds]: one dot on, one dot off. */
    data class Cw(
        override val frequencyHz: Long,
        val dotSeconds: Double = 0.1,
        override val level: Float = 0.03f,
    ) : SimStation
}

/**
 * Renders [stations] as a tuner sampling at [sampleRate] would see them. A station is rendered
 * only while all of it lies inside the stream, so nothing folds over at the edges. Time runs on
 * for keyed stations while they are out of view; broadcast programmes simply pause.
 */
class SimBand(stations: List<SimStation>, private val sampleRate: Int, private val maxBlock: Int = 16_384) {
    private val rate = sampleRate.toDouble()
    private val voices = stations.map { voiceFor(it) }.toTypedArray()

    /** Adds [count] complex samples of every station in view around [centerHz] to [out]. */
    fun render(out: FloatArray, count: Int, centerHz: Long) {
        require(count <= maxBlock) { "count $count exceeds the block size $maxBlock" }
        for (voice in voices) {
            val offset = (voice.station.frequencyHz - centerHz).toDouble()
            voice.render(out, count, offset, abs(offset) + voice.halfWidthHz <= rate / 2)
        }
    }

    private abstract class Voice(val station: SimStation, val halfWidthHz: Double) {
        /** Adds the station at [offsetHz] to [out]; when not [inView] only its clock advances. */
        abstract fun render(out: FloatArray, count: Int, offsetHz: Double, inView: Boolean)
    }

    private fun voiceFor(station: SimStation): Voice = when (station) {
        is SimStation.FmBroadcast -> object : Voice(station, 100_000.0) {
            val encoder = RdsEncoder(station.pi, station.name, station.text)
            val mpx = MpxGenerator(rate, station.leftHz, station.rightHz, rds = RdsBaseband(encoder.bits, rate))
            val modulator = FmModulator(rate, 75_000.0)
            val message = FloatArray(maxBlock)

            override fun render(out: FloatArray, count: Int, offsetHz: Double, inView: Boolean) {
                if (!inView) return
                mpx.generate(message, count)
                modulator.carrierHz = offsetHz
                modulator.modulate(message, count, out, station.level, add = true)
            }
        }
        is SimStation.NarrowFm -> object : Voice(station, 8_000.0) {
            val tone = Oscillator(station.toneHz, rate)
            val modulator = FmModulator(rate, station.deviationHz)
            val message = FloatArray(maxBlock)
            val on = maxOf(1L, (station.burstSeconds * rate).toLong())
            val period = on + (station.pauseSeconds * rate).toLong()
            var position = 0L   // within the current burst-and-pause cycle

            override fun render(out: FloatArray, count: Int, offsetHz: Double, inView: Boolean) {
                if (!inView) {
                    position = (position + count) % period
                    return
                }
                modulator.carrierHz = offsetHz
                var n = 0
                while (n < count) {
                    // One run lasts until the transmitter switches or the block ends.
                    val transmitting = position < on
                    val run = minOf((count - n).toLong(), (if (transmitting) on else period) - position).toInt()
                    if (transmitting) {
                        for (k in 0 until run) {
                            message[k] = tone.sine.toFloat()
                            tone.advance()
                        }
                        modulator.modulate(message, run, out, station.level, add = true, outOffset = n)
                    }
                    n += run
                    position += run
                    if (position >= period) position = 0
                }
            }
        }
        is SimStation.Am -> object : Voice(station, 5_000.0) {
            val tone = Oscillator(station.toneHz, rate)
            val carrier = FmModulator(rate, 0.0)
            val silence = FloatArray(maxBlock)
            val scratch = FloatArray(2 * maxBlock)

            override fun render(out: FloatArray, count: Int, offsetHz: Double, inView: Boolean) {
                if (!inView) return
                carrier.carrierHz = offsetHz
                carrier.modulate(silence, count, scratch, station.level)
                for (n in 0 until count) {
                    val m = (1 + station.depth * tone.sine).toFloat()
                    tone.advance()
                    out[2 * n] += scratch[2 * n] * m
                    out[2 * n + 1] += scratch[2 * n + 1] * m
                }
            }
        }
        is SimStation.Ssb -> object : Voice(station, 3_000.0) {
            val sign = if (station.upper) 1.0 else -1.0
            val first = FmModulator(rate, 0.0)
            val second = FmModulator(rate, 0.0)
            val silence = FloatArray(maxBlock)

            override fun render(out: FloatArray, count: Int, offsetHz: Double, inView: Boolean) {
                if (!inView) return
                first.carrierHz = offsetHz + sign * station.tone1Hz
                second.carrierHz = offsetHz + sign * station.tone2Hz
                first.modulate(silence, count, out, station.level / 2, add = true)
                second.modulate(silence, count, out, station.level / 2, add = true)
            }
        }
        is SimStation.Cw -> object : Voice(station, 1_000.0) {
            val carrier = FmModulator(rate, 0.0)
            val silence = FloatArray(maxBlock)
            val scratch = FloatArray(2 * maxBlock)
            val dot = maxOf(1L, (station.dotSeconds * rate).toLong())
            val edge = (1.0 / (0.005 * rate)).toFloat()      // 5 ms key shaping
            var position = 0L
            var key = 0f

            override fun render(out: FloatArray, count: Int, offsetHz: Double, inView: Boolean) {
                if (!inView) {
                    position += count
                    return
                }
                carrier.carrierHz = offsetHz
                carrier.modulate(silence, count, scratch, station.level)
                for (n in 0 until count) {
                    val down = (position++ / dot) % 2 == 0L
                    key = if (down) minOf(1f, key + edge) else maxOf(0f, key - edge)
                    out[2 * n] += scratch[2 * n] * key
                    out[2 * n + 1] += scratch[2 * n + 1] * key
                }
            }
        }
    }

    companion object {
        /** A demonstration band with something to hear in every mode. */
        fun defaultStations(): List<SimStation> = listOf(
            SimStation.FmBroadcast(99_500_000, "SPECTRA1", "SpectraSDR simulator, station one", 0xD301, 1_000.0, 400.0),
            SimStation.FmBroadcast(100_300_000, "DEMO FM ", "Second simulated station", 0xD302, 600.0, 600.0),
            SimStation.FmBroadcast(101_700_000, "RADIO 3 ", "Third simulated station", 0xD303, 1_500.0, 0.0, level = 0.05f),
            SimStation.Am(118_300_000),
            SimStation.NarrowFm(145_500_000),
            SimStation.Ssb(14_200_000, upper = true),
            SimStation.Ssb(7_100_000, upper = false),
            SimStation.Cw(14_050_000),
        )
    }
}
