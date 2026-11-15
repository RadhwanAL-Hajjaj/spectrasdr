// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Recording file names (§5.4, §9); SDR# and SDR++ read the centre frequency from "<digits>Hz". */
object RecordingNames {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val frequency = Regex("(\\d{4,})Hz")

    fun iq(time: Instant, centerHz: Long) = "SpectraSDR_${stamp.format(time)}_${centerHz}Hz_IQ.wav"

    fun audio(time: Instant, centerHz: Long, mode: String) = "SpectraSDR_${stamp.format(time)}_${centerHz}Hz_$mode.wav"

    fun parseCenterHz(fileName: String): Long? = frequency.find(fileName)?.groupValues?.get(1)?.toLongOrNull()
}
