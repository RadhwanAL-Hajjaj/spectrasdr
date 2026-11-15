// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

/**
 * Listening modes with their default channel bandwidth, the presets offered (§6.3) and the range
 * a filter edge can be dragged over.
 */
enum class Mode(val defaultBandwidthHz: Int, val bandwidthsHz: List<Int>, val minBandwidthHz: Int, val maxBandwidthHz: Int) {
    WFM(200_000, listOf(200_000), 100_000, 200_000),
    NFM(12_500, listOf(6_250, 12_500, 25_000), 5_000, 30_000),
    AM(8_000, listOf(6_000, 8_000, 10_000), 3_000, 12_000),
    USB(2_500, listOf(2_500), 2_500, 2_500),
    LSB(2_500, listOf(2_500), 2_500, 2_500),
    CW(500, listOf(250, 500, 1_000), 100, 1_000),
}
