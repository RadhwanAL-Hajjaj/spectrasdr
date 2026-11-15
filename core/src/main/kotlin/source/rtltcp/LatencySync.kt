// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import kotlin.math.pow

/** Fixed settle: discards [settleSamples] samples after each retune request (§7.1 step 3). */
class LatencySync(val settleSamples: Long) : RetuneSync() {
    private var remaining = 0L

    override fun wrap(commands: List<TimedCommand>) = commands

    override fun onRetuneRequested(count: Int, bytesPerSample: Int, sampleRate: Int) {
        remaining = settleSamples * bytesPerSample
    }

    override fun scan(bytes: ByteArray, off: Int, len: Int): Int {
        if (remaining >= len) {
            remaining -= len
            return -1
        }
        val at = remaining.toInt()
        remaining = 0
        return at
    }
}

/** Finds where a stream's power falls at least [thresholdDb] below [baseline] (latency probe). */
class PowerStepDetector(private val windowSamples: Int = 512, thresholdDb: Double = 10.0) {
    private val ratio = 10.0.pow(-thresholdDb / 10)
    var baseline = 0.0
    private var acc = 0.0
    private var inWindow = 0

    /** Feeds [n] complex samples; returns the index just past the first low window, or -1. */
    fun feed(samples: FloatArray, n: Int): Int {
        for (k in 0 until n) {
            acc += samples[2 * k] * samples[2 * k] + samples[2 * k + 1] * samples[2 * k + 1]
            if (++inWindow == windowSamples) {
                val mean = acc / windowSamples
                acc = 0.0
                inWindow = 0
                if (mean <= baseline * ratio) return k + 1
            }
        }
        return -1
    }
}
