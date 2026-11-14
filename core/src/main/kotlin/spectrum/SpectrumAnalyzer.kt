// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.spectrum

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Fft
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Select
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowType
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Windows
import kotlin.math.log10

/**
 * One published spectrum. Index 0 of [levelsDb] and [peakDb] is −fs/2, index size/2 is the centre
 * frequency. Levels are in dBFS: a full-scale complex tone reads 0.
 */
class SpectrumFrame(val size: Int) {
    val levelsDb = FloatArray(size)
    val peakDb = FloatArray(size)
    var centerHz = 0L
    var sampleRate = 0
    var sequence = 0L
}

/**
 * Turns the IQ stream into spectrum frames at the display rate (§6.2). Every frame averages up to
 * [maxFftsPerFrame] FFTs of the newest samples, then applies exponential averaging on linear power
 * and a decaying peak hold. The DSP thread calls [process]; any thread may call [latest].
 *
 * The analyzer also estimates the noise floor ([noiseDensityDb]) over the central [usableFraction]
 * of the band, where the tuner's filters are flat.
 */
class SpectrumAnalyzer(
    val fftSize: Int = 4_096,
    windowType: WindowType = WindowType.BLACKMAN_HARRIS,
    private val framesPerSecond: Double = 30.0,
    private val maxFftsPerFrame: Int = 4,
    usableFraction: Double = 0.75,
) {
    init {
        require(fftSize in 1_024..65_536 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two from 1,024 to 65,536" }
        require(framesPerSecond in 1.0..120.0) { "frame rate must be 1–120 per second" }
        require(maxFftsPerFrame in 1..16) { "FFTs per frame must be 1–16" }
        require(usableFraction > 0.0 && usableFraction <= 1.0) { "usable fraction must be above 0 and at most 1" }
    }

    /** Weight of the newest frame in the average, 0.1–1 (1 = no averaging). */
    @Volatile
    var averaging = 0.5

    /** How fast held peaks fall, in dB per second. */
    @Volatile
    var peakDecayDbPerSecond = 20.0

    /**
     * Noise floor of the stream as a power density in dBFS per hertz; negative infinity before the
     * first frame. It is the 20th percentile of a slowly averaged spectrum, so it holds while up to
     * four fifths of the band carry signals, and it reads about 1 dB low.
     */
    @Volatile
    var noiseDensityDb = Double.NEGATIVE_INFINITY
        private set

    /** FFTs averaged into the last frame (diagnostics). */
    var fftsInLastFrame = 0
        private set

    private val fft = Fft(fftSize)
    private val window = Windows.create(windowType, fftSize)
    private val windowGainDb = (20 * log10(Windows.sum(window))).toFloat()

    /** Equivalent noise bandwidth of the window in bins: N·Σw² / (Σw)². */
    private val noiseBandwidthBins: Double
    private val ring = FloatArray(2 * fftSize * maxFftsPerFrame)
    private val ringComplex = fftSize * maxFftsPerFrame
    private var writeIndex = 0
    private var filled = 0L
    private var sinceFrame = 0L
    private val work = FloatArray(2 * fftSize)
    private val power = FloatArray(fftSize)
    private val average = FloatArray(fftSize)
    private var haveAverage = false
    private val slow = FloatArray(fftSize)
    private var slowFrames = 0
    private val reach = (usableFraction * fftSize / 2).toInt().coerceIn(8, fftSize / 2)
    private val usable = FloatArray(2 * reach)
    private val lock = Any()
    private val front = SpectrumFrame(fftSize)
    private var havePeak = false

    init {
        var sumOfSquares = 0.0
        for (w in window) sumOfSquares += w * w
        val sum = Windows.sum(window)
        noiseBandwidthBins = fftSize * sumOfSquares / (sum * sum)
    }

    /** Feeds [count] complex samples taken at [sampleRate] around [centerHz]. */
    fun process(samples: FloatArray, count: Int, sampleRate: Int, centerHz: Long) {
        var src = 0
        var left = count
        if (left > ringComplex) {            // only the newest samples can matter
            src = left - ringComplex
            left = ringComplex
        }
        while (left > 0) {
            val n = minOf(left, ringComplex - writeIndex)
            System.arraycopy(samples, 2 * src, ring, 2 * writeIndex, 2 * n)
            writeIndex = (writeIndex + n) % ringComplex
            src += n
            left -= n
        }
        filled += count
        sinceFrame += count
        val interval = (sampleRate / framesPerSecond).toLong()
        if (sinceFrame < interval || filled < fftSize) return
        val fresh = minOf(sinceFrame, filled)
        val ffts = (fresh / fftSize).toInt().coerceIn(1, maxFftsPerFrame)
        computeFrame(ffts, sinceFrame.toDouble() / sampleRate, sampleRate, centerHz)
        sinceFrame %= interval
    }

    /** Copies the newest frame into [dst] when it is newer than what [dst] holds; returns whether it copied. */
    fun latest(dst: SpectrumFrame): Boolean {
        require(dst.size == fftSize) { "frame size ${dst.size} does not match FFT size $fftSize" }
        synchronized(lock) {
            if (front.sequence == dst.sequence) return false
            System.arraycopy(front.levelsDb, 0, dst.levelsDb, 0, fftSize)
            System.arraycopy(front.peakDb, 0, dst.peakDb, 0, fftSize)
            dst.centerHz = front.centerHz
            dst.sampleRate = front.sampleRate
            dst.sequence = front.sequence
            return true
        }
    }

    /** Drops the sample history, the averages, the held peaks and the noise estimate, for use after a retune. */
    fun reset() {
        filled = 0
        sinceFrame = 0
        haveAverage = false
        havePeak = false
        slowFrames = 0
        noiseDensityDb = Double.NEGATIVE_INFINITY
    }

    private fun computeFrame(ffts: Int, seconds: Double, sampleRate: Int, centerHz: Long) {
        power.fill(0f)
        for (k in 0 until ffts) {
            // Segment k ends (ffts − 1 − k) FFT lengths before the newest sample.
            var at = ((writeIndex - (ffts - k) * fftSize) % ringComplex + ringComplex) % ringComplex
            for (i in 0 until fftSize) {
                val w = window[i]
                work[2 * i] = ring[2 * at] * w
                work[2 * i + 1] = ring[2 * at + 1] * w
                if (++at == ringComplex) at = 0
            }
            fft.transform(work)
            for (i in 0 until fftSize) power[i] += work[2 * i] * work[2 * i] + work[2 * i + 1] * work[2 * i + 1]
        }
        val alpha = averaging.coerceIn(0.1, 1.0).toFloat()
        val scale = 1f / ffts
        if (haveAverage) {
            for (i in 0 until fftSize) average[i] += alpha * (power[i] * scale - average[i])
        } else {
            for (i in 0 until fftSize) average[i] = power[i] * scale
            haveAverage = true
        }
        updateNoise(scale, sampleRate)
        val decay = (peakDecayDbPerSecond * seconds).toFloat()
        val half = fftSize / 2
        synchronized(lock) {
            val levels = front.levelsDb
            val peaks = front.peakDb
            for (i in 0 until fftSize) {
                val level = 10f * log10(average[i] + 1e-30f) - windowGainDb
                val at = (i + half) % fftSize          // FFT bin i sits at display index i + N/2
                levels[at] = level
                peaks[at] = if (havePeak) maxOf(level, peaks[at] - decay) else level
            }
            havePeak = true
            front.centerHz = centerHz
            front.sampleRate = sampleRate
            front.sequence++
        }
        fftsInLastFrame = ffts
    }

    /**
     * Keeps a slow average of the power spectrum (a plain mean over the first frames, so that it is
     * usable at once) and takes its 20th percentile over the usable bins a few times per second.
     */
    private fun updateNoise(scale: Float, sampleRate: Int) {
        slowFrames++
        val weight = maxOf(1f / slowFrames, NOISE_ALPHA)
        for (i in 0 until fftSize) slow[i] += weight * (power[i] * scale - slow[i])
        if (slowFrames > NOISE_EVERY && slowFrames % NOISE_EVERY != 0) return
        // FFT bins 0 … reach−1 lie just above the centre, the last reach bins just below it.
        System.arraycopy(slow, 0, usable, 0, reach)
        System.arraycopy(slow, fftSize - reach, usable, reach, reach)
        val floor = Select.kth(usable, usable.size, usable.size / 5)
        // One bin of the spectrum gathers the noise of noiseBandwidthBins · fs / N hertz.
        noiseDensityDb = 10 * log10(floor + 1e-30) - windowGainDb - 10 * log10(noiseBandwidthBins * sampleRate / fftSize)
    }

    private companion object {
        const val NOISE_ALPHA = 0.1f
        const val NOISE_EVERY = 8
    }
}

/** Display binning (§6.2). */
object SpectrumDisplay {
    /**
     * Reduces the bins from [firstBin] to [lastBin] (exclusive; fractions allowed) of [levels] to
     * [columns] values in [out]: each column gets the largest level among the bins it covers, so
     * narrow peaks survive zooming out. Zoomed in beyond one bin per column, bins simply repeat.
     */
    fun reduce(levels: FloatArray, firstBin: Double, lastBin: Double, out: FloatArray, columns: Int) {
        val perColumn = (lastBin - firstBin) / columns
        val last = levels.size - 1
        for (c in 0 until columns) {
            val from = (firstBin + c * perColumn).toInt().coerceIn(0, last)
            val to = (kotlin.math.ceil(firstBin + (c + 1) * perColumn).toInt() - 1).coerceIn(from, last)
            var peak = levels[from]
            for (i in from + 1..to) if (levels[i] > peak) peak = levels[i]
            out[c] = peak
        }
    }
}
