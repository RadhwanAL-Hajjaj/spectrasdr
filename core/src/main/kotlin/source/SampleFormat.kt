// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/** Wire format of interleaved I/Q samples. */
enum class SampleFormat(val bytesPerComplex: Int) {
    /** 8-bit unsigned I then Q, centred on 127.5 (RTL-SDR, rtl_tcp). */
    U8(2),

    /** 16-bit signed little-endian I then Q (rsp_tcp extended mode). */
    S16LE(4),
}
