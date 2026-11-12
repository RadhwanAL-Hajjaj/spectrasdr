// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/** A command for the server, optionally preceded by a pause. */
data class TimedCommand(val command: Int, val param: Int, val delayBeforeMs: Long = 0)

/** How [RtlTcpClient] finds where a retune takes effect in the stream (§7.1). */
enum class RetuneSyncMode { AUTO, IMMEDIATE }

/**
 * Locates retune boundaries in the byte stream. [wrap] runs on the caller's thread; the other
 * methods run on the reader thread. Offsets given to and returned by [scan] are sample-aligned.
 */
abstract class RetuneSync {
    /** Adds any marker commands to a retune's own commands. */
    abstract fun wrap(commands: List<TimedCommand>): List<TimedCommand>

    /** [count] more retunes were requested since the last call. */
    abstract fun onRetuneRequested(count: Int, bytesPerSample: Int, sampleRate: Int)

    /** Returns the offset within [len] bytes at [off] where fresh samples start, or -1 to keep discarding. */
    abstract fun scan(bytes: ByteArray, off: Int, len: Int): Int
}

/** No synchronisation: samples count as fresh as soon as a retune is requested. */
class ImmediateSync : RetuneSync() {
    override fun wrap(commands: List<TimedCommand>) = commands

    override fun onRetuneRequested(count: Int, bytesPerSample: Int, sampleRate: Int) {}

    override fun scan(bytes: ByteArray, off: Int, len: Int) = 0
}
