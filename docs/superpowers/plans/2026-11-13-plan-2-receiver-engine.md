# SpectraSDR Plan 2 — Receiver Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the Android-free signal engine: demodulators for every listening mode (broadcast FM with stereo and RDS, NFM, AM, SSB, CW), the spectrum analyzer, the `Receiver` with its DSP thread and a simulator source, all tested, with the throughput test of spec §12 passing.

**Architecture:** A source's thread hands pooled blocks to the `Receiver`, which queues about half a second of them. The receiver's DSP thread runs a `SpectrumAnalyzer` and a `ReceiverChain` (channelizer → demodulator → squelch → audio output) on every block and publishes spectrum frames, status and RDS data. Settings are immutable snapshots applied on the DSP thread, so the signal path needs no locks and allocates nothing once it runs. A `sim` package of signal generators feeds both the tests and the `SimulatorSource`.

**Tech Stack:** Kotlin 2.1.21 (JVM target 17), standard library only; JUnit 4.13.2 + hamcrest-core 1.3; `tools/test-core.sh` (kotlinc, no Gradle).

**Spec:** `docs/superpowers/specs/2026-11-12-spectrasdr-design.md` (sections cited as §n). The follow-ups this plan picks up are listed in `docs/superpowers/plans/2026-11-12-plan-1-completion.md`, section 5.

## Global Constraints

- License GPL-3.0-or-later; every Kotlin file starts with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- `:core` uses the Kotlin standard library only (spec D6); tests use JUnit 4.13.2 and hamcrest-core 1.3 only.
- Kotlin 2.1.21, `-jvm-target 17`.
- Package root `io.github.radhwanalhajjaj.spectrasdr.core`. Source directories omit the common root: `core/src/main/kotlin/demod/WfmDemod.kt` declares `package io.github.radhwanalhajjaj.spectrasdr.core.demod`.
- Complex samples are interleaved `FloatArray`s (I0, Q0, I1, Q1, …). Counts are complex samples unless a name says bytes or frames. Audio is interleaved frames.
- The signal path allocates nothing once it runs (§4.4): preallocated arrays, no boxing, and no collections, iterators or non-inline lambdas in code that runs per sample or per block. Task 14 measures it.
- Everything in `dsp`, `demod`, `rds`, and `ReceiverChain` with what it owns, is used by one thread only, the DSP thread. Members that other threads may call say so in their KDoc (`Receiver.update`, `Receiver.spectrum`, `Receiver.settings`, `Receiver.status`, `SpectrumAnalyzer.latest`).
- Run tests with `tools/test-core.sh [fully.qualified.TestClass …]` from the repository root. Tests are compiled separately from the main code, so they cannot see `internal` declarations.
- Every listing in this plan was compiled and run before the plan was written, and the test counts in the "Expected" lines come from that run. Enter the code as written. If a step does not behave as written, stop and report it instead of improvising.
- Every commit message ends with a `Co-Authored-By:` line that names the model making the commit, and the session line. The commit steps below show Claude Sonnet 5.5; change the name if another model does the work.
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
  ```

## Where this plan sits

| Plan | Scope | Spec milestone |
|------|-------|----------------|
| 1 (done) | Harness, DSP blocks, sources and protocols, retune sync, WAV/IQ files | M1 (part 1) |
| **2 (this)** | Signal generators, demodulators, stereo, RDS, spectrum analyzer, `Receiver`, simulator source, throughput test | M1 (part 2) |
| 3 | Gradle + Android app, CI and emulator tests, driver hand-off, receiver screen, GL waterfall, diagnostics. First installable APK | M2 |
| 4 | Sweep analyzer | M3 |
| 5 | Scanner and recording | M4 |
| 6 | Polish and release | M5 |

## Decisions this plan takes

The spec is the authority. Where this plan settles something the spec leaves open, or changes the spec's wording, it is listed here with the reason. Task 15 writes the changes into the spec.

1. **Automatic squelch takes its noise floor from the spectrum, not from the channel's own history** (changes §6.4). The spec's "20th percentile of recent channel power" rises to the level of a station that never stops transmitting, and the squelch then mutes that station. The floor is now the 20th percentile of a slowly averaged spectrum over the usable part of the stream, scaled to the channel filter's noise bandwidth. It is known within one spectrum frame after a retune, which the scanner (Plan 5) needs. While the channel takes up more than half of the stream (broadcast FM at 240 kS/s) the spectrum cannot tell the floor, and the automatic squelch stays open.
2. **Stereo follows ITU-R BS.450: pilot sin ωt, subcarrier sin 2ωt** (clarifies §6.4). The spec's "2cos 2θ" is shorthand. `Pll` reports the pilot as cos θ, so the code multiplies by −2 sin 2θ.
3. **Stereo blends to mono on noise measured in quadrature to the subcarrier** (fills in §6.4's "noisy S"). The transmitter puts nothing on cos 2θ, so what is found there is noise. Measured in 2 kHz, relative to full deviation: full stereo up to −52 dB, mono from −40 dB, linear in dB between. These correspond to carrier-to-noise ratios of about 28 dB and 16 dB in 240 kHz.
4. **The listening receiver does not re-probe the retune latency on a timer** (changes §7.1). A probe silences the audio for one to three seconds, and a stale latency costs a listener a few milliseconds of the previous frequency. Every new connection probes, as Plan 1 built it. The sweep and the scanner, which need exact boundaries, re-probe before they start (Plans 4 and 5).
5. **Decimation factors are chosen so that every stage is at most 8** (changes §6.3; Plan 1 follow-up). Broadcast FM takes the largest such factor that leaves 240–480 kHz (3.2 MS/s: 12 instead of 13), narrow modes the largest that leaves 48–96 kHz.
6. **Tuning policy.** A new frequency inside the stream moves only the channel (§7.1 step 4). The tuner moves when the channel would leave the usable part of the stream (0.75 of it, 0.85 for SDRplay, §7) or, in the narrow modes, come within 1 kHz of its centre, where DC removal cuts a notch that would take the carrier out of an AM signal. When it moves, broadcast FM is centred and narrow modes are placed a quarter of the sample rate above the centre.
7. **`IqSource.setSampleRate` returns a tune epoch**, like `setCenterFrequency` (changes Plan 1's interface). A change of rate is a change of the stream; the receiver discards what was taken before it.
8. **Lowest sample rates:** 120 kS/s for broadcast FM, 48 kS/s for the other modes. Below that the receiver shows the spectrum and reports that it is not demodulating.
9. **The AGC rests at full gain and clamps its output to ±1.0** (Plan 1 follow-up). Starting at unity gain instead would cost a weak signal about two seconds to come up. The first milliseconds of a loud signal after silence clip.
10. **Broadcast FM hands its multiplex to a `MultiplexTap`**, which the RDS demodulator implements, so `demod` does not depend on `rds`.
11. **RDS block sync is dropped after 50 blocks without an error-free one.** 367 of the 1,023 non-zero syndromes look like a correctable burst, so corrected blocks alone prove nothing. A value from a corrected block is shown once it has arrived twice in a row; a value from an error-free block is shown at once (§6.4).
12. **One `Receiver` is one session.** It opens and closes its source and cannot be restarted, as one `RtlTcpClient` is one connection. A reconnect makes a new source and a new receiver from the old receiver's `settings` (Plan 3).
13. **A retune the source never confirms is given up after five seconds of samples**, with a warning, so a source that loses its markers cannot silence the receiver for ever.
14. **The simulator renders a station only while all of it lies inside the stream**, and applies retunes at block boundaries, so its spectrum is clean and its tune epochs exact.

## Plan 1 follow-ups and where they land

| Follow-up (completion report §5, "Before Plan 2") | Where |
|---|---|
| `Agc` start-up gain, no output clamp, no reset | Task 1 (decision 9) |
| A NaN poisons `Agc`, `DcBlocker`, `Deemphasis` | Task 1; `AudioOutput` silences what is left (Task 10) |
| `DcBlocker` and `Deemphasis` stick in denormal values | Task 1 |
| `RtlTcpClient` should clear its block reference before `onBlock` | Task 2 |
| `Nco` is not thread-safe | Task 12: tuning is applied on the DSP thread only |
| The receiver owns the latency re-probe | Decision 4; spec aligned in Task 15 |
| Decimation factors with stages of at most 8; `planStages` KDoc | Tasks 10 and 14 (decision 5) |
| `Pll`: float phase can reach 2π; `hypot`/`atan2` per sample | Task 1: the phase stays below 2π; per sample the `hypot`, one `cos` and the stereo decoder's own `sin`/`cos` pair are gone; `atan2` stays as the phase detector |
| `FractionalResampler` output-size validation; work-buffer sizing | Task 1. Buffers grow to the largest block and are then reused; Task 14 proves nothing is allocated once the path runs |
| Align spec §7.1 with the probe as built | Task 15 |

## File Structure

| File | Responsibility |
|------|----------------|
| `core/src/main/kotlin/dsp/Biquad.kt` | Butterworth low-pass and high-pass sections |
| `core/src/main/kotlin/dsp/Select.kt` | The k-th smallest value without sorting or allocating |
| `core/src/main/kotlin/rds/RdsCode.kt` | The (26,16) block code, offset words, burst-error table, chip pulse |
| `core/src/main/kotlin/rds/RdsDecoder.kt` | Block sync, error correction, groups → `RdsState` |
| `core/src/main/kotlin/rds/RdsDemodulator.kt` | 57 kHz subcarrier → bits |
| `core/src/main/kotlin/sim/Oscillator.kt`, `SineTable.kt`, `FmModulator.kt` | Tone and FM generation without per-sample trigonometry |
| `core/src/main/kotlin/sim/RdsEncoder.kt`, `RdsBaseband.kt`, `MpxGenerator.kt` | RDS groups, shaped RDS baseband, FM stereo multiplex |
| `core/src/main/kotlin/sim/SimBand.kt` | `SimStation` kinds and their rendering into an IQ stream |
| `core/src/main/kotlin/demod/Demodulator.kt` | `Demodulator` and `MultiplexTap` interfaces |
| `core/src/main/kotlin/demod/FmDiscriminator.kt`, `StereoDecoder.kt`, `WfmDemod.kt` | Broadcast FM |
| `core/src/main/kotlin/demod/NfmDemod.kt`, `AmDemod.kt`, `PassbandDemod.kt` | NFM, AM, SSB and CW |
| `core/src/main/kotlin/demod/Squelch.kt` | Level squelch with hysteresis and gate ramps |
| `core/src/main/kotlin/spectrum/SpectrumAnalyzer.kt` | Spectrum frames, noise-floor estimate, display binning |
| `core/src/main/kotlin/engine/AudioSink.kt`, `Mode.kt` | Sink interface; listening modes and their bandwidths |
| `core/src/main/kotlin/engine/Channelizer.kt` | NCO, decimation, channel filter, channel power |
| `core/src/main/kotlin/engine/AudioOutput.kt` | Resampling with drift control, volume, mute, soft limiter |
| `core/src/main/kotlin/engine/ReceiverChain.kt` | The listening path for one mode at one sample rate |
| `core/src/main/kotlin/engine/TuningPolicy.kt` | Where the tuner sits for a wanted frequency |
| `core/src/main/kotlin/engine/ReceiverSettings.kt` | `ReceiverSettings`, `ReceiverStatus`, `ReceiverListener` |
| `core/src/main/kotlin/engine/Receiver.kt` | Queue, DSP thread, settings, tuning, status |
| `core/src/main/kotlin/source/SimulatorSource.kt` | The simulated band as a paced, tunable source |
| `core/src/test/kotlin/testutil/CollectingSink.kt`, `TestStations.kt`, `ScriptedSource.kt` | Test sink, wide-band test signals, lock-step test source |
| `core/src/test/kotlin/rds/RdsTestSignals.kt` | An FM station with RDS through the demodulators |
| `core/src/test/kotlin/**/…Test.kt` | One test class per unit |

Modified from Plan 1: `dsp/Agc.kt`, `DcBlocker.kt`, `Deemphasis.kt`, `Pll.kt`, `FractionalResampler.kt`, `DecimatorChain.kt`; `source/BlockQueue.kt`, `IqSource.kt`, `WavIqFileSource.kt`, `rtltcp/RtlTcpClient.kt`; their tests; `testutil/Signals.kt`.

## How to read the steps

Three kinds of file instruction appear, always followed by a code block:

- **Create `path`:** the file does not exist yet; the block is its whole content.
- **Replace the whole of `path` with:** the file exists; the block is its whole new content.
- **In `path`, replace: … with: …** the first block occurs exactly once in the file; put the second block in its place. Each block holds whole lines.

---

### Task 1: Harden the small DSP blocks

Plan 1's reviewers left notes on five blocks that the demodulators are about to lean on. This task clears them before anything is built on top.

**Files:**
- Modify: `core/src/main/kotlin/dsp/Agc.kt`, `core/src/main/kotlin/dsp/DcBlocker.kt`, `core/src/main/kotlin/dsp/Deemphasis.kt`, `core/src/main/kotlin/dsp/Pll.kt`, `core/src/main/kotlin/dsp/FractionalResampler.kt`
- Test (modify): `core/src/test/kotlin/dsp/AgcTest.kt`, `DcBlockerTest.kt`, `DeemphasisTest.kt`, `PllTest.kt`, `FractionalResamplerTest.kt`

**Interfaces:**
- Consumes: Plan 1's classes as they are.
- Produces (everything not named keeps its Plan 1 signature):
  - `Agc.reset()` returns to full gain. `Agc.process` clamps its output to ±1.0 and turns a NaN or infinite input sample into 0 without touching the state.
  - `DcBlocker.reset()` and `Deemphasis.reset()`. Both clear state that is not a number or smaller than 1e-30 after every block.
  - `Pll.processSinCos(input: FloatArray, count: Int, sinOut: FloatArray, cosOut: FloatArray)` writes sin θ and cos θ instead of θ. `Pll.reset()`. `Pll.process` now guarantees 0 ≤ θ < 2π after rounding to float. `Pll.amplitude` is computed when read.
  - `FractionalResampler.process` throws `IllegalArgumentException` unless `output.size >= channels * maxOutput(frames)`.

- [ ] **Step 1: Write the failing tests**

In `core/src/test/kotlin/dsp/AgcTest.kt`, replace:

```kotlin
        assertTrue(Signals.peak(x, 24_000, 48_000) <= 1e-3f * 1.01f)
    }
}
```

with:

```kotlin
        assertTrue(Signals.peak(x, 24_000, 48_000) <= 1e-3f * 1.01f)
    }

    @Test
    fun outputNeverExceedsFullScale() {
        val agc = Agc(fs)
        val silence = FloatArray(4_800)
        agc.process(silence, silence.size)           // the gain rests at its maximum
        val loud = Signals.realTone(4_800, 1_000.0, fs, 0.8)
        agc.process(loud, loud.size)
        assertEquals("the onset clips", 1f, Signals.peak(loud, 0, 48), 0f)
        assertTrue(Signals.peak(loud) <= 1f)
        assertEquals(0.5f, Signals.peak(loud, 2_400, 4_800), 0.05f)
    }

    @Test
    fun samplesThatAreNotNumbersAreMutedAndLeaveNoTrace() {
        val agc = Agc(fs)
        val x = Signals.realTone(9_600, 1_000.0, fs, 0.1)
        val clean = x.copyOf()
        Agc(fs).process(clean, clean.size)
        x[100] = Float.NaN
        x[101] = Float.POSITIVE_INFINITY
        agc.process(x, x.size)
        assertEquals(0f, x[100], 0f)
        assertEquals(0f, x[101], 0f)
        for (i in 4_800 until 9_600) assertEquals(clean[i], x[i], 1e-3f)
    }

    @Test
    fun resetReturnsToFullGain() {
        val agc = Agc(fs, maxGainDb = 40.0)
        val loud = Signals.realTone(4_800, 1_000.0, fs, 0.5)
        agc.process(loud, loud.size)
        agc.reset()
        val quiet = floatArrayOf(1e-4f)
        agc.process(quiet, 1)
        assertEquals("40 dB of gain", 1e-2f, quiet[0], 1e-4f)
    }
}
```

In `core/src/test/kotlin/dsp/DcBlockerTest.kt`, replace:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Test
```

with:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
```

In `core/src/test/kotlin/dsp/DcBlockerTest.kt`, replace:

```kotlin
        assertEquals(0.5f, Signals.peak(x, 24_000, 48_000), 0.005f)
    }
}
```

with:

```kotlin
        assertEquals(0.5f, Signals.peak(x, 24_000, 48_000), 0.005f)
    }

    @Test
    fun recoversInTheBlockAfterASampleThatIsNotANumber() {
        val fs = 48_000.0
        val blocker = DcBlocker(1, 20.0, fs)
        val bad = Signals.realTone(480, 1_000.0, fs, 0.5)
        bad[10] = Float.NaN
        blocker.process(bad, bad.size)
        val good = Signals.realTone(4_800, 1_000.0, fs, 0.5)
        blocker.process(good, good.size)
        assertTrue(good.all { it.isFinite() })
        assertEquals(0.5f, Signals.peak(good, 2_400, 4_800), 0.01f)
    }

    @Test
    fun silenceLeavesTheStateAtExactlyZero() {
        val fs = 48_000.0
        val blocker = DcBlocker(1, 20.0, fs)
        val x = Signals.realTone(480, 1_000.0, fs, 0.5)
        blocker.process(x, x.size)
        // 60 s of silence in blocks, as a closed squelch produces; the tail must not hover in denormals.
        val silence = FloatArray(4_800)
        repeat(600) {
            silence.fill(0f)
            blocker.process(silence, silence.size)
        }
        assertEquals(0f, silence[4_799], 0f)
        val impulse = floatArrayOf(1e-20f)
        blocker.process(impulse, 1)
        assertEquals("no residue is added", 1e-20f, impulse[0], 0f)
    }

    @Test
    fun resetClearsTheState() {
        val blocker = DcBlocker(1, 20.0, 48_000.0)
        val x = FloatArray(100) { 1f }
        blocker.process(x, x.size)
        blocker.reset()
        val y = floatArrayOf(0f)
        blocker.process(y, 1)
        assertEquals(0f, y[0], 0f)
    }
}
```

In `core/src/test/kotlin/dsp/DeemphasisTest.kt`, replace:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
```

with:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
```

In `core/src/test/kotlin/dsp/DeemphasisTest.kt`, replace:

```kotlin
        assertEquals(0f, x[1_999], 0f)
    }
}
```

with:

```kotlin
        assertEquals(0f, x[1_999], 0f)
    }

    @Test
    fun recoversInTheBlockAfterASampleThatIsNotANumber() {
        val filter = Deemphasis(1, 50e-6, 48_000.0)
        val bad = FloatArray(100) { 0.5f }
        bad[10] = Float.NaN
        filter.process(bad, bad.size)
        val good = FloatArray(1_000) { 0.5f }
        filter.process(good, good.size)
        assertTrue(good.all { it.isFinite() })
        assertEquals(0.5f, good[999], 1e-4f)
    }

    @Test
    fun silenceLeavesTheStateAtExactlyZero() {
        val filter = Deemphasis(1, 50e-6, 48_000.0)
        val x = FloatArray(100) { 0.5f }
        filter.process(x, x.size)
        val silence = FloatArray(480)
        repeat(3) {
            silence.fill(0f)
            filter.process(silence, silence.size)
        }
        assertEquals(0f, silence[479], 0f)
        val impulse = floatArrayOf(0f)
        filter.process(impulse, 1)
        assertEquals(0f, impulse[0], 0f)
    }

    @Test
    fun resetClearsTheState() {
        val filter = Deemphasis(2, 50e-6, 48_000.0)
        val x = FloatArray(200) { 1f }
        filter.process(x, 100)
        filter.reset()
        val y = floatArrayOf(0f, 0f)
        filter.process(y, 1)
        assertEquals(0f, y[0], 0f)
        assertEquals(0f, y[1], 0f)
    }
}
```

In `core/src/test/kotlin/dsp/PllTest.kt`, replace:

```kotlin
        assertTrue("amplitude ${pll.amplitude}", pll.amplitude < 1e-6)
    }
}
```

with:

```kotlin
        assertTrue("amplitude ${pll.amplitude}", pll.amplitude < 1e-6)
    }

    private fun pilot(n: Int) = FloatArray(n) { (0.09 * cos(2 * PI * 19_000.0 * it / fs)).toFloat() }

    @Test
    fun phaseStaysBelowTwoPi() {
        val n = 480_000
        val phase = FloatArray(n)
        Pll(fs, 19_000.0).process(pilot(n), n, phase)
        for (i in 0 until n) assertTrue("phase[$i] = ${phase[i]}", phase[i] >= 0f && phase[i].toDouble() < 2 * PI)
    }

    @Test
    fun sineAndCosineOutputsMatchThePhase() {
        val n = 48_000
        val x = pilot(n)
        val phase = FloatArray(n)
        val sine = FloatArray(n)
        val cosine = FloatArray(n)
        Pll(fs, 19_000.0).process(x, n, phase)
        val pll = Pll(fs, 19_000.0)
        pll.processSinCos(x, n, sine, cosine)
        assertTrue(pll.locked)
        for (i in 0 until n) {
            assertEquals(sin(phase[i].toDouble()), sine[i].toDouble(), 1e-5)
            assertEquals(cos(phase[i].toDouble()), cosine[i].toDouble(), 1e-5)
        }
    }

    @Test
    fun resetDropsTheLock() {
        val n = 240_000
        val pll = Pll(fs, 19_000.0)
        pll.process(pilot(n), n, FloatArray(n))
        assertTrue(pll.locked)
        pll.reset()
        assertFalse(pll.locked)
        assertEquals(0.0, pll.amplitude, 0.0)
        assertEquals(19_000.0, pll.frequencyHz, 1e-9)
    }
}
```

In `core/src/test/kotlin/dsp/FractionalResamplerTest.kt`, replace:

```kotlin
        for (i in 0 until mWhole) assertEquals(outWhole[i], outSplit[i], 1e-6f)
    }
}
```

with:

```kotlin
        for (i in 0 until mWhole) assertEquals(outWhole[i], outSplit[i], 1e-6f)
    }

    @Test
    fun rejectsAnOutputBufferThatCouldOverflow() {
        val r = FractionalResampler(2, 12_000.0, 48_000.0)
        val input = FloatArray(2 * 100)
        r.process(input, 100, FloatArray(2 * r.maxOutput(100)))
        try {
            r.process(input, 100, FloatArray(2 * 400))   // 100 frames in give 400 out; the bound is higher
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("100 input frames"))
        }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.AgcTest`
Expected: the build fails with `unresolved reference 'processSinCos'` (and `'reset'`): the new members do not exist yet.

- [ ] **Step 3: Write the implementation**

Replace the whole of `core/src/main/kotlin/dsp/Agc.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Peak-tracking AGC for real audio (AM, SSB, CW — §6.4): fast attack, hang, then slow decay. The
 * output peak settles at [targetPeak]; gain never exceeds [maxGainDb]. The output is clamped to
 * ±1.0, so the first milliseconds of a loud signal after silence clip instead of overshooting.
 */
class Agc(
    sampleRate: Double,
    attackMs: Double = 2.0,
    decayMs: Double = 500.0,
    hangMs: Double = 200.0,
    private val targetPeak: Float = 0.5f,
    maxGainDb: Double = 60.0,
) {
    private val attack = (1 - exp(-1000.0 / (attackMs * sampleRate))).toFloat()
    private val decay = (1 - exp(-1000.0 / (decayMs * sampleRate))).toFloat()
    private val hangSamples = (hangMs * sampleRate / 1000).toInt()
    private val minEnvelope = (targetPeak / 10.0.pow(maxGainDb / 20)).toFloat()
    private var envelope = minEnvelope
    private var hang = 0

    fun process(data: FloatArray, count: Int) {
        var env = envelope
        var h = hang
        for (i in 0 until count) {
            val x = data[i]
            val a = abs(x)
            if (!(a <= Float.MAX_VALUE)) {   // NaN or infinite: mute the sample and keep the state
                data[i] = 0f
                continue
            }
            if (a > env) {
                env += (a - env) * attack
                h = hangSamples
            } else if (h > 0) {
                h--
            } else {
                env += (a - env) * decay
            }
            if (env < minEnvelope) env = minEnvelope
            val y = x * (targetPeak / env)
            data[i] = if (y > 1f) 1f else if (y < -1f) -1f else y
        }
        envelope = env
        hang = h
    }

    /** Returns to the resting state (full gain), for use after a retune. */
    fun reset() {
        envelope = minEnvelope
        hang = 0
    }
}
```

Replace the whole of `core/src/main/kotlin/dsp/DcBlocker.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * One-pole DC blocker y[n] = x[n] − x[n−1] + a·y[n−1], per interleaved channel (§6.1). After every
 * block, state that is not a number or has decayed to almost nothing is cleared, so one bad sample
 * cannot poison the filter and silence does not leave it grinding through denormal numbers.
 */
class DcBlocker(private val channels: Int, cutoffHz: Double, sampleRate: Double) {
    private val a = exp(-2 * PI * cutoffHz / sampleRate).toFloat()
    private val prevX = FloatArray(channels)
    private val prevY = FloatArray(channels)

    /** Filters [frames] frames of [data] in place. */
    fun process(data: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = data[i]
                val y = x - prevX[c] + a * prevY[c]
                prevX[c] = x
                prevY[c] = y
                data[i] = y
                i++
            }
        }
        for (c in 0 until channels) {
            // Written so that NaN fails the tests too.
            if (!(abs(prevX[c]) <= Float.MAX_VALUE)) prevX[c] = 0f
            val y = abs(prevY[c])
            if (!(y >= TINY && y <= Float.MAX_VALUE)) prevY[c] = 0f
        }
    }

    fun reset() {
        prevX.fill(0f)
        prevY.fill(0f)
    }

    private companion object {
        const val TINY = 1e-30f
    }
}
```

Replace the whole of `core/src/main/kotlin/dsp/Deemphasis.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs
import kotlin.math.exp

/**
 * FM de-emphasis: one-pole low-pass with time constant [tauSeconds] (50 µs or 75 µs), per channel.
 * After every block, state that is not a number or has decayed to almost nothing is cleared.
 */
class Deemphasis(private val channels: Int, tauSeconds: Double, sampleRate: Double) {
    private val alpha = (1 - exp(-1 / (sampleRate * tauSeconds))).toFloat()
    private val state = FloatArray(channels)

    fun process(data: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                state[c] += alpha * (data[i] - state[c])
                data[i] = state[c]
                i++
            }
        }
        for (c in 0 until channels) {
            val s = abs(state[c])
            if (!(s >= TINY && s <= Float.MAX_VALUE)) state[c] = 0f   // also clears NaN
        }
    }

    fun reset() = state.fill(0f)

    private companion object {
        const val TINY = 1e-30f
    }
}
```

Replace the whole of `core/src/main/kotlin/dsp/Pll.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order PLL that locks to a real sinusoid near [centerHz] (the 19 kHz stereo pilot, §6.4).
 * The input is mixed to baseband and low-passed by a two-pole arm filter, so the phase error is
 * amplitude-independent and signals more than a few kHz away do not disturb the loop. When locked,
 * input ≈ amplitude · cos(θ), where θ is the loop phase reported for every sample.
 * [minLockAmplitude] is the smallest tracked amplitude (same scale as [amplitude]) that can count
 * as lock, so silence and very weak input read as unlocked.
 */
class Pll(
    private val sampleRate: Double,
    private val centerHz: Double,
    loopBandwidthHz: Double = 10.0,
    maxDeviationHz: Double = 20.0,
    armFilterHz: Double = 400.0,
    damping: Double = 0.707,
    minLockAmplitude: Double = 1e-3,
) {
    private val twoPi = 2 * PI
    private val armAlpha = 1 - exp(-twoPi * armFilterHz / sampleRate)
    private val lockAlpha = 1 - exp(-twoPi * 2.0 / sampleRate)
    private val centerStep = twoPi * centerHz / sampleRate
    private val maxOffset = twoPi * maxDeviationHz / sampleRate
    private val minHalfAmplitudeSquared = (minLockAmplitude / 2) * (minLockAmplitude / 2)
    private val kp: Double
    private val ki: Double
    private var phase = 0.0
    private var freqOffset = 0.0
    private var aRe = 0.0
    private var aIm = 0.0
    private var zRe = 0.0
    private var zIm = 0.0
    private var lockMetric = 0.0

    init {
        val wn = twoPi * loopBandwidthHz / sampleRate
        kp = 2 * damping * wn
        ki = wn * wn
    }

    /** True while the loop holds phase lock. */
    val locked: Boolean get() = lockMetric > 0.8

    /** Estimated amplitude of the tracked sinusoid. */
    val amplitude: Double get() = 2 * sqrt(zRe * zRe + zIm * zIm)

    /** Tracked frequency in Hz. */
    val frequencyHz: Double get() = (centerStep + freqOffset) * sampleRate / twoPi

    /** Tracks [count] samples of [input] and writes the loop phase θ, 0 ≤ θ < 2π, for every sample. */
    fun process(input: FloatArray, count: Int, phaseOut: FloatArray) = run(input, count, phaseOut, null, null)

    /** As [process], but writes sin θ and cos θ, which the loop computes anyway, instead of θ. */
    fun processSinCos(input: FloatArray, count: Int, sinOut: FloatArray, cosOut: FloatArray) =
        run(input, count, null, sinOut, cosOut)

    private fun run(input: FloatArray, count: Int, phaseOut: FloatArray?, sinOut: FloatArray?, cosOut: FloatArray?) {
        for (n in 0 until count) {
            val x = input[n].toDouble()
            val c = cos(phase)
            val s = sin(phase)
            // For x = A·cos(θ + φ), mixing with e^(−jθ) leaves (A/2)·e^(jφ) after the arm filter.
            aRe += armAlpha * (x * c - aRe)
            aIm += armAlpha * (-x * s - aIm)
            zRe += armAlpha * (aRe - zRe)
            zIm += armAlpha * (aIm - zIm)
            val err = atan2(zIm, zRe)
            val magnitudeSquared = zRe * zRe + zIm * zIm
            // cos(err) without a second trigonometric call; weak input counts as unlocked.
            val quality = if (magnitudeSquared <= minHalfAmplitudeSquared) 0.0 else zRe / sqrt(magnitudeSquared)
            lockMetric += lockAlpha * (quality - lockMetric)
            freqOffset = (freqOffset + ki * err).coerceIn(-maxOffset, maxOffset)
            if (phaseOut != null) {
                val p = phase.toFloat()
                phaseOut[n] = if (p < TWO_PI_FLOAT) p else 0f   // rounding to float must not reach 2π
            }
            if (sinOut != null && cosOut != null) {
                sinOut[n] = s.toFloat()
                cosOut[n] = c.toFloat()
            }
            phase += centerStep + freqOffset + kp * err
            if (phase >= twoPi) phase -= twoPi else if (phase < 0) phase += twoPi
        }
    }

    /** Drops the lock and all loop state, for use after a retune. */
    fun reset() {
        phase = 0.0
        freqOffset = 0.0
        aRe = 0.0
        aIm = 0.0
        zRe = 0.0
        zIm = 0.0
        lockMetric = 0.0
    }

    private companion object {
        /** The smallest float that is not below 2π. */
        val TWO_PI_FLOAT = (2 * PI).toFloat()
    }
}
```

In `core/src/main/kotlin/dsp/FractionalResampler.kt`, replace:

```kotlin
    fun maxOutput(frames: Int): Int = (frames / (baseStep * (1 - abs(correction) - 1e-3))).toInt() + 2

    /** Resamples [frames] interleaved frames from [input] into [output]; returns output frames. */
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        val hist = tapsPerPhase - 1
        val need = channels * (hist + frames)
```

with:

```kotlin
    fun maxOutput(frames: Int): Int = (frames / (baseStep * (1 - abs(correction) - 1e-3))).toInt() + 2

    /**
     * Resamples [frames] interleaved frames from [input] into [output]; returns output frames.
     * [output] must hold [maxOutput] frames for the correction in force.
     */
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        require(output.size >= channels * maxOutput(frames)) {
            "output holds ${output.size} values but $frames input frames can produce ${channels * maxOutput(frames)}"
        }
        val hist = tapsPerPhase - 1
        val need = channels * (hist + frames)
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.AgcTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlockerTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DeemphasisTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.PllTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.FractionalResamplerTest`
Expected: `OK (28 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (118 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
fix(core): harden AGC, DC blocker, de-emphasis, PLL and resampler

The AGC clamps its output and can be reset; the one-pole filters drop
NaN and denormal state after every block; the PLL can hand out sin and
cos of its phase and keeps the float phase below 2π; the resampler
checks its output buffer.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 2: Source layer follow-ups

**Files:**
- Modify: `core/src/main/kotlin/source/BlockQueue.kt`, `core/src/main/kotlin/source/IqSource.kt`, `core/src/main/kotlin/source/WavIqFileSource.kt`, `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`
- Test (modify): `core/src/test/kotlin/source/BlockQueueTest.kt`, `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`

**Interfaces:**
- Consumes: Plan 1's source classes and `FakeRtlTcpServer`.
- Produces:
  - `BlockQueue.capacity` is a `var` (at least 1). After it is lowered, the next `offer` drops the surplus oldest blocks; each counts as an overrun.
  - `IqSource.setSampleRate(sps: Int): Int` returns a tune epoch like `setCenterFrequency`: blocks with `tuneEpoch` ≥ it were taken after the change. `RtlTcpClient` returns the epoch of the retune it performs; `WavIqFileSource` ignores the request and returns 0.
  - `RtlTcpClient` gives up its own reference to a block before it calls `onBlock`. A listener that releases the block and then throws is reported with its own exception, not with "IqBlock released twice".

- [ ] **Step 1: Write the failing tests**

In `core/src/test/kotlin/source/BlockQueueTest.kt`, replace:

```kotlin
        assertEquals(3, pool.freeCount)
    }
}
```

with:

```kotlin
        assertEquals(3, pool.freeCount)
    }

    @Test
    fun loweringTheCapacityDropsTheOldestBlocksAtTheNextOffer() {
        val pool = BlockPool(4)
        val q = BlockQueue(8)
        for (i in 0 until 6) q.offer(pool.obtain().apply { seq = i.toLong() })
        q.capacity = 3
        assertEquals(6, q.size)
        q.offer(pool.obtain().apply { seq = 6 })
        assertEquals(3, q.size)
        assertEquals(4L, q.overruns)
        assertEquals(4, pool.freeCount)
        assertEquals(4L, q.take(10)!!.seq)
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacityMustBePositive() {
        BlockQueue(4).capacity = 0
    }
}
```

In `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`, replace:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit.SECONDS

class RtlTcpClientTest {
```

with:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class RtlTcpClientTest {
```

In `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`, replace:

```kotlin

    @Test
    fun reportsAServerThatAcceptsButSendsNothing() {
        ServerSocket(0).use { silent ->
```

with:

```kotlin

    @Test
    fun sampleRateChangesReturnTheirEpoch() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            l.awaitEpochSamples(epoch = 1, complex = 1_000)
            val epoch = c.setSampleRate(1_024_000)
            assertEquals(2, epoch)
            l.awaitEpochSamples(epoch = epoch, complex = 1_000)
            assertTrue(l.blocksOf(epoch).all { it.sampleRate == 1_024_000 })
            c.close()
        }
    }

    @Test
    fun aListenerThatFailsAfterReleasingItsBlockIsReportedAsItself() {
        val uncaught = AtomicReference<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        try {
            FakeRtlTcpServer().start().use { server ->
                val stopped = CountDownLatch(1)
                val c = client(server.port)
                c.open(object : SourceListener {
                    override fun onStarted(info: SourceInfo) {}

                    override fun onBlock(block: IqBlock) {
                        block.release()
                        throw IllegalStateException("listener failed")
                    }

                    override fun onError(error: SourceError) {}

                    override fun onStopped() = stopped.countDown()
                })
                assertTrue(stopped.await(5, SECONDS))
                val deadline = System.currentTimeMillis() + 5_000
                while (uncaught.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
                // Before the fix the reader released the block a second time and that error hid this one.
                assertEquals("listener failed", uncaught.get()?.message)
                c.close()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun reportsAServerThatAcceptsButSendsNothing() {
        ServerSocket(0).use { silent ->
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueueTest`
Expected: the build fails with `'val' cannot be reassigned`: `capacity` is still a `val`.

- [ ] **Step 3: Write the implementation**

In `core/src/main/kotlin/source/BlockQueue.kt`, replace:

```kotlin
 * back to its pool and [overruns] increases, so a slow consumer never stalls the source (§4.3).
 */
class BlockQueue(val capacity: Int) {
    private val items = ArrayDeque<IqBlock>(capacity)
    private val lock = ReentrantLock()
```

with:

```kotlin
 * back to its pool and [overruns] increases, so a slow consumer never stalls the source (§4.3).
 */
class BlockQueue(capacity: Int) {
    /** Blocks held at most. Lowering it takes effect at the next [offer], which drops the surplus. */
    @Volatile
    var capacity: Int = capacity
        set(value) {
            require(value >= 1) { "capacity must be >= 1" }
            field = value
        }

    private val items = ArrayDeque<IqBlock>(capacity)
    private val lock = ReentrantLock()
```

In `core/src/main/kotlin/source/BlockQueue.kt`, replace:

```kotlin
    fun offer(block: IqBlock) {
        var dropped: IqBlock? = null
        lock.withLock {
            if (items.size >= capacity) {
                dropped = items.removeFirst()
                overruns++
            }
            items.addLast(block)
```

with:

```kotlin
    fun offer(block: IqBlock) {
        var dropped: IqBlock? = null
        var more: ArrayList<IqBlock>? = null   // only after the capacity was lowered
        lock.withLock {
            while (items.size >= capacity) {
                val oldest = items.removeFirst()
                overruns++
                if (dropped == null) dropped = oldest else (more ?: ArrayList<IqBlock>().also { more = it }).add(oldest)
            }
            items.addLast(block)
```

In `core/src/main/kotlin/source/BlockQueue.kt`, replace:

```kotlin
        }
        dropped?.release()
    }
```

with:

```kotlin
        }
        dropped?.release()
        more?.forEach { it.release() }
    }
```

In `core/src/main/kotlin/source/IqSource.kt`, replace:

```kotlin

/**
 * A stream of IQ samples produced on the source's own thread. [setCenterFrequency] returns a tune
 * epoch E: blocks with tuneEpoch ≥ E are at the requested frequency or a later one (§7.1).
 * A source whose `info.canTune` is false ignores the request and returns its current epoch.
 */
interface IqSource {
```

with:

```kotlin

/**
 * A stream of IQ samples produced on the source's own thread. [setCenterFrequency] and
 * [setSampleRate] return a tune epoch E: blocks with tuneEpoch ≥ E were taken after the change
 * (§7.1). A source that cannot tune (`info.canTune` is false) or has a fixed sample rate ignores
 * the request and returns its current epoch; every block states its own frequency and rate.
 */
interface IqSource {
```

In `core/src/main/kotlin/source/IqSource.kt`, replace:

```kotlin
    fun setCenterFrequency(hz: Long): Int

    fun setSampleRate(sps: Int)

    fun setGain(gain: GainSetting)
```

with:

```kotlin
    fun setCenterFrequency(hz: Long): Int

    fun setSampleRate(sps: Int): Int

    fun setGain(gain: GainSetting)
```

In `core/src/main/kotlin/source/WavIqFileSource.kt`, replace:

```kotlin
    override fun setCenterFrequency(hz: Long): Int = 0

    override fun setSampleRate(sps: Int) {}

    override fun setGain(gain: GainSetting) {}
```

with:

```kotlin
    override fun setCenterFrequency(hz: Long): Int = 0

    /** Ignored: the file plays at its recorded rate. */
    override fun setSampleRate(sps: Int): Int = 0

    override fun setGain(gain: GainSetting) {}
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
    }

    override fun setSampleRate(sps: Int) {
        retune(listOf(TimedCommand(RtlTcpProtocol.SET_SAMPLE_RATE, sps))) {
            requestedRate = sps
            sampleRate = sps
        }
    }

    override fun setGain(gain: GainSetting) {
```

with:

```kotlin
    }

    override fun setSampleRate(sps: Int): Int =
        retune(listOf(TimedCommand(RtlTcpProtocol.SET_SAMPLE_RATE, sps))) {
            requestedRate = sps
            sampleRate = sps
        }

    override fun setGain(gain: GainSetting) {
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
                if (probeRequested && !discarding && have == 0) {
                    probeRequested = false
                    block?.let { if (it.length > 0) listener.onBlock(it) else it.release() }
                    block = null
                    runProbe(input)
                    continue
```

with:

```kotlin
                if (probeRequested && !discarding && have == 0) {
                    probeRequested = false
                    val partial = block
                    block = null   // cleared first: the listener owns the block from here on
                    if (partial != null) {
                        if (partial.length > 0) listener.onBlock(partial) else partial.release()
                    }
                    runProbe(input)
                    continue
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
                        observedEpoch = newEpoch
                        discarding = true
                        block?.let { if (it.length > 0) listener.onBlock(it) else it.release() }
                        block = null
                    }
                    if (discarding) {
```

with:

```kotlin
                        observedEpoch = newEpoch
                        discarding = true
                        val partial = block
                        block = null
                        if (partial != null) {
                            if (partial.length > 0) listener.onBlock(partial) else partial.release()
                        }
                    }
                    if (discarding) {
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
                    pos += got * bps
                    if (b.length == b.capacity) {
                        listener.onBlock(b)
                        block = null
                    }
                }
```

with:

```kotlin
                    pos += got * bps
                    if (b.length == b.capacity) {
                        block = null
                        listener.onBlock(b)
                    }
                }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueueTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClientTest`
Expected: `OK (22 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (122 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): adjustable queue capacity, sample-rate epochs, safer block hand-over

BlockQueue.capacity can be lowered while in use. IqSource.setSampleRate
returns a tune epoch. RtlTcpClient clears its block reference before
onBlock, so a failing listener is reported with its own exception.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 3: Biquad filters and tone measurements

The narrow FM demodulator needs steep audio filters at 300 Hz and 3.5 kHz, where FIR filters would be long; the stereo decoder needs a narrow low-pass at 240 kHz. The demodulator tests need to measure a tone's amplitude and its SINAD.

**Files:**
- Create: `core/src/main/kotlin/dsp/Biquad.kt`
- Modify: `core/src/test/kotlin/testutil/Signals.kt`
- Test: `core/src/test/kotlin/dsp/BiquadTest.kt`

**Interfaces:**
- Consumes: `Signals` (Plan 1).
- Produces:
  - `class BiquadCascade` (private constructor) with `fun process(data: FloatArray, frames: Int)` (in place, interleaved channels), `fun reset()`, and in its companion `fun lowPass(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double): BiquadCascade` and `fun highPass(…)` with the same parameters. `order` is even; the cutoff is the −3 dB point.
  - `Signals.toneAmplitude(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double`: amplitude of the tone by a least-squares fit over frames `[from, to)`.
  - `Signals.sinadDb(…)` with the same parameters: tone power over everything the fit leaves, in dB.

- [ ] **Step 1: Write the measurement helpers and the failing test**

In `core/src/test/kotlin/testutil/Signals.kt`, replace:

```kotlin
        return count / ((last - first) / rate)
    }
}
```

with:

```kotlin
        return count / ((last - first) / rate)
    }

    /**
     * Least-squares fit of a·sin + b·cos + dc at [freqHz] to one channel of [x] over frames
     * [from, to). Returns the tone amplitude and the mean power of what the fit leaves over.
     */
    private fun toneFit(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int, channel: Int): DoubleArray {
        var ss = 0.0
        var sc = 0.0
        var cc = 0.0
        var s1 = 0.0
        var c1 = 0.0
        var xs = 0.0
        var xc = 0.0
        var x1 = 0.0
        var xx = 0.0
        val w = 2 * PI * freqHz / rate
        for (k in from until to) {
            val s = sin(w * k)
            val c = cos(w * k)
            val v = x[k * channels + channel].toDouble()
            ss += s * s
            sc += s * c
            cc += c * c
            s1 += s
            c1 += c
            xs += v * s
            xc += v * c
            x1 += v
            xx += v * v
        }
        val n = (to - from).toDouble()
        val det = ss * (cc * n - c1 * c1) - sc * (sc * n - c1 * s1) + s1 * (sc * c1 - cc * s1)
        val a = (xs * (cc * n - c1 * c1) - sc * (xc * n - c1 * x1) + s1 * (xc * c1 - cc * x1)) / det
        val b = (ss * (xc * n - c1 * x1) - xs * (sc * n - c1 * s1) + s1 * (sc * x1 - xc * s1)) / det
        val d = (ss * (cc * x1 - xc * c1) - sc * (sc * x1 - xc * s1) + xs * (sc * c1 - cc * s1)) / det
        val residual = maxOf(0.0, (xx - a * xs - b * xc - d * x1) / n)
        return doubleArrayOf(hypot(a, b), residual)
    }

    /** Amplitude of the real tone at [freqHz] in one channel of [x] over frames [from, to). */
    fun toneAmplitude(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double =
        toneFit(x, freqHz, rate, from, to, channels, channel)[0]

    /** Signal-to-noise-and-distortion ratio in dB of the real tone at [freqHz] over frames [from, to). */
    fun sinadDb(x: FloatArray, freqHz: Double, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        val fit = toneFit(x, freqHz, rate, from, to, channels, channel)
        return 10 * log10(fit[0] * fit[0] / 2 / (fit[1] + 1e-30))
    }
}
```

Create `core/src/test/kotlin/dsp/BiquadTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class BiquadTest {
    private val fs = 48_000.0

    /** Gain in dB of a filter for a tone at [hz], measured after the transient. */
    private fun gainDb(filter: BiquadCascade, hz: Double): Double {
        val n = 48_000
        val x = Signals.realTone(n, hz, fs)
        filter.reset()
        filter.process(x, n)
        return 20 * log10(Signals.toneAmplitude(x, hz, fs, n / 2, n))
    }

    @Test
    fun lowPassFollowsTheButterworthCurve() {
        val lp = BiquadCascade.lowPass(1, 4, 3_500.0, fs)
        assertEquals(0.0, gainDb(lp, 500.0), 0.1)
        assertEquals(-3.0, gainDb(lp, 3_500.0), 0.2)
        assertEquals(-24.1, gainDb(lp, 7_000.0), 2.0)
        assertTrue(gainDb(lp, 14_000.0) < -45.0)
    }

    @Test
    fun highPassFollowsTheButterworthCurve() {
        val hp = BiquadCascade.highPass(1, 4, 300.0, fs)
        assertEquals(0.0, gainDb(hp, 3_000.0), 0.1)
        assertEquals(-3.0, gainDb(hp, 300.0), 0.2)
        assertEquals(-24.1, gainDb(hp, 150.0), 1.0)
        assertTrue(gainDb(hp, 100.0) < -36.0)
    }

    @Test
    fun channelsAreIndependent() {
        val lp = BiquadCascade.lowPass(2, 2, 1_000.0, fs)
        val n = 4_800
        val x = FloatArray(2 * n) { if (it % 2 == 0) 1f else 0f }
        lp.process(x, n)
        assertEquals(1f, x[2 * (n - 1)], 1e-3f)
        for (i in 0 until n) assertEquals(0f, x[2 * i + 1], 0f)
    }

    @Test
    fun recoversAfterANonFiniteBlock() {
        val lp = BiquadCascade.lowPass(1, 4, 3_500.0, fs)
        val bad = FloatArray(64) { Float.NaN }
        lp.process(bad, bad.size)
        val x = FloatArray(4_800) { 1f }
        lp.process(x, x.size)
        assertEquals(1f, x[x.size - 1], 1e-3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddOrders() {
        BiquadCascade.lowPass(1, 3, 1_000.0, fs)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.BiquadTest`
Expected: the build fails with `unresolved reference 'BiquadCascade'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/dsp/Biquad.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Even-order Butterworth low-pass or high-pass built from second-order sections (RBJ cookbook
 * coefficients), applied to each interleaved channel. State is kept in double precision so low
 * cutoffs stay stable; denormal and non-finite state is flushed after every block.
 */
class BiquadCascade private constructor(private val channels: Int, private val c: DoubleArray) {
    private val sections = c.size / 5
    private val z = DoubleArray(sections * channels * 2)

    /** Filters [frames] frames of [data] in place. */
    fun process(data: FloatArray, frames: Int) {
        for (ch in 0 until channels) {
            for (s in 0 until sections) {
                val b0 = c[5 * s]
                val b1 = c[5 * s + 1]
                val b2 = c[5 * s + 2]
                val a1 = c[5 * s + 3]
                val a2 = c[5 * s + 4]
                val zi = 2 * (s * channels + ch)
                var z1 = z[zi]
                var z2 = z[zi + 1]
                var i = ch
                for (n in 0 until frames) {
                    val x = data[i].toDouble()
                    val y = b0 * x + z1
                    z1 = b1 * x - a1 * y + z2
                    z2 = b2 * x - a2 * y
                    data[i] = y.toFloat()
                    i += channels
                }
                // Written so that NaN fails the test too: a bad block cannot poison the filter.
                z[zi] = if (abs(z1) >= 1e-30 && abs(z1) < 1e30) z1 else 0.0
                z[zi + 1] = if (abs(z2) >= 1e-30 && abs(z2) < 1e30) z2 else 0.0
            }
        }
    }

    fun reset() = z.fill(0.0)

    companion object {
        /** Butterworth low-pass of even [order] with its −3 dB point at [cutoffHz]. */
        fun lowPass(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double): BiquadCascade =
            design(channels, order, cutoffHz, sampleRate, highPass = false)

        /** Butterworth high-pass of even [order] with its −3 dB point at [cutoffHz]. */
        fun highPass(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double): BiquadCascade =
            design(channels, order, cutoffHz, sampleRate, highPass = true)

        private fun design(channels: Int, order: Int, cutoffHz: Double, sampleRate: Double, highPass: Boolean): BiquadCascade {
            require(channels >= 1) { "channels must be >= 1" }
            require(order >= 2 && order % 2 == 0) { "order must be even and >= 2" }
            require(cutoffHz > 0 && cutoffHz < sampleRate / 2) { "cutoff $cutoffHz Hz is outside 0..${sampleRate / 2} Hz" }
            val w0 = 2 * PI * cutoffHz / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val c = DoubleArray(5 * (order / 2))
            for (k in 0 until order / 2) {
                val q = 1 / (2 * cos(PI * (2 * k + 1) / (2.0 * order)))
                val alpha = sw / (2 * q)
                val a0 = 1 + alpha
                val b1 = if (highPass) -(1 + cw) else 1 - cw
                val b0 = abs(b1) / 2
                c[5 * k] = b0 / a0
                c[5 * k + 1] = b1 / a0
                c[5 * k + 2] = b0 / a0
                c[5 * k + 3] = -2 * cw / a0
                c[5 * k + 4] = (1 - alpha) / a0
            }
            return BiquadCascade(channels, c)
        }
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.BiquadTest`
Expected: `OK (5 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (127 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): Butterworth biquad cascade and tone measurement helpers

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 4: RDS block code and signal generators

Everything that follows is tested against signals made here: tones, FM carriers, a stereo multiplex and an RDS bit stream with the standard's pulse shape. The simulator (Task 13) reuses the same generators (§12), so they live in the main source set, in a package of their own. The block code comes first because the generator needs its checkwords and its pulse.

**Files:**
- Create: `core/src/main/kotlin/rds/RdsCode.kt`, `core/src/main/kotlin/sim/Oscillator.kt`, `core/src/main/kotlin/sim/SineTable.kt`, `core/src/main/kotlin/sim/FmModulator.kt`, `core/src/main/kotlin/sim/RdsEncoder.kt`, `core/src/main/kotlin/sim/RdsBaseband.kt`, `core/src/main/kotlin/sim/MpxGenerator.kt`
- Test: `core/src/test/kotlin/rds/RdsCodeTest.kt`, `core/src/test/kotlin/sim/GeneratorTest.kt`

**Interfaces:**
- Consumes: `Signals.toneAmplitude`, `Signals.complexToneDb`, `Signals.peak` (tests).
- Produces:
  - `object RdsCode` with `BIT_RATE = 1_187.5`, `CHIP_RATE = 2_375.0`, `POLY = 0x5B9`, `OFFSET_A = 0x0FC`, `OFFSET_B = 0x198`, `OFFSET_C = 0x168`, `OFFSET_C_PRIME = 0x350`, `OFFSET_D = 0x1B4`, `MAX_BURST = 5`; `fun pulse(u: Double): Double`; `fun remainder(word: Int, bits: Int): Int`; `fun crc(info: Int): Int`; `fun encode(info: Int, offset: Int): Int` (a 26-bit block); `fun syndrome(block: Int): Int` (the offset word when the block is error-free); `val burstErrors: IntArray` (1,024 entries: syndrome with the offset word removed → 26-bit error pattern, 0 = not correctable).
  - `class Oscillator(frequencyHz: Double, sampleRate: Double, phase: Double = 0.0)` with `val cosine: Double`, `val sine: Double`, `fun advance()`.
  - `object SineTable` with `fun sin(turns: Double): Float` and `fun cos(turns: Double): Float` (1.0 turn = a full circle).
  - `class FmModulator(sampleRate: Double, deviationHz: Double, var carrierHz: Double = 0.0)` with `fun modulate(message: FloatArray, count: Int, out: FloatArray, amplitude: Float = 1f, add: Boolean = false, outOffset: Int = 0)`: a message of ±1.0 deviates the carrier by ±`deviationHz`; `out` is complex.
  - `class RdsEncoder(val pi: Int, ps: String, rt: String = "", val pty: Int = 0, val tp: Boolean = false, val ta: Boolean = false)` with `val bits: ByteArray` (one cycle of 0A and 2A groups, values 0 or 1); companion: `radioText(rt: String): String`, `group0A(pi, pty, tp, ta, segment, c1, c2): IntArray`, `group2A(pi, pty, tp, abFlag, segment, c1, c2, c3, c4): IntArray`, `group4A(pi, pty, tp, mjd, hour, minute, offsetHalfHours): IntArray`, `blocks(group: IntArray): IntArray`, `toBits(groups: List<IntArray>): ByteArray`.
  - `class RdsBaseband(bits: ByteArray, sampleRate: Double)` with `fun next(): Float`.
  - `class MpxGenerator(sampleRate: Double, leftHz: Double = 1_000.0, rightHz: Double = 1_000.0, leftLevel: Float = 1f, rightLevel: Float = 1f, stereo: Boolean = true, rds: RdsBaseband? = null, pilotLevel: Float = 0.09f, rdsLevel: Float = 0.03f, rdsPhaseTurns: Double = 0.0)` with `val audioLevel: Float` and `fun generate(out: FloatArray, count: Int)` (±1.0 = full deviation; a tone of 0 Hz is a silent channel).

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/rds/RdsCodeTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

class RdsCodeTest {
    @Test
    fun validBlocksGiveThePublishedSyndromes() {
        // x¹⁰-premultiplied syndromes of offset words A, B, C, D and C′ (IEC 62106 Annex B, as used by gr-rds)
        val expected = intArrayOf(383, 14, 303, 663, 748)
        val offsets = intArrayOf(RdsCode.OFFSET_A, RdsCode.OFFSET_B, RdsCode.OFFSET_C, RdsCode.OFFSET_D, RdsCode.OFFSET_C_PRIME)
        val rnd = Random(1)
        repeat(50) {
            val info = rnd.nextInt(0x10000)
            for (i in offsets.indices) {
                assertEquals(expected[i], RdsCode.remainder(RdsCode.encode(info, offsets[i]), 26))
            }
        }
    }

    @Test
    fun syndromeOfACleanBlockIsItsOffsetWord() {
        assertEquals(RdsCode.OFFSET_A, RdsCode.syndrome(RdsCode.encode(0x1234, RdsCode.OFFSET_A)))
        assertEquals(RdsCode.OFFSET_D, RdsCode.syndrome(RdsCode.encode(0xFFFF, RdsCode.OFFSET_D)))
        assertEquals(RdsCode.OFFSET_C_PRIME, RdsCode.syndrome(RdsCode.encode(0, RdsCode.OFFSET_C_PRIME)))
    }

    @Test
    fun everyBurstOfUpToFiveBitsHasItsOwnSyndrome() {
        var patterns = 0
        for (length in 1..RdsCode.MAX_BURST) {
            val inner = if (length <= 2) 1 else 1 shl (length - 2)
            for (middle in 0 until inner) {
                val pattern = if (length == 1) 1 else (1 shl (length - 1)) or (middle shl 1) or 1
                for (shift in 0..26 - length) {
                    val error = pattern shl shift
                    val syndrome = RdsCode.syndrome(error)
                    assertNotEquals(0, syndrome)
                    assertEquals(error, RdsCode.burstErrors[syndrome])
                    patterns++
                }
            }
        }
        assertEquals(367, patterns)
        assertEquals(367, RdsCode.burstErrors.count { it != 0 })
    }

    @Test
    fun aBurstErrorIsLocatedThroughItsSyndrome() {
        val block = RdsCode.encode(0xBEEF, RdsCode.OFFSET_B)
        val error = 0b10111 shl 9
        val syndrome = RdsCode.syndrome(block xor error) xor RdsCode.OFFSET_B
        assertEquals(error, RdsCode.burstErrors[syndrome])
    }
}
```

Create `core/src/test/kotlin/sim/GeneratorTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class GeneratorTest {
    @Test
    fun oscillatorStaysOnTheUnitCircleAndOnFrequency() {
        val osc = Oscillator(1_000.0, 48_000.0)
        var worst = 0.0
        for (n in 0 until 480_000) {
            worst = maxOf(worst, abs(osc.sine - sin(2 * PI * 1_000.0 * n / 48_000.0)))
            assertEquals(1.0, hypot(osc.sine, osc.cosine), 1e-9)
            osc.advance()
        }
        assertTrue("worst error $worst", worst < 1e-6)
    }

    @Test
    fun sineTableIsAccurate() {
        var worst = 0.0
        for (i in 0..10_000) {
            val turns = i / 1_000.0 - 3.0
            worst = maxOf(worst, abs(SineTable.sin(turns) - sin(2 * PI * turns)))
            worst = maxOf(worst, abs(SineTable.cos(turns) - kotlin.math.cos(2 * PI * turns)))
        }
        assertTrue("worst error $worst", worst < 1e-5)
    }

    @Test
    fun fmModulatorPutsAConstantMessageAtItsDeviation() {
        val fs = 240_000.0
        val n = 24_000
        val message = FloatArray(n) { 0.5f }
        val iq = FloatArray(2 * n)
        FmModulator(fs, 75_000.0).modulate(message, n, iq)
        assertEquals(0.0, Signals.complexToneDb(iq, 37_500.0, fs), 0.01)
        FmModulator(fs, 75_000.0, carrierHz = -20_000.0).modulate(message, n, iq, amplitude = 0.5f, add = true)
        assertEquals(-6.02, Signals.complexToneDb(iq, 17_500.0, fs), 0.05)
    }

    @Test
    fun rdsPulseHasTheStandardShape() {
        assertEquals(1.0, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.0), 1e-12)
        assertEquals(PI / 4, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.25), 1e-6)
        assertEquals(1.0 / 3, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.5), 1e-12)
        assertEquals(0.0, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(0.75), 1e-12)
        assertEquals(0.0, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.pulse(1.25), 1e-12)
    }

    @Test
    fun rdsBasebandStaysBelowTwoPointFourKilohertz() {
        val fs = 19_000.0   // 8 samples per chip
        val bits = ByteArray(1_040) { ((it * 7 + it / 3) % 2).toByte() }
        val gen = RdsBaseband(bits, fs)
        val n = 19_000
        val x = FloatArray(n) { gen.next() }
        var inBand = 0.0
        var outOfBand = 0.0
        for (f in 100..9_400 step 100) {
            val a = Signals.toneAmplitude(x, f.toDouble(), fs, 0, n)
            if (f <= 2_400) inBand += a * a else outOfBand += a * a
        }
        assertTrue("in-band $inBand out-of-band $outOfBand", outOfBand < inBand * 1e-2)
        assertTrue(Signals.peak(x) in 1.0f..1.6f)
    }

    @Test
    fun encoderProducesWholeGroupsWithValidChecks() {
        val enc = RdsEncoder(0x1234, "TEST FM", "Hello")
        // 4 PS groups + 2 text groups ("Hello" + carriage return + padding = 8 characters)
        assertEquals(6 * 104, enc.bits.size)
        assertEquals("Hello\r  ", RdsEncoder.radioText("Hello"))
        var word = 0
        for (i in 0 until 26) word = (word shl 1) or enc.bits[i].toInt()
        assertEquals(0x1234, word ushr 10)
        assertEquals(io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.OFFSET_A, io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode.syndrome(word))
    }

    @Test
    fun multiplexHasPilotAndSubcarrierWhereTheStandardPutsThem() {
        val fs = 240_000.0
        val n = 240_000
        val gen = MpxGenerator(fs, leftHz = 1_000.0, rightHz = 0.0)
        val mpx = FloatArray(n)
        gen.generate(mpx, n)
        assertEquals(0.09, Signals.toneAmplitude(mpx, 19_000.0, fs, 0, n), 1e-3)
        // Left only: M carries L/2 and the 38 kHz double sideband carries L/2 as two lines of L/4
        assertEquals(0.91 / 2, Signals.toneAmplitude(mpx, 1_000.0, fs, 0, n), 1e-3)
        assertEquals(0.91 / 4, Signals.toneAmplitude(mpx, 37_000.0, fs, 0, n), 1e-3)
        assertEquals(0.91 / 4, Signals.toneAmplitude(mpx, 39_000.0, fs, 0, n), 1e-3)
        assertTrue(Signals.peak(mpx) <= 1.0f)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCodeTest`
Expected: the build fails with `unresolved reference 'RdsCode'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/rds/RdsCode.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

/**
 * The RDS block code (IEC 62106 Annex B): a (26,16) shortened cyclic code with generator
 * g(x) = x¹⁰ + x⁸ + x⁷ + x⁵ + x⁴ + x³ + 1. Every block is a 16-bit information word followed by a
 * 10-bit checkword to which an offset word (A, B, C, C′ or D) has been added.
 */
object RdsCode {
    /** Data rate in bit/s: the 57 kHz subcarrier divided by 48. */
    const val BIT_RATE = 1_187.5

    /** Biphase chips per second: two per bit. */
    const val CHIP_RATE = 2_375.0

    const val POLY = 0x5B9
    const val OFFSET_A = 0x0FC
    const val OFFSET_B = 0x198
    const val OFFSET_C = 0x168
    const val OFFSET_C_PRIME = 0x350
    const val OFFSET_D = 0x1B4

    /** Longest error burst the code corrects. */
    const val MAX_BURST = 5

    /**
     * The shaped chip pulse at [u] chip periods from its centre: the impulse response of the
     * standard's cosine-shaped data filter, p(u) = cos(2πu) / (1 − 16u²).
     */
    fun pulse(u: Double): Double {
        val d = 1 - 16 * u * u
        return if (kotlin.math.abs(d) < 1e-9) kotlin.math.PI / 4 else kotlin.math.cos(2 * kotlin.math.PI * u) / d
    }

    /** Remainder of word(x)·x¹⁰ divided by g(x); [bits] is the length of [word]. */
    fun remainder(word: Int, bits: Int): Int {
        var reg = 0
        for (i in bits - 1 downTo 0) {
            reg = (reg shl 1) or ((word ushr i) and 1)
            if (reg and 0x400 != 0) reg = reg xor POLY
        }
        repeat(10) {
            reg = reg shl 1
            if (reg and 0x400 != 0) reg = reg xor POLY
        }
        return reg and 0x3FF
    }

    /** Checkword of a 16-bit information word, before the offset word is added. */
    fun crc(info: Int): Int = remainder(info and 0xFFFF, 16)

    /** The 26-bit block for [info] with [offset]. */
    fun encode(info: Int, offset: Int): Int = ((info and 0xFFFF) shl 10) or (crc(info) xor offset)

    /** Syndrome of a 26-bit block: the block's offset word when it was received without errors. */
    fun syndrome(block: Int): Int = crc(block ushr 10) xor (block and 0x3FF)

    /**
     * For every syndrome (with the offset word already removed) of an error burst of at most
     * [MAX_BURST] bits: the 26-bit error pattern to flip. 0 marks a syndrome that is not correctable.
     */
    val burstErrors: IntArray = IntArray(1024).also { table ->
        for (length in 1..MAX_BURST) {
            // A burst of `length` bits has its first and last bit set; the bits between are free.
            val inner = if (length <= 2) 1 else 1 shl (length - 2)
            for (middle in 0 until inner) {
                val pattern = if (length == 1) 1 else (1 shl (length - 1)) or (middle shl 1) or 1
                for (shift in 0..26 - length) {
                    val error = pattern shl shift
                    table[syndrome(error)] = error
                }
            }
        }
    }
}
```

Create `core/src/main/kotlin/sim/Oscillator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sine and cosine of a fixed frequency by phasor recursion with periodic renormalisation, so test
 * signals and the simulator need no trigonometry per sample.
 */
class Oscillator(frequencyHz: Double, sampleRate: Double, phase: Double = 0.0) {
    private val stepCos = cos(2 * PI * frequencyHz / sampleRate)
    private val stepSin = sin(2 * PI * frequencyHz / sampleRate)
    private var sinceRenorm = 0

    /** cos of the current phase. */
    var cosine = cos(phase)
        private set

    /** sin of the current phase. */
    var sine = sin(phase)
        private set

    /** Advances the phase by one sample. */
    fun advance() {
        val c = cosine * stepCos - sine * stepSin
        val s = cosine * stepSin + sine * stepCos
        if (++sinceRenorm >= 1024) {
            sinceRenorm = 0
            val g = 1.0 / sqrt(c * c + s * s)
            cosine = c * g
            sine = s * g
        } else {
            cosine = c
            sine = s
        }
    }
}
```

Create `core/src/main/kotlin/sim/SineTable.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import kotlin.math.PI
import kotlin.math.floor

/** Sine lookup with linear interpolation. Phases are in turns: 1.0 is a full circle. */
object SineTable {
    private const val SIZE = 4096
    private val table = FloatArray(SIZE + 1) { kotlin.math.sin(2 * PI * it / SIZE).toFloat() }

    fun sin(turns: Double): Float {
        val x = (turns - floor(turns)) * SIZE
        val i = x.toInt()
        return table[i] + (table[i + 1] - table[i]) * (x - i).toFloat()
    }

    fun cos(turns: Double): Float = sin(turns + 0.25)
}
```

Create `core/src/main/kotlin/sim/FmModulator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

/**
 * Complex-baseband FM modulator: a message of ±1.0 deviates the carrier by ±[deviationHz] around
 * [carrierHz] (the offset from the centre of the generated band).
 */
class FmModulator(private val sampleRate: Double, private val deviationHz: Double, var carrierHz: Double = 0.0) {
    private var phase = 0.0   // turns

    /**
     * Writes [count] complex samples for [message] into [out], starting at complex index [outOffset].
     * With [add] the samples are added to what [out] already holds.
     */
    fun modulate(message: FloatArray, count: Int, out: FloatArray, amplitude: Float = 1f, add: Boolean = false, outOffset: Int = 0) {
        val carrierStep = carrierHz / sampleRate
        val k = deviationHz / sampleRate
        var p = phase
        var o = 2 * outOffset
        for (n in 0 until count) {
            p += carrierStep + k * message[n]
            if (p >= 1.0) p -= 1.0 else if (p < 0.0) p += 1.0
            val re = amplitude * SineTable.cos(p)
            val im = amplitude * SineTable.sin(p)
            if (add) {
                out[o] += re
                out[o + 1] += im
            } else {
                out[o] = re
                out[o + 1] = im
            }
            o += 2
        }
        phase = p
    }
}
```

Create `core/src/main/kotlin/sim/RdsEncoder.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode
import kotlin.math.abs

/**
 * Builds the repeating RDS bit stream of a station: 0A groups carrying the programme service name
 * [ps], interleaved with 2A groups carrying the radio text [rt]. Used by the simulator and by tests.
 */
class RdsEncoder(
    val pi: Int,
    ps: String,
    rt: String = "",
    val pty: Int = 0,
    val tp: Boolean = false,
    val ta: Boolean = false,
) {
    /** One full cycle of groups as bits (0 or 1), most significant bit of each block first. */
    val bits: ByteArray

    init {
        val name = ps.padEnd(8).take(8)
        val text = radioText(rt)
        val segments = text.length / 4
        val groups = ArrayList<IntArray>()
        for (i in 0 until maxOf(4, segments)) {
            val s = i % 4
            groups += group0A(pi, pty, tp, ta, s, name[2 * s].code, name[2 * s + 1].code)
            if (i < segments) {
                groups += group2A(pi, pty, tp, false, i, text[4 * i].code, text[4 * i + 1].code, text[4 * i + 2].code, text[4 * i + 3].code)
            }
        }
        bits = toBits(groups)
    }

    companion object {
        private fun bit(b: Boolean) = if (b) 1 else 0

        /** Radio text as transmitted: at most 64 characters, ended by a carriage return when shorter. */
        fun radioText(rt: String): String {
            if (rt.isEmpty()) return ""
            val t = rt.take(64)
            if (t.length == 64) return t
            val ended = t + "\r"
            return ended.padEnd((ended.length + 3) / 4 * 4)
        }

        /** Group 0A: two characters of the programme service name at [segment] (0–3). */
        fun group0A(pi: Int, pty: Int, tp: Boolean, ta: Boolean, segment: Int, c1: Int, c2: Int): IntArray {
            val di = if (segment == 3) 1 else 0   // decoder identification: stereo
            val b = (bit(tp) shl 10) or (pty shl 5) or (bit(ta) shl 4) or (1 shl 3) or (di shl 2) or segment
            return intArrayOf(pi, b, 0xCDCD, (c1 shl 8) or c2)
        }

        /** Group 2A: four radio text characters at [segment] (0–15). */
        fun group2A(pi: Int, pty: Int, tp: Boolean, abFlag: Boolean, segment: Int, c1: Int, c2: Int, c3: Int, c4: Int): IntArray {
            val b = (2 shl 12) or (bit(tp) shl 10) or (pty shl 5) or (bit(abFlag) shl 4) or segment
            return intArrayOf(pi, b, (c1 shl 8) or c2, (c3 shl 8) or c4)
        }

        /** Group 4A: clock time as modified Julian day, UTC hour and minute, and the local offset in half hours. */
        fun group4A(pi: Int, pty: Int, tp: Boolean, mjd: Int, hour: Int, minute: Int, offsetHalfHours: Int): IntArray {
            val b = (4 shl 12) or (bit(tp) shl 10) or (pty shl 5) or ((mjd ushr 15) and 3)
            val c = ((mjd and 0x7FFF) shl 1) or ((hour ushr 4) and 1)
            val sign = if (offsetHalfHours < 0) 1 else 0
            val d = ((hour and 0xF) shl 12) or (minute shl 6) or (sign shl 5) or (abs(offsetHalfHours) and 0x1F)
            return intArrayOf(pi, b, c, d)
        }

        /** The four 26-bit blocks of a group; version B groups (bit 11 of block B) use offset C′. */
        fun blocks(group: IntArray): IntArray {
            val versionB = (group[1] ushr 11) and 1 == 1
            return intArrayOf(
                RdsCode.encode(group[0], RdsCode.OFFSET_A),
                RdsCode.encode(group[1], RdsCode.OFFSET_B),
                RdsCode.encode(group[2], if (versionB) RdsCode.OFFSET_C_PRIME else RdsCode.OFFSET_C),
                RdsCode.encode(group[3], RdsCode.OFFSET_D),
            )
        }

        /** Groups as a bit stream, most significant bit of each block first. */
        fun toBits(groups: List<IntArray>): ByteArray {
            val out = ByteArray(groups.size * 104)
            var o = 0
            for (g in groups) {
                for (block in blocks(g)) {
                    for (i in 25 downTo 0) out[o++] = ((block ushr i) and 1).toByte()
                }
            }
            return out
        }
    }
}
```

Create `core/src/main/kotlin/sim/RdsBaseband.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCode

/**
 * The RDS baseband signal for a repeating bit stream: differential encoding, biphase symbols
 * (two chips per bit) and the standard's cosine spectral shaping, generated sample by sample.
 * Each chip contributes the pulse [RdsCode.pulse].
 */
class RdsBaseband(private val bits: ByteArray, sampleRate: Double) {
    private val step = RdsCode.CHIP_RATE / sampleRate
    private val window = FloatArray(2 * SPAN)   // chips base−2 … base+3
    private var frac = 0.0
    private var bitIndex = 0
    private var secondHalf = false
    private var level = 0

    init {
        require(bits.isNotEmpty()) { "bits must not be empty" }
        repeat(SPAN + 1) { push() }
    }

    private fun push() {
        System.arraycopy(window, 1, window, 0, window.size - 1)
        if (!secondHalf) {
            level = level xor bits[bitIndex].toInt()
            bitIndex = (bitIndex + 1) % bits.size
        }
        val first = if (level == 1) 1f else -1f
        window[window.size - 1] = if (secondHalf) -first else first
        secondHalf = !secondHalf
    }

    /** The next baseband sample; chips have unit amplitude, so peaks reach about ±1.4. */
    fun next(): Float {
        var acc = 0f
        for (j in window.indices) {
            val x = (frac - (j - (SPAN - 1)) + SPAN) * RESOLUTION
            val i = x.toInt()
            acc += window[j] * (PULSE[i] + (PULSE[i + 1] - PULSE[i]) * (x - i).toFloat())
        }
        frac += step
        if (frac >= 1.0) {
            frac -= 1.0
            push()
        }
        return acc
    }

    companion object {
        private const val SPAN = 3
        private const val RESOLUTION = 64
        private val PULSE = FloatArray(2 * SPAN * RESOLUTION + 2) { RdsCode.pulse((it.toDouble() / RESOLUTION) - SPAN).toFloat() }
    }
}
```

Create `core/src/main/kotlin/sim/MpxGenerator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

/**
 * FM broadcast multiplex (ITU-R BS.450 pilot-tone system) for two audio tones:
 * a·[(L+R)/2 + (L−R)/2·sin 2ωt] + pilot·sin ωt + rds·r(t)·sin(3ωt + φ), with ω the 19 kHz pilot.
 * A tone frequency of 0 Hz gives a silent channel. No pre-emphasis is applied.
 */
class MpxGenerator(
    sampleRate: Double,
    leftHz: Double = 1_000.0,
    rightHz: Double = 1_000.0,
    private val leftLevel: Float = 1f,
    private val rightLevel: Float = 1f,
    private val stereo: Boolean = true,
    private val rds: RdsBaseband? = null,
    private val pilotLevel: Float = 0.09f,
    private val rdsLevel: Float = 0.03f,
    rdsPhaseTurns: Double = 0.0,
) {
    private val left = Oscillator(leftHz, sampleRate)
    private val right = Oscillator(rightHz, sampleRate)
    private val pilot = Oscillator(19_000.0, sampleRate)
    private val rdsCos = SineTable.cos(rdsPhaseTurns)
    private val rdsSin = SineTable.sin(rdsPhaseTurns)

    /** Share of the full deviation left for the audio. */
    val audioLevel: Float = 1f - (if (stereo) pilotLevel else 0f) - (if (rds != null) 0.05f else 0f)

    /** Writes [count] multiplex samples (±1.0 = full deviation) to [out]. */
    fun generate(out: FloatArray, count: Int) {
        for (n in 0 until count) {
            val l = leftLevel * left.sine.toFloat()
            val r = rightLevel * right.sine.toFloat()
            val ps = pilot.sine.toFloat()
            val pc = pilot.cosine.toFloat()
            var v = audioLevel * 0.5f * (l + r)
            if (stereo) v += audioLevel * 0.5f * (l - r) * (2f * ps * pc) + pilotLevel * ps
            if (rds != null) {
                val s3 = 3f * ps - 4f * ps * ps * ps
                val c3 = 4f * pc * pc * pc - 3f * pc
                v += rdsLevel * rds.next() * (s3 * rdsCos + c3 * rdsSin)
            }
            out[n] = v
            left.advance()
            right.advance()
            pilot.advance()
        }
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsCodeTest io.github.radhwanalhajjaj.spectrasdr.core.sim.GeneratorTest`
Expected: `OK (11 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (138 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): RDS block code and test-signal generators

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 5: RDS decoder

Bits in, station data out (§6.4): block synchronisation, burst-error correction while synchronised, groups 0A/0B, 2A/2B and 4A.

**Files:**
- Create: `core/src/main/kotlin/rds/RdsDecoder.kt`
- Test: `core/src/test/kotlin/rds/RdsDecoderTest.kt`

**Interfaces:**
- Consumes: `RdsCode`; `RdsEncoder` (tests).
- Produces:
  - `data class RdsClock(val mjd: Int, val hour: Int, val minute: Int, val offsetHalfHours: Int)`.
  - `data class RdsState(val pi: Int = -1, val pty: Int = 0, val tp: Boolean = false, val ta: Boolean = false, val ps: String = "", val rt: String = "", val clock: RdsClock? = null)`. `ps` is empty or 8 characters; unconfirmed positions are spaces.
  - `class RdsDecoder` with `val state: RdsState` (volatile, replaced on every change), `val synced: Boolean` (volatile), `val blocksClean: Long`, `val blocksCorrected: Long`, `val blocksFailed: Long`, `fun push(bit: Int)`, `fun reset()`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/rds/RdsDecoderTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RdsDecoderTest {
    private fun feed(decoder: RdsDecoder, bits: ByteArray, cycles: Int = 1) {
        repeat(cycles) { for (b in bits) decoder.push(b.toInt()) }
    }

    @Test
    fun decodesNameAndTextFromACleanStream() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0xD318, "SPECTRA ", "Hello from SpectraSDR", pty = 10, tp = true).bits, 2)
        assertTrue(decoder.synced)
        val s = decoder.state
        assertEquals(0xD318, s.pi)
        assertEquals("SPECTRA ", s.ps)
        assertEquals("Hello from SpectraSDR", s.rt)
        assertEquals(10, s.pty)
        assertTrue(s.tp)
        assertFalse(s.ta)
        assertEquals(0L, decoder.blocksFailed)
        assertEquals(0L, decoder.blocksCorrected)
    }

    @Test
    fun synchronisesFromAnyBitOffset() {
        val bits = RdsEncoder(0x1234, "OFFSET  ").bits
        for (skip in intArrayOf(1, 13, 27, 77, 103)) {
            val decoder = RdsDecoder()
            for (i in skip until bits.size) decoder.push(bits[i].toInt())
            feed(decoder, bits, 2)
            assertEquals("skip $skip", "OFFSET  ", decoder.state.ps)
        }
    }

    @Test
    fun correctsBurstsOfUpToFiveBits() {
        val bits = RdsEncoder(0x4321, "BURSTS  ", "Corrected text").bits
        val decoder = RdsDecoder()
        feed(decoder, bits)                      // clean pass to synchronise
        val rnd = Random(3)
        repeat(4) {
            val damaged = bits.copyOf()
            for (block in 0 until damaged.size / 26) {
                if (block % 5 == 0) continue      // sync is only kept while error-free blocks keep arriving
                val length = 1 + rnd.nextInt(5)
                val start = block * 26 + rnd.nextInt(26 - length + 1)
                damaged[start] = (1 - damaged[start]).toByte()
                damaged[start + length - 1] = (1 - bits[start + length - 1]).toByte()
            }
            feed(decoder, damaged)
        }
        assertTrue(decoder.synced)
        assertEquals(0L, decoder.blocksFailed)
        assertTrue(decoder.blocksCorrected > 0)
        assertEquals("BURSTS  ", decoder.state.ps)
        assertEquals("Corrected text", decoder.state.rt)
    }

    @Test
    fun aCorrectedCharacterNeedsASecondReception() {
        val clean = RdsEncoder(0x4321, "ABCDEFGH").bits
        val other = RdsEncoder(0x4321, "ABCDEFXY").bits
        val decoder = RdsDecoder()
        feed(decoder, clean, 2)
        assertEquals("ABCDEFGH", decoder.state.ps)
        // One bit error in block D of the last name group: the block is corrected, not error-free.
        val damaged = other.copyOf()
        val blockD = 3 * 104 + 3 * 26
        damaged[blockD + 20] = (1 - damaged[blockD + 20]).toByte()
        feed(decoder, damaged)
        assertEquals("first reception is not shown", "ABCDEFGH", decoder.state.ps)
        feed(decoder, damaged)
        assertEquals("second identical reception is shown", "ABCDEFXY", decoder.state.ps)
    }

    @Test
    fun textResetsWhenTheStationChanges() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0x1111, "FIRST   ", "First text").bits, 2)
        assertEquals("First text", decoder.state.rt)
        feed(decoder, RdsEncoder(0x2222, "SECOND  ").bits, 1)
        assertEquals(0x2222, decoder.state.pi)
        assertEquals("SECOND  ", decoder.state.ps)
        assertEquals("", decoder.state.rt)
    }

    @Test
    fun aNewTextFlagClearsTheOldText() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0x1111, "FLAG    ", "A long first radio text").bits, 2)
        val groups = listOf(
            RdsEncoder.group2A(0x1111, 0, false, true, 0, 'N'.code, 'e'.code, 'w'.code, '\r'.code),
        )
        feed(decoder, RdsEncoder.toBits(groups), 2)
        assertEquals("New", decoder.state.rt)
    }

    @Test
    fun decodesVersionBGroupsAndTheClock() {
        val decoder = RdsDecoder()
        val versionB = 1 shl 11
        val groups = ArrayList<IntArray>()
        val name = "VERSIONB"
        for (s in 0 until 4) {
            val g = RdsEncoder.group0A(0x5555, 5, false, true, s, name[2 * s].code, name[2 * s + 1].code)
            groups += intArrayOf(g[0], g[1] or versionB, 0x5555, g[3])
        }
        val text = "Short\r"
        for (s in 0 until 3) {
            groups += intArrayOf(0x5555, (2 shl 12) or versionB or (5 shl 5) or s, 0x5555, (text[2 * s].code shl 8) or text[2 * s + 1].code)
        }
        groups += RdsEncoder.group4A(0x5555, 5, false, mjd = 61_357, hour = 21, minute = 47, offsetHalfHours = -6)
        feed(decoder, RdsEncoder.toBits(groups), 2)
        val s = decoder.state
        assertEquals("VERSIONB", s.ps)
        assertEquals("Short", s.rt)
        assertTrue(s.ta)
        assertEquals(RdsClock(61_357, 21, 47, -6), s.clock)
    }

    @Test
    fun noiseNeitherHoldsSyncNorShowsText() {
        val decoder = RdsDecoder()
        val rnd = Random(7)
        var syncedBits = 0
        repeat(1_187 * 120) {             // two minutes of random bits
            decoder.push(rnd.nextInt(2))
            if (decoder.synced) syncedBits++
        }
        assertTrue("synced for $syncedBits bits", syncedBits < 1_187 * 30)
        assertEquals("", decoder.state.ps.trim())
        assertEquals("", decoder.state.rt)
        assertNull(decoder.state.clock)
    }

    @Test
    fun dropsSyncWhenTheSignalDisappears() {
        val decoder = RdsDecoder()
        feed(decoder, RdsEncoder(0x1234, "GONE    ").bits, 2)
        assertTrue(decoder.synced)
        val rnd = Random(5)
        repeat(26 * 200) { decoder.push(rnd.nextInt(2)) }
        assertFalse(decoder.synced)
        assertEquals("the last name stays on display", "GONE    ", decoder.state.ps)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsDecoderTest`
Expected: the build fails with `unresolved reference 'RdsDecoder'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/rds/RdsDecoder.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

/** Clock time from group 4A: modified Julian day, UTC hour and minute, local offset in half hours. */
data class RdsClock(val mjd: Int, val hour: Int, val minute: Int, val offsetHalfHours: Int)

/**
 * What has been decoded from a station so far. Text holds confirmed characters only; positions
 * not yet confirmed are spaces. [pi] is −1 until a programme identification has been confirmed.
 */
data class RdsState(
    val pi: Int = -1,
    val pty: Int = 0,
    val tp: Boolean = false,
    val ta: Boolean = false,
    val ps: String = "",
    val rt: String = "",
    val clock: RdsClock? = null,
)

/**
 * RDS block synchronisation, error correction and group decoding (§6.4). Feed it the data bits
 * (after differential decoding) one at a time; [state] is replaced whenever something changes.
 *
 * Bursts of up to 5 bit errors per block are corrected, but only while block sync is held, and a
 * value from a corrected block is shown only once it has been received twice in a row; a value
 * from an error-free block is shown at once. Sync is dropped after 50 blocks without an error-free
 * one, because corrected blocks alone prove nothing: a third of all random words look correctable.
 */
class RdsDecoder {
    /** The latest decoded state; an immutable snapshot that is safe to read from any thread. */
    @Volatile
    var state = RdsState()
        private set

    /** True while block synchronisation is held. */
    @Volatile
    var synced = false
        private set

    /** Blocks received without errors, corrected, and failed, since the last [reset]. */
    var blocksClean = 0L
        private set
    var blocksCorrected = 0L
        private set
    var blocksFailed = 0L
        private set

    private var reg = 0
    private var bitsInReg = 0
    private var bitCount = 0
    private var blockIndex = 0
    private var sinceClean = 0
    private var candidateIndex = -1
    private var candidateAge = 0

    private val word = IntArray(4)
    private val valid = BooleanArray(4)
    private val clean = BooleanArray(4)

    private var pi = -1
    private var piCandidate = -1
    private var flags = 0            // tp (bit 5) and pty (bits 4–0), as last confirmed
    private var flagsCandidate = -1
    private var ta = false
    private var taCandidate = -1
    private val ps = IntArray(8) { -1 }
    private val psCandidate = IntArray(8) { -1 }
    private val rt = IntArray(64) { -1 }
    private val rtCandidate = IntArray(64) { -1 }
    private var rtFlag = -1
    private var clock: RdsClock? = null

    /** Takes the next data bit (only bit 0 of [bit] is used). */
    fun push(bit: Int) {
        reg = ((reg shl 1) or (bit and 1)) and 0x3FFFFFF
        if (!synced) {
            search()
            return
        }
        if (++bitCount < 26) return
        bitCount = 0
        receiveBlock()
    }

    /** Forgets everything, for use after a retune. */
    fun reset() {
        synced = false
        reg = 0
        bitsInReg = 0
        bitCount = 0
        candidateIndex = -1
        blocksClean = 0
        blocksCorrected = 0
        blocksFailed = 0
        pi = -1
        piCandidate = -1
        clearProgramme()
        state = RdsState()
    }

    private fun search() {
        if (bitsInReg < 26) bitsInReg++
        if (candidateIndex >= 0) candidateAge++
        if (bitsInReg < 26) return
        val index = when (RdsCode.syndrome(reg)) {
            RdsCode.OFFSET_A -> 0
            RdsCode.OFFSET_B -> 1
            RdsCode.OFFSET_C, RdsCode.OFFSET_C_PRIME -> 2
            RdsCode.OFFSET_D -> 3
            else -> -1
        }
        if (index < 0) {
            if (candidateAge > 26 * MAX_SEARCH_BLOCKS) candidateIndex = -1
            return
        }
        if (candidateIndex >= 0 && candidateAge % 26 == 0 && (candidateIndex + candidateAge / 26) % 4 == index) {
            // Two error-free blocks the right distance apart: synchronised. This block starts the group
            // assembly at its own position; earlier blocks of the group are simply missing.
            synced = true
            sinceClean = 0
            bitCount = 0
            blockIndex = index
            valid.fill(false)
            clean.fill(false)
            store(reg ushr 10, isClean = true)
        } else {
            candidateIndex = index
            candidateAge = 0
        }
    }

    private fun receiveBlock() {
        val syndrome = RdsCode.syndrome(reg)
        var data = -1
        var isClean = false
        if (blockIndex == 2 && (syndrome == RdsCode.OFFSET_C || syndrome == RdsCode.OFFSET_C_PRIME)) {
            data = reg ushr 10
            isClean = true
        } else {
            val offset = when (blockIndex) {
                0 -> RdsCode.OFFSET_A
                1 -> RdsCode.OFFSET_B
                2 -> if (valid[1] && (word[1] ushr 11) and 1 == 1) RdsCode.OFFSET_C_PRIME else RdsCode.OFFSET_C
                else -> RdsCode.OFFSET_D
            }
            if (syndrome == offset) {
                data = reg ushr 10
                isClean = true
            } else {
                val error = RdsCode.burstErrors[syndrome xor offset]
                if (error != 0) data = (reg xor error) ushr 10
            }
        }
        if (isClean) {
            blocksClean++
            sinceClean = 0
        } else {
            if (data >= 0) blocksCorrected++ else blocksFailed++
            if (++sinceClean > MAX_BLOCKS_WITHOUT_CLEAN) {
                synced = false
                bitsInReg = 26
                candidateIndex = -1
                return
            }
        }
        if (data >= 0) store(data, isClean) else advance()
    }

    private fun store(data: Int, isClean: Boolean) {
        word[blockIndex] = data
        valid[blockIndex] = true
        clean[blockIndex] = isClean
        advance()
    }

    private fun advance() {
        if (blockIndex == 3) {
            decodeGroup()
            valid.fill(false)
            clean.fill(false)
            blockIndex = 0
        } else {
            blockIndex++
        }
    }

    /** True when [value] may be shown: it came error-free, or matches the previous reception. */
    private fun confirmed(value: Int, previous: Int, isClean: Boolean) = isClean || value == previous

    private fun decodeGroup() {
        var changed = false
        if (valid[0]) {
            val value = word[0]
            if (confirmed(value, piCandidate, clean[0]) && value != pi) {
                pi = value
                clearProgramme()
                changed = true
            }
            piCandidate = value
        }
        if (valid[1]) {
            val b = word[1]
            val type = b ushr 12
            val versionB = (b ushr 11) and 1 == 1
            val f = (b ushr 5) and 0x3F
            if (confirmed(f, flagsCandidate, clean[1]) && f != flags) {
                flags = f
                changed = true
            }
            flagsCandidate = f
            when (type) {
                0 -> changed = decodeBasic(b) or changed
                2 -> changed = decodeRadioText(b, versionB) or changed
                4 -> if (!versionB) changed = decodeClock(b) or changed
            }
        }
        if (changed) publish()
    }

    private fun decodeBasic(b: Int): Boolean {
        var changed = false
        val announcement = (b ushr 4) and 1
        if (confirmed(announcement, taCandidate, clean[1]) && (announcement == 1) != ta) {
            ta = announcement == 1
            changed = true
        }
        taCandidate = announcement
        if (valid[3]) {
            val at = 2 * (b and 3)
            changed = accept(ps, psCandidate, at, word[3] ushr 8, clean[1] && clean[3]) or changed
            changed = accept(ps, psCandidate, at + 1, word[3] and 0xFF, clean[1] && clean[3]) or changed
        }
        return changed
    }

    private fun decodeRadioText(b: Int, versionB: Boolean): Boolean {
        var changed = false
        val flag = (b ushr 4) and 1
        if (flag != rtFlag) {
            // A changed A/B flag announces a new text; believe it only from an error-free block.
            if (!clean[1]) return false
            if (rtFlag >= 0) {
                rt.fill(-1)
                rtCandidate.fill(-1)
                changed = true
            }
            rtFlag = flag
        }
        val segment = b and 0xF
        if (versionB) {
            if (valid[3]) {
                changed = accept(rt, rtCandidate, 2 * segment, word[3] ushr 8, clean[1] && clean[3]) or changed
                changed = accept(rt, rtCandidate, 2 * segment + 1, word[3] and 0xFF, clean[1] && clean[3]) or changed
            }
        } else {
            if (valid[2]) {
                changed = accept(rt, rtCandidate, 4 * segment, word[2] ushr 8, clean[1] && clean[2]) or changed
                changed = accept(rt, rtCandidate, 4 * segment + 1, word[2] and 0xFF, clean[1] && clean[2]) or changed
            }
            if (valid[3]) {
                changed = accept(rt, rtCandidate, 4 * segment + 2, word[3] ushr 8, clean[1] && clean[3]) or changed
                changed = accept(rt, rtCandidate, 4 * segment + 3, word[3] and 0xFF, clean[1] && clean[3]) or changed
            }
        }
        return changed
    }

    private fun decodeClock(b: Int): Boolean {
        // The time changes every minute, so repetition cannot confirm it: take error-free groups only.
        if (!(clean[1] && valid[2] && clean[2] && valid[3] && clean[3])) return false
        val mjd = ((b and 3) shl 15) or (word[2] ushr 1)
        val hour = ((word[2] and 1) shl 4) or (word[3] ushr 12)
        val minute = (word[3] ushr 6) and 0x3F
        val magnitude = word[3] and 0x1F
        val offset = if ((word[3] ushr 5) and 1 == 1) -magnitude else magnitude
        if (hour > 23 || minute > 59) return false
        val value = RdsClock(mjd, hour, minute, offset)
        if (value == clock) return false
        clock = value
        return true
    }

    private fun accept(shown: IntArray, candidate: IntArray, at: Int, code: Int, isClean: Boolean): Boolean {
        val ok = confirmed(code, candidate[at], isClean)
        candidate[at] = code
        if (!ok || shown[at] == code) return false
        shown[at] = code
        return true
    }

    private fun clearProgramme() {
        flags = 0
        flagsCandidate = -1
        ta = false
        taCandidate = -1
        ps.fill(-1)
        psCandidate.fill(-1)
        rt.fill(-1)
        rtCandidate.fill(-1)
        rtFlag = -1
        clock = null
    }

    private fun publish() {
        val name = StringBuilder(8)
        var anyName = false
        for (code in ps) {
            name.append(char(code))
            if (code >= 0) anyName = true
        }
        val text = StringBuilder(64)
        for (code in rt) {
            if (code == 0x0D) break
            text.append(char(code))
        }
        state = RdsState(
            pi = pi,
            pty = flags and 0x1F,
            tp = (flags ushr 5) and 1 == 1,
            ta = ta,
            ps = if (anyName) name.toString() else "",
            rt = text.toString().trimEnd(),
            clock = clock,
        )
    }

    /** RDS basic character set, reduced to its ASCII part; everything else shows as a space. */
    private fun char(code: Int): Char = if (code in 0x20..0x7E) code.toChar() else ' '

    private companion object {
        const val MAX_SEARCH_BLOCKS = 3
        const val MAX_BLOCKS_WITHOUT_CLEAN = 50
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsDecoderTest`
Expected: `OK (9 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (147 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): RDS block sync, error correction and group decoding

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 6: Broadcast FM demodulator with stereo

Discriminator, pilot PLL, stereo decoding with a noise-driven blend, 15 kHz low-pass, de-emphasis (§6.4). M and S share one filter as the real and imaginary part of a complex stream, so their delays cannot differ.

**Files:**
- Create: `core/src/main/kotlin/demod/Demodulator.kt`, `core/src/main/kotlin/demod/FmDiscriminator.kt`, `core/src/main/kotlin/demod/StereoDecoder.kt`, `core/src/main/kotlin/demod/WfmDemod.kt`
- Test: `core/src/test/kotlin/demod/WfmDemodTest.kt`

**Interfaces:**
- Consumes: `Pll.processSinCos`, `Pll.reset`, `Deemphasis.reset` (Task 1); `BiquadCascade` (Task 3); `FirDecimator`, `FirDesign.lowPassKaiser(passbandHz, stopbandHz, sampleRate, attenuationDb)` (Plan 1); `MpxGenerator`, `FmModulator`, `Signals.sinadDb` (tests).
- Produces:
  - `interface Demodulator { val inputRate: Double; val outputRate: Double; val channels: Int; fun maxOutput(inCount: Int): Int; fun process(input: FloatArray, inCount: Int, output: FloatArray): Int; fun reset() }`. `process` takes complex samples, may overwrite them, and returns the audio frames written.
  - `interface MultiplexTap { fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int); fun reset() }`.
  - `class FmDiscriminator(sampleRate: Double, deviationHz: Double)` with `fun process(input: FloatArray, count: Int, output: FloatArray)` and `fun reset()`.
  - `class StereoDecoder(mpxRate: Double)` with `val pilotSin: FloatArray`, `val pilotCos: FloatArray`, `var enabled: Boolean`, `val blend: Float`, `val pilotLocked: Boolean`, `val pilotLevel: Double`, `val noiseDb: Double`, `fun process(mpx: FloatArray, count: Int, ms: FloatArray)`, `fun reset()`; companion constants `NOISE_BAND_HZ = 2_000.0`, `QUIET_DB = -52.0`, `NOISY_DB = -40.0`.
  - `class WfmDemod(inputRate: Double, deemphasisSeconds: Double = 50e-6) : Demodulator` (needs at least 120 kHz; always two channels; output rate `inputRate / floor(inputRate / 48 kHz)`) with `var tap: MultiplexTap?`, `var stereoEnabled: Boolean`, `val stereo: Boolean`, `val pilotLocked: Boolean`, `val stereoBlend: Float`, `val stereoNoiseDb: Double`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/demod/WfmDemodTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.MpxGenerator
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class WfmDemodTest {
    private val fs = 240_000.0

    /**
     * Demodulates [seconds] of an FM signal carrying [mpx]; returns interleaved stereo audio at 48 kHz.
     * [cnrDb] adds white noise for that carrier-to-noise ratio in the 240 kHz band.
     */
    private fun demodulate(
        mpx: MpxGenerator,
        demod: WfmDemod,
        seconds: Double,
        block: Int = 16_384,
        cnrDb: Double = Double.POSITIVE_INFINITY,
    ): FloatArray {
        val total = (seconds * fs).toInt()
        val sigma = if (cnrDb.isInfinite()) 0f else sqrt(10.0.pow(-cnrDb / 10) / 2).toFloat()
        val rnd = Random(2)
        val modulator = FmModulator(fs, 75_000.0)
        val message = FloatArray(block)
        val iq = FloatArray(2 * block)
        val out = FloatArray(2 * demod.maxOutput(block))
        val audio = FloatArray(2 * (total / 5 + 16))
        var frames = 0
        var done = 0
        while (done < total) {
            val n = minOf(block, total - done)
            mpx.generate(message, n)
            modulator.modulate(message, n, iq)
            if (sigma > 0f) for (i in 0 until 2 * n) iq[i] += sigma * rnd.nextGaussian().toFloat()
            val got = demod.process(iq, n, out)
            System.arraycopy(out, 0, audio, 2 * frames, 2 * got)
            frames += got
            done += n
        }
        return audio.copyOf(2 * frames)
    }

    @Test
    fun monoToneAtFullDeviationIsClean() {
        val demod = WfmDemod(fs)
        val audio = demodulate(MpxGenerator(fs, 1_000.0, 1_000.0, stereo = false), demod, 1.0)
        val frames = audio.size / 2
        assertEquals(48_000.0, demod.outputRate, 0.0)
        assertEquals(2, demod.channels)
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        assertTrue("SINAD $sinad dB", sinad >= 40.0)
        // 50 µs de-emphasis takes 0.41 dB off a 1 kHz tone
        assertEquals(-0.41, 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)), 0.1)
        assertFalse(demod.stereo)
        for (i in frames / 2 until frames) assertEquals(audio[2 * i], audio[2 * i + 1], 1e-6f)
    }

    @Test
    fun leftOnlyToneStaysOutOfTheRightChannel() {
        val demod = WfmDemod(fs)
        val audio = demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 2.0)
        val frames = audio.size / 2
        assertTrue(demod.pilotLocked)
        assertTrue(demod.stereo)
        val left = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0))
        val right = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1))
        assertEquals(20 * log10(0.91) - 0.41, left, 0.3)
        assertTrue("left $left dB, right $right dB", right <= left - 25.0)
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        assertTrue("SINAD $sinad dB", sinad >= 40.0)
    }

    @Test
    fun differentTonesLandInTheirOwnChannels() {
        val demod = WfmDemod(fs)
        val audio = demodulate(MpxGenerator(fs, 400.0, 3_000.0), demod, 2.0)
        val frames = audio.size / 2
        val l400 = Signals.toneAmplitude(audio, 400.0, 48_000.0, frames / 2, frames, 2, 0)
        val l3000 = Signals.toneAmplitude(audio, 3_000.0, 48_000.0, frames / 2, frames, 2, 0)
        val r400 = Signals.toneAmplitude(audio, 400.0, 48_000.0, frames / 2, frames, 2, 1)
        val r3000 = Signals.toneAmplitude(audio, 3_000.0, 48_000.0, frames / 2, frames, 2, 1)
        assertTrue(20 * log10(l3000 / l400) < -25.0)
        assertTrue(20 * log10(r400 / r3000) < -25.0)
    }

    @Test
    fun stereoCanBeSwitchedOff() {
        val demod = WfmDemod(fs)
        demod.stereoEnabled = false
        val audio = demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 1.0)
        val frames = audio.size / 2
        assertFalse(demod.stereo)
        for (i in frames / 2 until frames) assertEquals(audio[2 * i], audio[2 * i + 1], 1e-6f)
    }

    @Test
    fun noisyReceptionBlendsTowardMono() {
        /** Left and right amplitude of a left-only tone received at [cnrDb]. */
        fun receive(cnrDb: Double, demod: WfmDemod): Pair<Double, Double> {
            val audio = demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 3.0, cnrDb = cnrDb)
            val frames = audio.size / 2
            return Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0) to
                Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1)
        }
        val strong = WfmDemod(fs)
        val (strongLeft, strongRight) = receive(40.0, strong)
        assertEquals(1f, strong.stereoBlend, 0f)
        assertTrue(strong.stereoNoiseDb < StereoDecoder.QUIET_DB)
        assertTrue("separation at 40 dB", 20 * log10(strongLeft / strongRight) > 40.0)

        val marginal = WfmDemod(fs)
        val (marginalLeft, marginalRight) = receive(22.0, marginal)
        assertTrue("blend ${marginal.stereoBlend}", marginal.stereoBlend > 0.2f && marginal.stereoBlend < 0.7f)
        val separation = 20 * log10(marginalLeft / marginalRight)
        assertTrue("separation $separation dB at 22 dB", separation > 3.0 && separation < 15.0)

        val weak = WfmDemod(fs)
        val (weakLeft, weakRight) = receive(14.0, weak)
        assertTrue("the pilot itself is still locked", weak.pilotLocked)
        assertEquals(0f, weak.stereoBlend, 0f)
        assertFalse(weak.stereo)
        assertTrue(weak.stereoNoiseDb > StereoDecoder.NOISY_DB)
        assertEquals(weakLeft, weakRight, 0.02)
    }

    @Test
    fun theTapGetsTheMultiplexWithThePilotPhase() {
        val demod = WfmDemod(fs)
        var samples = 0
        var resets = 0
        var inPhase = 0.0
        var locked = false
        demod.tap = object : MultiplexTap {
            override fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int) {
                locked = pilotLocked
                if (!pilotLocked) return
                for (n in 0 until count) inPhase += mpx[n] * pilotCos[n]
                samples += count
            }

            override fun reset() {
                resets++
            }
        }
        demodulate(MpxGenerator(fs, 1_000.0, 0.0), demod, 2.0)
        assertTrue(locked)
        assertTrue(samples > 240_000)
        assertEquals("the pilot is 0.09·cos θ", 0.09, 2 * inPhase / samples, 0.005)
        demod.reset()
        assertEquals(1, resets)
    }

    @Test
    fun blockSizeDoesNotChangeTheSettledAudio() {
        // The stereo blend starts at a block boundary, so only the settled part must match.
        val a = demodulate(MpxGenerator(fs, 1_000.0, 0.0), WfmDemod(fs), 1.5, block = 16_384)
        val b = demodulate(MpxGenerator(fs, 1_000.0, 0.0), WfmDemod(fs), 1.5, block = 1_000)
        assertEquals(a.size, b.size)
        for (i in a.size * 2 / 3 until a.size) assertEquals(a[i], b[i], 1e-4f)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.demod.WfmDemodTest`
Expected: the build fails with `unresolved reference 'WfmDemod'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/demod/Demodulator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

/**
 * Turns channel-filtered complex samples into audio (§6.4). The audio leaves at [outputRate] with
 * [channels] interleaved channels; the engine resamples it to the sink's rate afterwards.
 */
interface Demodulator {
    /** Sample rate of the complex input in Hz. */
    val inputRate: Double

    /** Sample rate of the audio output in Hz. */
    val outputRate: Double

    /** Audio channels produced: 1 or 2. */
    val channels: Int

    /** Upper bound of the audio frames produced for [inCount] input samples. */
    fun maxOutput(inCount: Int): Int

    /**
     * Demodulates [inCount] complex samples from [input] (which may be overwritten) and writes
     * interleaved audio frames to [output]; returns the number of frames.
     */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int

    /** Clears all filter and loop state, for use after a retune. */
    fun reset()
}

/** Receives the FM multiplex of a [WfmDemod] beside the audio path; the RDS demodulator is one. */
interface MultiplexTap {
    /**
     * Takes [count] multiplex samples (±1.0 is ±75 kHz of deviation). [pilotSin] and [pilotCos]
     * hold sin θ and cos θ of the pilot phase for each sample (pilot = cos θ); they mean something
     * only while [pilotLocked].
     */
    fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int)

    /** Clears all state, for use after a retune. */
    fun reset()
}
```

Create `core/src/main/kotlin/demod/FmDiscriminator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import kotlin.math.PI
import kotlin.math.atan2

/** Polar FM discriminator arg(x[n]·x*[n−1]), scaled so that ±[deviationHz] gives ±1.0 (§6.4). */
class FmDiscriminator(sampleRate: Double, deviationHz: Double) {
    private val gain = (sampleRate / (2 * PI * deviationHz)).toFloat()
    private var prevRe = 1f
    private var prevIm = 0f

    /** Writes one real output sample per complex input sample. */
    fun process(input: FloatArray, count: Int, output: FloatArray) {
        var pr = prevRe
        var pi = prevIm
        for (n in 0 until count) {
            val re = input[2 * n]
            val im = input[2 * n + 1]
            output[n] = atan2(im * pr - re * pi, re * pr + im * pi) * gain
            pr = re
            pi = im
        }
        prevRe = pr
        prevIm = pi
    }

    fun reset() {
        prevRe = 1f
        prevIm = 0f
    }
}
```

Create `core/src/main/kotlin/demod/StereoDecoder.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.BiquadCascade
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Pll
import kotlin.math.exp
import kotlin.math.log10

/**
 * Pilot-tone stereo decoder front half (§6.4): a PLL locks to the 19 kHz pilot and the multiplex
 * is multiplied by the regenerated 38 kHz subcarrier. The transmitted pilot is sin ωt and the
 * subcarrier sin 2ωt (ITU-R BS.450); the PLL reports θ with pilot = cos θ, so the subcarrier is
 * −sin 2θ. The output pairs (M, S·blend) still need the 15 kHz low-pass; L = M + S, R = M − S.
 *
 * The blend goes to mono without a locked pilot and when the difference channel is noisy. That
 * noise is measured where the transmitter puts nothing: in quadrature to the subcarrier (cos 2θ).
 */
class StereoDecoder(private val mpxRate: Double) {
    private val pll = Pll(mpxRate, 19_000.0)
    private val blendStep = (1.0 / (0.05 * mpxRate)).toFloat()   // full swing in 50 ms
    private val noiseFilter = BiquadCascade.lowPass(1, 4, NOISE_BAND_HZ, mpxRate)
    private var quadrature = FloatArray(0)
    private var noisePower = 1.0   // 0 dB: as noisy as it gets, until measured
    private var haveNoise = false

    /** sin θ of the pilot phase for each sample of the last block. */
    var pilotSin = FloatArray(0)
        private set

    /** cos θ of the pilot phase for each sample of the last block. */
    var pilotCos = FloatArray(0)
        private set

    /** Set to false to force mono. */
    var enabled = true

    /** 0 = mono … 1 = full stereo; ramps so that switching never clicks. */
    var blend = 0f
        private set

    val pilotLocked: Boolean get() = pll.locked

    /** Pilot amplitude in multiplex units (0.09 nominal). */
    val pilotLevel: Double get() = pll.amplitude

    /**
     * Noise of the difference channel within [NOISE_BAND_HZ], in dB relative to full deviation.
     * Stereo is full at [QUIET_DB] and below, and mono from [NOISY_DB].
     */
    val noiseDb: Double get() = 10 * log10(noisePower + 1e-30)

    /** Writes [count] interleaved (M, S) pairs for the multiplex samples in [mpx]. */
    fun process(mpx: FloatArray, count: Int, ms: FloatArray) {
        if (pilotSin.size < count) {
            pilotSin = FloatArray(count)
            pilotCos = FloatArray(count)
            quadrature = FloatArray(count)
        }
        val ps = pilotSin
        val pc = pilotCos
        pll.processSinCos(mpx, count, ps, pc)
        measureNoise(mpx, count)
        val quality = ((NOISY_DB - noiseDb) / (NOISY_DB - QUIET_DB)).coerceIn(0.0, 1.0).toFloat()
        val target = if (enabled && pll.locked) quality else 0f
        var b = blend
        for (n in 0 until count) {
            if (b < target) b = minOf(target, b + blendStep) else if (b > target) b = maxOf(target, b - blendStep)
            val x = mpx[n]
            ms[2 * n] = x
            ms[2 * n + 1] = x * (-4f * ps[n] * pc[n]) * b   // 2·(−sin 2θ) = −4·sin θ·cos θ
        }
        blend = b
    }

    /**
     * Demodulates with 2·cos 2θ, low-passes and averages the power over about 50 ms. Until the pilot
     * is locked the phase is meaningless and the programme itself would be measured, so the average
     * starts afresh at lock.
     */
    private fun measureNoise(mpx: FloatArray, count: Int) {
        if (count == 0) return
        val q = quadrature
        val ps = pilotSin
        val pc = pilotCos
        for (n in 0 until count) q[n] = mpx[n] * 2f * (pc[n] * pc[n] - ps[n] * ps[n])
        noiseFilter.process(q, count)
        if (!pll.locked) {
            haveNoise = false
            noisePower = 1.0
            return
        }
        var sum = 0.0
        for (n in 0 until count) sum += q[n] * q[n]
        val mean = sum / count
        if (haveNoise) {
            noisePower += (1 - exp(-count / (NOISE_SECONDS * mpxRate))) * (mean - noisePower)
        } else {
            noisePower = mean
            haveNoise = true
        }
    }

    fun reset() {
        pll.reset()
        noiseFilter.reset()
        haveNoise = false
        noisePower = 1.0
        blend = 0f
    }

    companion object {
        /** Width of the band the noise is measured in, in Hz. */
        const val NOISE_BAND_HZ = 2_000.0

        /** Noise up to which stereo is full: a carrier-to-noise ratio of about 28 dB in 240 kHz. */
        const val QUIET_DB = -52.0

        /** Noise from which the output is mono: a carrier-to-noise ratio of about 16 dB in 240 kHz. */
        const val NOISY_DB = -40.0
        private const val NOISE_SECONDS = 0.05
    }
}
```

Create `core/src/main/kotlin/demod/WfmDemod.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Deemphasis
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import kotlin.math.floor

/**
 * Broadcast FM demodulator (§6.4): discriminator, pilot-tone stereo decoder, 15 kHz low-pass and
 * de-emphasis. M and S travel as the real and imaginary part of one complex stream through one
 * decimating filter, so their delays match exactly. The output is always two channels; without a
 * locked pilot both carry the mono signal.
 */
class WfmDemod(
    override val inputRate: Double,
    deemphasisSeconds: Double = 50e-6,
) : Demodulator {
    init {
        require(inputRate >= 120_000.0) { "WFM needs at least 120 kHz of input rate, got $inputRate Hz" }
    }

    private val decimation = maxOf(1, floor(inputRate / 48_000.0).toInt())
    override val outputRate: Double = inputRate / decimation
    override val channels: Int = 2

    private val discriminator = FmDiscriminator(inputRate, 75_000.0)
    private val stereoDecoder = StereoDecoder(inputRate)
    private val audioFilter = FirDecimator(FirDesign.lowPassKaiser(15_000.0, 19_000.0, inputRate, 60.0), decimation)
    private val deemphasis = Deemphasis(2, deemphasisSeconds, outputRate)
    private var mpx = FloatArray(0)
    private var ms = FloatArray(0)

    /** When set, the multiplex is also handed to this tap (the RDS demodulator). */
    var tap: MultiplexTap? = null

    /** False forces mono even when a pilot is present. */
    var stereoEnabled: Boolean
        get() = stereoDecoder.enabled
        set(value) {
            stereoDecoder.enabled = value
        }

    /** True while the output is (mostly) stereo. */
    val stereo: Boolean get() = stereoDecoder.blend >= 0.5f

    val pilotLocked: Boolean get() = stereoDecoder.pilotLocked

    /** 0 = mono … 1 = full stereo. */
    val stereoBlend: Float get() = stereoDecoder.blend

    /** Noise of the stereo difference channel in dB relative to full deviation (see [StereoDecoder.noiseDb]). */
    val stereoNoiseDb: Double get() = stereoDecoder.noiseDb

    override fun maxOutput(inCount: Int): Int = audioFilter.maxOutput(inCount)

    override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        if (mpx.size < inCount) {
            mpx = FloatArray(inCount)
            ms = FloatArray(2 * inCount)
        }
        discriminator.process(input, inCount, mpx)
        stereoDecoder.process(mpx, inCount, ms)
        tap?.process(mpx, stereoDecoder.pilotSin, stereoDecoder.pilotCos, stereoDecoder.pilotLocked, inCount)
        val frames = audioFilter.process(ms, inCount, output)
        for (i in 0 until frames) {
            val m = output[2 * i]
            val s = output[2 * i + 1]
            output[2 * i] = m + s
            output[2 * i + 1] = m - s
        }
        deemphasis.process(output, frames)
        return frames
    }

    override fun reset() {
        discriminator.reset()
        stereoDecoder.reset()
        audioFilter.reset()
        deemphasis.reset()
        tap?.reset()
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.demod.WfmDemodTest`
Expected: `OK (7 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (154 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): broadcast FM demodulator with pilot-tone stereo and noise blend

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 7: RDS demodulator, decoded end to end

The 57 kHz subcarrier becomes bits (§6.4): mix down with three times the pilot phase (or a free-running oscillator when there is no pilot), decimate, matched filter, Gardner chip timing, Costas loop, biphase pairing, differential decoding. The tests run a whole FM station through `WfmDemod` and check the decoded text, then add noise and hold the block error rate under a pass line (§12).

**Files:**
- Create: `core/src/main/kotlin/rds/RdsDemodulator.kt`
- Test: `core/src/test/kotlin/rds/RdsTestSignals.kt`, `core/src/test/kotlin/rds/RdsEndToEndTest.kt`, `core/src/test/kotlin/rds/RdsNoiseTest.kt`

**Interfaces:**
- Consumes: `MultiplexTap`, `WfmDemod` (Task 6); `RdsDecoder` (Task 5); `RdsCode.pulse`, `RdsCode.CHIP_RATE` (Task 4); `DecimatorChain.design(inputRate, factor, passbandHz, attenuationDb)`, `FirDecimator`, `FirDesign`, `Nco` (Plan 1); the generators (tests).
- Produces:
  - `class RdsDemodulator(mpxRate: Double, decoder: RdsDecoder) : MultiplexTap` (needs at least 125 kHz of multiplex rate) with `val chipPower: Float`.
  - Test helper `RdsTestSignals.receive(encoder: RdsEncoder, seconds: Double, stereo: Boolean = true, rdsPhaseTurns: Double = 0.0, cnrDb: Double = +∞, rateError: Double = 0.0, seed: Long = 1): RdsDecoder` at `RdsTestSignals.FS = 240_000.0`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/rds/RdsTestSignals.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.demod.WfmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.MpxGenerator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsBaseband
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

/** Runs an FM broadcast signal with RDS through [WfmDemod] and an [RdsDemodulator]. */
object RdsTestSignals {
    const val FS = 240_000.0

    /**
     * Demodulates [seconds] of a station sending [encoder]'s groups. [cnrDb] adds white noise for
     * that carrier-to-noise ratio in the 240 kHz band; [rateError] is the relative error of the
     * transmitter's clocks against the receiver's (5e-5 = 50 ppm).
     */
    fun receive(
        encoder: RdsEncoder,
        seconds: Double,
        stereo: Boolean = true,
        rdsPhaseTurns: Double = 0.0,
        cnrDb: Double = Double.POSITIVE_INFINITY,
        rateError: Double = 0.0,
        seed: Long = 1,
    ): RdsDecoder {
        val txRate = FS * (1 + rateError)
        val mpx = MpxGenerator(txRate, 1_000.0, 400.0, stereo = stereo, rds = RdsBaseband(encoder.bits, txRate), rdsPhaseTurns = rdsPhaseTurns)
        val modulator = FmModulator(txRate, 75_000.0)
        val decoder = RdsDecoder()
        val demod = WfmDemod(FS)
        demod.tap = RdsDemodulator(FS, decoder)
        val sigma = if (cnrDb.isInfinite()) 0f else sqrt(10.0.pow(-cnrDb / 10) / 2).toFloat()
        val rnd = Random(seed)
        val block = 16_384
        val message = FloatArray(block)
        val iq = FloatArray(2 * block)
        val audio = FloatArray(2 * demod.maxOutput(block))
        var left = (seconds * FS).toInt()
        while (left > 0) {
            val n = minOf(block, left)
            mpx.generate(message, n)
            modulator.modulate(message, n, iq)
            if (sigma > 0f) for (i in 0 until 2 * n) iq[i] += sigma * rnd.nextGaussian().toFloat()
            demod.process(iq, n, audio)
            left -= n
        }
        return decoder
    }
}
```

Create `core/src/test/kotlin/rds/RdsEndToEndTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RdsEndToEndTest {
    private val encoder = RdsEncoder(0xD318, "SPECTRA ", "Hello from SpectraSDR, RDS test", pty = 10)

    private fun assertDecoded(decoder: RdsDecoder) {
        assertTrue(decoder.synced)
        assertEquals(0xD318, decoder.state.pi)
        assertEquals("SPECTRA ", decoder.state.ps)
        assertEquals("Hello from SpectraSDR, RDS test", decoder.state.rt)
        assertEquals(10, decoder.state.pty)
    }

    @Test
    fun decodesAStereoStationExactly() {
        val decoder = RdsTestSignals.receive(encoder, 4.0)
        assertDecoded(decoder)
        assertEquals(0L, decoder.blocksFailed)
        assertEquals(0L, decoder.blocksCorrected)
    }

    @Test
    fun subcarrierInQuadratureWithThePilotHarmonicDecodesToo() {
        assertDecoded(RdsTestSignals.receive(encoder, 4.0, rdsPhaseTurns = 0.25))
        assertDecoded(RdsTestSignals.receive(encoder, 4.0, rdsPhaseTurns = 0.4))
    }

    @Test
    fun monoStationWithoutPilotDecodesWithTheFreeRunningCarrier() {
        assertDecoded(RdsTestSignals.receive(encoder, 5.0, stereo = false))
    }

    @Test
    fun toleratesFiftyPartsPerMillionOfClockError() {
        assertDecoded(RdsTestSignals.receive(encoder, 5.0, rateError = 5e-5))
        assertDecoded(RdsTestSignals.receive(encoder, 5.0, rateError = -5e-5, stereo = false))
    }
}
```

Create `core/src/test/kotlin/rds/RdsNoiseTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RDS on weak signals (§12): the share of blocks lost at each signal-to-noise ratio stays under a pass line. */
class RdsNoiseTest {
    private val encoder = RdsEncoder(0xD318, "SPECTRA ", "Hello from SpectraSDR, RDS test", pty = 10)

    /** Twelve seconds (about 545 blocks) of the station at [cnrDb] in the 240 kHz band. */
    private fun receive(cnrDb: Double) = RdsTestSignals.receive(encoder, 12.0, cnrDb = cnrDb, seed = 1)

    private fun blocks(d: RdsDecoder) = d.blocksClean + d.blocksCorrected + d.blocksFailed

    @Test
    fun blockErrorRateStaysUnderItsPassLine() {
        // Carrier-to-noise ratio in dB to the largest share of undecodable blocks allowed there.
        // Measured when the test was written: 0 %, 0 %, 1.5 % and 13 %.
        val passLine = listOf(20.0 to 0.005, 16.0 to 0.02, 14.0 to 0.05, 12.0 to 0.25)
        for ((cnrDb, limit) in passLine) {
            val d = receive(cnrDb)
            val total = blocks(d)
            assertTrue("CNR $cnrDb dB: only $total blocks", total > 500)
            assertTrue("CNR $cnrDb dB: ${d.blocksFailed} of $total blocks failed", d.blocksFailed <= limit * total)
            if (cnrDb >= 14.0) {
                assertEquals("CNR $cnrDb dB", 0xD318, d.state.pi)
                assertEquals("CNR $cnrDb dB", "SPECTRA ", d.state.ps)
                assertEquals("CNR $cnrDb dB", "Hello from SpectraSDR, RDS test", d.state.rt)
            }
        }
    }

    @Test
    fun errorCorrectionRepairsMostDamagedBlocksOfAWeakSignal() {
        val d = receive(14.0)
        val total = blocks(d)
        assertTrue("${d.blocksCorrected} corrected of $total", d.blocksCorrected > total / 10)
        assertTrue("${d.blocksCorrected} corrected, ${d.blocksFailed} failed", d.blocksCorrected > 5 * d.blocksFailed)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsEndToEndTest`
Expected: the build fails with `unresolved reference 'RdsDemodulator'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/rds/RdsDemodulator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.rds

import io.github.radhwanalhajjaj.spectrasdr.core.demod.MultiplexTap
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChain
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Nco
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recovers the RDS bit stream from the FM multiplex (§6.4) and feeds it to [decoder].
 *
 * The 57 kHz subcarrier is mixed to 0 Hz with three times the pilot phase while the pilot is
 * locked (the standard locks the subcarrier to the pilot's third harmonic), otherwise with a
 * free-running oscillator. After decimation, a channel filter and the matched filter, a Gardner
 * loop times the biphase chips, a decision-directed Costas loop removes the remaining carrier
 * phase, chip pairs become bits and differential decoding undoes the transmitter's encoding.
 */
class RdsDemodulator(mpxRate: Double, private val decoder: RdsDecoder) : MultiplexTap {
    init {
        require(mpxRate >= 125_000.0) { "RDS needs at least 125 kHz of multiplex rate, got $mpxRate Hz" }
    }

    private val decimation = maxOf(1, floor(mpxRate / 19_000.0).toInt())
    private val rate = mpxRate / decimation
    private val chain = DecimatorChain.design(mpxRate, decimation, 2_400.0, 60.0)
    private val channelFilter = FirDecimator(FirDesign.lowPassKaiser(2_400.0, 4_000.0, rate, 60.0), 1)
    private val matchedFilter = FirDecimator(matchedTaps(rate), 1)
    private val freeRunning = Nco(mpxRate).apply { frequencyHz = -57_000.0 }
    private var baseband = FloatArray(0)
    private var work = FloatArray(0)

    // Chip timing (Gardner)
    private val chipStep = RdsCode.CHIP_RATE / rate
    private var clock = 0.0
    private var prevRe = 0f
    private var prevIm = 0f
    private var midRe = 0f
    private var midIm = 0f
    private var lastRe = 0f
    private var lastIm = 0f
    private var power = 0f

    // Carrier phase (Costas, one update per chip)
    private var phi = 0.0
    private var freq = 0.0

    // Biphase pairing and differential decoding
    private var prevChip = 0f
    private var parity = 0
    private var pairParity = 0
    private val pairEnergy = FloatArray(2)
    private var prevBit = 0

    /** Mean power of the chips, a rough signal-strength indication for diagnostics. */
    val chipPower: Float get() = power

    /**
     * Processes [count] multiplex samples. [pilotSin] and [pilotCos] hold sin θ and cos θ of the
     * pilot phase for each sample (pilot = cos θ); they are used only while [pilotLocked].
     */
    override fun process(mpx: FloatArray, pilotSin: FloatArray, pilotCos: FloatArray, pilotLocked: Boolean, count: Int) {
        if (baseband.size < 2 * count) {
            baseband = FloatArray(2 * count)
            work = FloatArray(2 * count)
        }
        val bb = baseband
        if (pilotLocked) {
            for (n in 0 until count) {
                val s = pilotSin[n]
                val c = pilotCos[n]
                val x = mpx[n]
                bb[2 * n] = x * (4f * c * c * c - 3f * c)      // x·cos 3θ
                bb[2 * n + 1] = -x * (3f * s - 4f * s * s * s)  // −x·sin 3θ
            }
        } else {
            for (n in 0 until count) {
                bb[2 * n] = mpx[n]
                bb[2 * n + 1] = 0f
            }
            freeRunning.mix(bb, 0, count)
        }
        var m = chain.process(bb, count, work)
        m = channelFilter.process(work, m, bb)
        m = matchedFilter.process(bb, m, work)
        for (k in 0 until m) sample(work[2 * k], work[2 * k + 1])
    }

    /** Clears all loop state, for use after a retune. */
    override fun reset() {
        chain.reset()
        channelFilter.reset()
        matchedFilter.reset()
        clock = 0.0
        prevRe = 0f
        prevIm = 0f
        midRe = 0f
        midIm = 0f
        lastRe = 0f
        lastIm = 0f
        power = 0f
        phi = 0.0
        freq = 0.0
        prevChip = 0f
        parity = 0
        pairParity = 0
        pairEnergy.fill(0f)
        prevBit = 0
    }

    private fun sample(re: Float, im: Float) {
        val before = clock
        clock += chipStep
        if (before < 0.5 && clock >= 0.5) {
            val f = ((0.5 - before) / chipStep).toFloat()
            midRe = prevRe + f * (re - prevRe)
            midIm = prevIm + f * (im - prevIm)
        }
        if (clock >= 1.0) {
            val f = ((1.0 - before) / chipStep).toFloat()
            clock -= 1.0
            chip(prevRe + f * (re - prevRe), prevIm + f * (im - prevIm))
        }
        prevRe = re
        prevIm = im
    }

    private fun chip(re: Float, im: Float) {
        power += POWER_ALPHA * (re * re + im * im - power)
        val scale = power + 1e-20f

        // Gardner: positive error = sampling late, so the next chip instant is brought forward.
        val timing = ((re - lastRe) * midRe + (im - lastIm) * midIm) / scale
        clock += (TIMING_GAIN * timing).coerceIn(-0.1f, 0.1f)
        lastRe = re
        lastIm = im

        // Costas: rotate by −φ, steer φ so the data sits on the real axis.
        val c = cos(phi).toFloat()
        val s = sin(phi).toFloat()
        val dRe = re * c + im * s
        val dIm = im * c - re * s
        val error = (if (dRe >= 0) dIm else -dIm) / sqrt(scale)
        freq = (freq + CARRIER_KI * error).coerceIn(-MAX_CARRIER_STEP, MAX_CARRIER_STEP)
        phi += freq + CARRIER_KP * error
        if (phi > PI) phi -= 2 * PI else if (phi < -PI) phi += 2 * PI

        // Biphase: the two chips of a bit are always opposite, while chips on either side of a bit
        // boundary are opposite only when the next data bit is 0. The right pairing therefore
        // carries more energy, by a factor of 1/P(bit = 0); a 20 % margin over a 500-chip average
        // decides without flapping on runs of zeros.
        val diff = prevChip - dRe
        pairEnergy[parity] += ENERGY_ALPHA * (diff * diff - pairEnergy[parity])
        if (parity == pairParity) {
            val bit = if (diff > 0) 1 else 0
            decoder.push(bit xor prevBit)
            prevBit = bit
        } else if (pairEnergy[parity] > PAIRING_MARGIN * pairEnergy[pairParity]) {
            pairParity = parity
        }
        prevChip = dRe
        parity = parity xor 1
    }

    private companion object {
        const val POWER_ALPHA = 0.01f
        const val ENERGY_ALPHA = 0.002f
        const val PAIRING_MARGIN = 1.2f
        const val TIMING_GAIN = 0.03f
        // 20 Hz loop at the chip rate: ωn = 2π·20/2375, damping 0.707
        const val CARRIER_KP = 0.0748
        const val CARRIER_KI = 0.0028
        // The loop never needs to follow more than ±30 Hz (transmitter tolerance plus receiver clock error)
        const val MAX_CARRIER_STEP = 2 * PI * 30.0 / RdsCode.CHIP_RATE

        /** The chip pulse sampled at [rate], Hann-windowed over ±3 chips, with unit DC gain. */
        fun matchedTaps(rate: Double): FloatArray {
            val perChip = rate / RdsCode.CHIP_RATE
            val half = kotlin.math.ceil(3 * perChip).toInt()
            val taps = DoubleArray(2 * half + 1) { i ->
                val k = i - half
                RdsCode.pulse(k / perChip) * 0.5 * (1 + cos(PI * k / (half + 1)))
            }
            val sum = taps.sum()
            return FloatArray(taps.size) { (taps[it] / sum).toFloat() }
        }
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsEndToEndTest io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsNoiseTest`
Expected: `OK (6 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (160 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): RDS demodulator, decoded end to end

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 8: Narrow-band demodulators and squelch

NFM, AM, SSB and CW (§6.4), and the squelch that gates their audio. SSB and CW share one demodulator: move a slice of the channel to 0 Hz, low-pass it, move it back, take the real part.

**Files:**
- Create: `core/src/main/kotlin/demod/NfmDemod.kt`, `core/src/main/kotlin/demod/AmDemod.kt`, `core/src/main/kotlin/demod/PassbandDemod.kt`, `core/src/main/kotlin/demod/Squelch.kt`
- Test: `core/src/test/kotlin/demod/NarrowDemodTest.kt`, `core/src/test/kotlin/demod/SquelchTest.kt`

**Interfaces:**
- Consumes: `Demodulator`, `FmDiscriminator` (Task 6); `BiquadCascade` (Task 3); `Agc.reset`, `DcBlocker.reset`, `Deemphasis.reset` (Task 1); `DecimatorChain`, `FirDecimator`, `FirDesign`, `Nco` (Plan 1).
- Produces:
  - `class NfmDemod(inputRate: Double, deviationHz: Double = 5_000.0, deemphasis: Boolean = true) : Demodulator`: mono, output rate = input rate.
  - `class AmDemod(inputRate: Double, agc: Boolean = true) : Demodulator`: mono, output rate = input rate.
  - `open class PassbandDemod(inputRate: Double, centerHz: Double, halfWidthHz: Double, transitionHz: Double, agc: Boolean) : Demodulator`: mono, output rate `inputRate / max(1, floor(inputRate / 12 kHz))`.
  - `enum class Sideband { USB, LSB }`; `class SsbDemod(inputRate: Double, sideband: Sideband, agc: Boolean = true) : PassbandDemod` (300–2,800 Hz); `class CwDemod(inputRate: Double, pitchHz: Double = 700.0, bandwidthHz: Double = 500.0, agc: Boolean = true) : PassbandDemod`.
  - `class Squelch(hysteresisDb: Double = 3.0)` with `var thresholdDb: Double` (−∞ = always open), `var auto: Boolean`, `var noiseFloorDb: Double`, `val open: Boolean`, `val levelDb: Double`, `val effectiveThresholdDb: Double`, `fun update(powerDb: Double): Boolean`, `fun gate(audio: FloatArray, frames: Int, channels: Int, sampleRate: Double)`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/demod/NarrowDemodTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class NarrowDemodTest {
    private val fs = 48_000.0

    /** Runs [iq] through [demod] in blocks; returns the mono audio. */
    private fun run(demod: Demodulator, iq: FloatArray, block: Int = 4_096): FloatArray {
        val total = iq.size / 2
        val audio = FloatArray(demod.maxOutput(total) + 16)
        val chunk = FloatArray(2 * block)
        val out = FloatArray(demod.maxOutput(block))
        var frames = 0
        var done = 0
        while (done < total) {
            val n = minOf(block, total - done)
            System.arraycopy(iq, 2 * done, chunk, 0, 2 * n)
            val got = demod.process(chunk, n, out)
            System.arraycopy(out, 0, audio, frames, got)
            frames += got
            done += n
        }
        return audio.copyOf(frames)
    }

    private fun nfmSignal(toneHz: Double, deviationHz: Double, seconds: Double = 1.0, noise: Double = 0.0): FloatArray {
        val n = (seconds * fs).toInt()
        val message = Signals.realTone(n, toneHz, fs)
        val iq = FloatArray(2 * n)
        FmModulator(fs, deviationHz).modulate(message, n, iq)
        if (noise > 0) {
            val rnd = Random(2)
            for (i in iq.indices) iq[i] += (noise * rnd.nextGaussian()).toFloat()
        }
        return iq
    }

    @Test
    fun nfmRecoversAToneAtSixtyPercentDeviation() {
        val audio = run(NfmDemod(fs), nfmSignal(1_000.0, 3_000.0, noise = 0.01))
        val n = audio.size
        assertEquals(0.6, Signals.toneAmplitude(audio, 1_000.0, fs, n / 2, n), 0.03)
        val sinad = Signals.sinadDb(audio, 1_000.0, fs, n / 2, n)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
    }

    @Test
    fun nfmRemovesSubAudibleTones() {
        val audio = run(NfmDemod(fs), nfmSignal(100.0, 500.0))
        val n = audio.size
        val level = 20 * log10(Signals.toneAmplitude(audio, 100.0, fs, n / 2, n))
        assertTrue("100 Hz at $level dBFS", level < -40.0)
    }

    @Test
    fun nfmWithoutDeemphasisIsFlat() {
        val demod = NfmDemod(fs, deviationHz = 2_500.0, deemphasis = false)
        val low = run(demod, nfmSignal(600.0, 1_250.0))
        val high = run(NfmDemod(fs, deviationHz = 2_500.0, deemphasis = false), nfmSignal(2_400.0, 1_250.0))
        assertEquals(0.5, Signals.toneAmplitude(low, 600.0, fs, low.size / 2, low.size), 0.03)
        assertEquals(0.5, Signals.toneAmplitude(high, 2_400.0, fs, high.size / 2, high.size), 0.04)
    }

    private fun amSignal(toneHz: Double, depth: Double, carrier: Double, seconds: Double = 2.0, offsetHz: Double = 0.0): FloatArray {
        val n = (seconds * fs).toInt()
        val iq = Signals.complexTone(n, offsetHz, fs, carrier)
        val rnd = Random(4)
        for (k in 0 until n) {
            val m = (1 + depth * sin(2 * PI * toneHz * k / fs)).toFloat()
            iq[2 * k] = iq[2 * k] * m + (0.001 * rnd.nextGaussian()).toFloat()
            iq[2 * k + 1] = iq[2 * k + 1] * m + (0.001 * rnd.nextGaussian()).toFloat()
        }
        return iq
    }

    @Test
    fun amRecoversAToneAndLevelsIt() {
        val audio = run(AmDemod(fs), amSignal(1_000.0, 0.5, 0.2, offsetHz = 150.0))
        val n = audio.size
        val sinad = Signals.sinadDb(audio, 1_000.0, fs, n / 2, n)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
        assertEquals("AGC brings the peak to its target", 0.5, Signals.peak(audio, n / 2, n).toDouble(), 0.05)
    }

    @Test
    fun amWithoutAgcFollowsTheModulationDepth() {
        val audio = run(AmDemod(fs, agc = false), amSignal(1_000.0, 0.5, 0.2))
        val n = audio.size
        assertEquals(0.1, Signals.toneAmplitude(audio, 1_000.0, fs, n / 2, n), 0.005)
    }

    private fun ssbLevelDb(demod: Demodulator, toneOffsetHz: Double, audioHz: Double): Double {
        val n = 96_000
        val audio = run(demod, Signals.complexTone(n, toneOffsetHz, fs, 0.1))
        val m = audio.size
        return 20 * log10(Signals.toneAmplitude(audio, audioHz, demod.outputRate, m / 2, m) + 1e-12)
    }

    @Test
    fun usbPassesItsSidebandAndRejectsTheOther() {
        val wanted = ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), 1_000.0, 1_000.0)
        val unwanted = ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), -1_000.0, 1_000.0)
        assertEquals(-20.0, wanted, 0.5)
        assertTrue("wanted $wanted dB, unwanted $unwanted dB", unwanted <= wanted - 40.0)
    }

    @Test
    fun lsbPassesItsSidebandAndRejectsTheOther() {
        val wanted = ssbLevelDb(SsbDemod(fs, Sideband.LSB, agc = false), -1_500.0, 1_500.0)
        val unwanted = ssbLevelDb(SsbDemod(fs, Sideband.LSB, agc = false), 1_500.0, 1_500.0)
        assertEquals(-20.0, wanted, 0.5)
        assertTrue("wanted $wanted dB, unwanted $unwanted dB", unwanted <= wanted - 40.0)
    }

    @Test
    fun ssbPassbandIsFlatFromThreeHundredToTwentyEightHundredHertz() {
        for (hz in listOf(400.0, 1_500.0, 2_700.0)) {
            assertEquals("at $hz Hz", -20.0, ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), hz, hz), 1.0)
        }
        assertTrue(ssbLevelDb(SsbDemod(fs, Sideband.USB, agc = false), 3_300.0, 3_300.0) < -60.0)
    }

    @Test
    fun ssbAgcLevelsTheAudio() {
        val demod = SsbDemod(fs, Sideband.USB)
        val audio = run(demod, Signals.complexTone(96_000, 1_000.0, fs, 0.01))
        assertEquals(0.5, Signals.peak(audio, audio.size / 2, audio.size).toDouble(), 0.05)
        assertEquals(12_000.0, demod.outputRate, 0.0)
    }

    @Test
    fun cwPutsTheCarrierAtThePitchAndRejectsNeighbours() {
        val demod = CwDemod(fs, agc = false)
        val n = 96_000
        val audio = run(demod, Signals.complexTone(n, 700.0, fs, 0.1))
        val m = audio.size
        assertEquals(700.0, Signals.zeroCrossingFrequency(audio, demod.outputRate, m / 2, m), 0.5)
        assertEquals(-20.0, 20 * log10(Signals.toneAmplitude(audio, 700.0, demod.outputRate, m / 2, m)), 0.5)
        val neighbour = ssbLevelDb(CwDemod(fs, agc = false), 1_300.0, 1_300.0)
        val image = ssbLevelDb(CwDemod(fs, agc = false), -700.0, 700.0)
        assertTrue("neighbour at $neighbour dB", neighbour < -60.0)
        assertTrue("image at $image dB", image < -60.0)
    }

    @Test
    fun demodulatorsAcceptOddChannelRates() {
        // 2.048 MS/s decimated by 42 gives 48,761.9 Hz
        val rate = 2_048_000.0 / 42
        val n = 97_524
        val demod = SsbDemod(rate, Sideband.USB, agc = false)
        val audio = run(demod, Signals.complexTone(n, 1_000.0, rate, 0.1))
        val m = audio.size
        assertEquals(rate / 4, demod.outputRate, 1e-6)
        assertEquals(-20.0, 20 * log10(Signals.toneAmplitude(audio, 1_000.0, demod.outputRate, m / 2, m)), 0.5)
    }
}
```

Create `core/src/test/kotlin/demod/SquelchTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquelchTest {
    @Test
    fun staysOpenUntilAThresholdIsSet() {
        val squelch = Squelch()
        assertTrue(squelch.update(-120.0))
        squelch.thresholdDb = -50.0
        assertFalse(squelch.update(-120.0))
    }

    @Test
    fun opensAtTheThresholdAndClosesThreeDecibelsBelow() {
        val squelch = Squelch()
        squelch.thresholdDb = -50.0
        assertFalse(squelch.update(-60.0))
        assertFalse(squelch.update(-50.1))
        assertTrue(squelch.update(-50.0))
        assertTrue(squelch.update(-52.9))
        assertFalse(squelch.update(-53.1))
        assertFalse(squelch.update(-51.0))
        assertEquals(-51.0, squelch.levelDb, 0.0)
    }

    @Test
    fun automaticThresholdSitsSixDecibelsAboveTheNoiseFloor() {
        val squelch = Squelch()
        squelch.auto = true
        assertTrue("open while the floor is unknown", squelch.update(-90.0))
        squelch.noiseFloorDb = -90.0
        assertEquals(-84.0, squelch.effectiveThresholdDb, 0.0)
        assertFalse(squelch.update(-89.0))
        assertFalse(squelch.update(-84.1))
        assertTrue(squelch.update(-84.0))
        assertTrue(squelch.update(-86.9))
        assertFalse(squelch.update(-87.1))
        squelch.thresholdDb = -100.0                 // ignored in automatic mode
        assertFalse(squelch.update(-89.0))
        squelch.auto = false
        assertTrue(squelch.update(-89.0))
    }

    @Test
    fun gateFadesInFiveMilliseconds() {
        val squelch = Squelch()
        squelch.thresholdDb = -50.0
        val rate = 48_000.0
        val audio = FloatArray(960) { 1f }
        squelch.update(-80.0)                            // closes
        squelch.gate(audio, 480, 2, rate)
        assertEquals(1f - 1f / 240, audio[0], 1e-4f)     // 5 ms = 240 frames
        assertEquals(audio[0], audio[1], 0f)
        assertEquals(0f, audio[2 * 240], 1e-4f)
        assertEquals(0f, audio[2 * 479], 0f)
        audio.fill(1f)
        squelch.gate(audio, 480, 2, rate)
        assertEquals("stays silent while closed", 0f, audio[100], 0f)
        audio.fill(1f)
        squelch.update(-40.0)                            // opens
        squelch.gate(audio, 480, 2, rate)
        assertEquals(1f / 240, audio[0], 1e-4f)
        assertEquals(1f, audio[2 * 240], 1e-4f)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.demod.SquelchTest`
Expected: the build fails with `unresolved reference 'Squelch'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/demod/NfmDemod.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.BiquadCascade
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Deemphasis
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Narrow-band FM demodulator (§6.4): discriminator scaled so that ±[deviationHz] is ±1.0, a 300 Hz
 * high-pass that removes CTCSS tones, a 3.5 kHz low-pass and optional de-emphasis. The de-emphasis
 * is the 6 dB/octave slope above 300 Hz that two-way radios pre-emphasise for, with its gain made
 * up so that a 1 kHz tone keeps its level.
 */
class NfmDemod(
    override val inputRate: Double,
    deviationHz: Double = 5_000.0,
    deemphasis: Boolean = true,
) : Demodulator {
    override val outputRate: Double = inputRate
    override val channels: Int = 1

    private val discriminator = FmDiscriminator(inputRate, deviationHz)
    private val highPass = BiquadCascade.highPass(1, 4, 300.0, inputRate)
    private val lowPass = BiquadCascade.lowPass(1, 4, 3_500.0, inputRate)
    private val deemphasisFilter = if (deemphasis) Deemphasis(1, 1 / (2 * PI * CORNER_HZ), inputRate) else null
    private val makeUp = sqrt(1 + (1_000.0 / CORNER_HZ) * (1_000.0 / CORNER_HZ)).toFloat()

    override fun maxOutput(inCount: Int): Int = inCount

    override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        discriminator.process(input, inCount, output)
        highPass.process(output, inCount)
        lowPass.process(output, inCount)
        if (deemphasisFilter != null) {
            deemphasisFilter.process(output, inCount)
            for (i in 0 until inCount) output[i] *= makeUp
        }
        return inCount
    }

    override fun reset() {
        discriminator.reset()
        highPass.reset()
        lowPass.reset()
        deemphasisFilter?.reset()
    }

    private companion object {
        const val CORNER_HZ = 300.0
    }
}
```

Create `core/src/main/kotlin/demod/AmDemod.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Agc
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlocker
import kotlin.math.sqrt

/** AM demodulator (§6.4): envelope detector, DC block and AGC. */
class AmDemod(override val inputRate: Double, agc: Boolean = true) : Demodulator {
    override val outputRate: Double = inputRate
    override val channels: Int = 1

    private val dcBlocker = DcBlocker(1, 30.0, inputRate)
    private val agc = if (agc) Agc(inputRate) else null

    override fun maxOutput(inCount: Int): Int = inCount

    override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        for (n in 0 until inCount) {
            val re = input[2 * n]
            val im = input[2 * n + 1]
            output[n] = sqrt(re * re + im * im)
        }
        dcBlocker.process(output, inCount)
        agc?.process(output, inCount)
        return inCount
    }

    override fun reset() {
        dcBlocker.reset()
        agc?.reset()
    }
}
```

Create `core/src/main/kotlin/demod/PassbandDemod.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Agc
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChain
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Nco
import kotlin.math.abs
import kotlin.math.floor

/**
 * Shared core of the SSB and CW demodulators (§6.4): the slice [centerHz] ± [halfWidthHz] of the
 * channel is moved to 0 Hz, isolated by a complex low-pass, moved back and its real part taken, so
 * every signal in the slice comes out at its own audio frequency and everything else is rejected.
 * The work is done at about 12 kHz to keep the steep filter short.
 */
open class PassbandDemod(
    final override val inputRate: Double,
    centerHz: Double,
    halfWidthHz: Double,
    transitionHz: Double,
    agc: Boolean,
) : Demodulator {
    private val decimation = maxOf(1, floor(inputRate / 12_000.0).toInt())
    final override val outputRate: Double = inputRate / decimation
    final override val channels: Int = 1

    private val chain = DecimatorChain.design(inputRate, decimation, abs(centerHz) + halfWidthHz + transitionHz)
    private val down = Nco(outputRate).apply { frequencyHz = -centerHz }
    private val up = Nco(outputRate).apply { frequencyHz = centerHz }
    private val filter = FirDecimator(FirDesign.lowPassKaiser(halfWidthHz, halfWidthHz + transitionHz, outputRate, 60.0), 1)
    private val agc = if (agc) Agc(outputRate) else null
    private var low = FloatArray(0)
    private var filtered = FloatArray(0)

    final override fun maxOutput(inCount: Int): Int = chain.maxOutput(inCount)

    final override fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        val need = 2 * chain.maxOutput(inCount)
        if (low.size < need) {
            low = FloatArray(need)
            filtered = FloatArray(need)
        }
        val n = chain.process(input, inCount, low)
        down.mix(low, 0, n)
        filter.process(low, n, filtered)
        up.mix(filtered, 0, n)
        for (i in 0 until n) output[i] = filtered[2 * i]
        agc?.process(output, n)
        return n
    }

    final override fun reset() {
        chain.reset()
        filter.reset()
        agc?.reset()
    }
}

enum class Sideband { USB, LSB }

/** Single-sideband demodulator for the 300–2,800 Hz voice passband above (USB) or below (LSB) the carrier. */
class SsbDemod(inputRate: Double, sideband: Sideband, agc: Boolean = true) :
    PassbandDemod(inputRate, if (sideband == Sideband.USB) 1_550.0 else -1_550.0, 1_250.0, 300.0, agc)

/**
 * CW demodulator: a carrier placed [pitchHz] above the channel centre is heard as a tone of that
 * pitch through a filter [bandwidthHz] wide. The receiver tunes the channel [pitchHz] below the carrier.
 */
class CwDemod(inputRate: Double, pitchHz: Double = 700.0, bandwidthHz: Double = 500.0, agc: Boolean = true) :
    PassbandDemod(inputRate, pitchHz, bandwidthHz / 2, maxOf(100.0, bandwidthHz / 4), agc)
```

Create `core/src/main/kotlin/demod/Squelch.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.demod

/**
 * Squelch on channel power (§6.4). The gate opens when the level reaches the threshold and closes
 * again [hysteresisDb] below it. In automatic mode the threshold is the noise floor plus 6 dB; the
 * engine supplies the floor ([noiseFloorDb]) from the spectrum around the channel, so a station
 * that never stops transmitting does not raise it. Audio is gated with 5 ms ramps. The same state
 * drives the scanner. Used from the DSP thread only.
 */
class Squelch(private val hysteresisDb: Double = 3.0) {
    /** Manual threshold in dBFS. Negative infinity (the default) keeps the squelch open. */
    var thresholdDb = Double.NEGATIVE_INFINITY

    /** When true the threshold follows [noiseFloorDb] and [thresholdDb] is ignored. */
    var auto = false

    /** Noise power expected in the empty channel, in dBFS; negative infinity while unknown. */
    var noiseFloorDb = Double.NEGATIVE_INFINITY

    /** True while the gate is open. */
    var open = true
        private set

    /** The last channel power given to [update], in dBFS. */
    var levelDb = Double.NEGATIVE_INFINITY
        private set

    private var gain = 1f

    /** The threshold in force right now, in dBFS. */
    val effectiveThresholdDb: Double
        get() = if (auto) noiseFloorDb + AUTO_MARGIN_DB else thresholdDb

    /** Takes the channel power of the newest block; returns whether the gate is open. */
    fun update(powerDb: Double): Boolean {
        levelDb = powerDb
        val threshold = effectiveThresholdDb
        if (open) {
            if (powerDb < threshold - hysteresisDb) open = false
        } else if (powerDb >= threshold) {
            open = true
        }
        return open
    }

    /** Fades [frames] interleaved frames of [audio] toward the gate state. */
    fun gate(audio: FloatArray, frames: Int, channels: Int, sampleRate: Double) {
        val target = if (open) 1f else 0f
        if (gain == target) {
            if (target == 0f) audio.fill(0f, 0, frames * channels)
            return
        }
        val step = (1.0 / (RAMP_SECONDS * sampleRate)).toFloat()
        var g = gain
        var i = 0
        for (f in 0 until frames) {
            if (g < target) g = minOf(target, g + step) else if (g > target) g = maxOf(target, g - step)
            for (c in 0 until channels) audio[i++] *= g
        }
        gain = g
    }

    private companion object {
        const val AUTO_MARGIN_DB = 6.0
        const val RAMP_SECONDS = 0.005
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.demod.NarrowDemodTest io.github.radhwanalhajjaj.spectrasdr.core.demod.SquelchTest`
Expected: `OK (15 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (175 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): NFM, AM, SSB and CW demodulators and squelch

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 9: Spectrum analyzer

Spectrum frames at the display rate (§6.2) and, from the same FFTs, the noise floor that the automatic squelch needs (decision 1). The floor is a percentile, and `java.util.Arrays.sort` may allocate on some inputs, so a small selection routine comes first.

**Files:**
- Create: `core/src/main/kotlin/dsp/Select.kt`, `core/src/main/kotlin/spectrum/SpectrumAnalyzer.kt`
- Test: `core/src/test/kotlin/dsp/SelectTest.kt`, `core/src/test/kotlin/spectrum/SpectrumAnalyzerTest.kt`

**Interfaces:**
- Consumes: `Fft(size).transform(data)`, `Windows.create(type, n)`, `Windows.sum(window)`, `WindowType` (Plan 1).
- Produces:
  - `object Select` with `fun kth(a: FloatArray, n: Int, k: Int): Float`: the value that would be at index `k` if the first `n` elements were sorted; reorders them; allocates nothing.
  - `class SpectrumFrame(val size: Int)` with `val levelsDb: FloatArray`, `val peakDb: FloatArray` (index 0 = −fs/2, index size/2 = the centre), `var centerHz: Long`, `var sampleRate: Int`, `var sequence: Long`.
  - `class SpectrumAnalyzer(val fftSize: Int = 4_096, windowType: WindowType = WindowType.BLACKMAN_HARRIS, framesPerSecond: Double = 30.0, maxFftsPerFrame: Int = 4, usableFraction: Double = 0.75)` with `var averaging: Double`, `var peakDecayDbPerSecond: Double`, `val noiseDensityDb: Double` (dBFS per hertz, −∞ before the first frame), `val fftsInLastFrame: Int`, `fun process(samples: FloatArray, count: Int, sampleRate: Int, centerHz: Long)`, `fun latest(dst: SpectrumFrame): Boolean` (any thread), `fun reset()`.
  - `object SpectrumDisplay` with `fun reduce(levels: FloatArray, firstBin: Double, lastBin: Double, out: FloatArray, columns: Int)`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/dsp/SelectTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class SelectTest {
    private fun check(values: FloatArray, n: Int = values.size) {
        val sorted = values.copyOf(n).also { it.sort() }
        for (k in 0 until n) {
            assertEquals("k = $k of $n", sorted[k], Select.kth(values.copyOf(), n, k), 0f)
        }
    }

    @Test
    fun matchesSortingOnRandomValues() {
        val rnd = Random(1)
        for (size in intArrayOf(1, 2, 3, 4, 7, 100, 513)) {
            check(FloatArray(size) { rnd.nextFloat() })
        }
        val large = FloatArray(20_000) { rnd.nextGaussian().toFloat() }
        val sorted = large.copyOf().also { it.sort() }
        assertEquals(sorted[4_000], Select.kth(large, large.size, 4_000), 0f)
    }

    @Test
    fun handlesSortedReversedAndRepeatedValues() {
        check(FloatArray(200) { it.toFloat() })
        check(FloatArray(200) { -it.toFloat() })
        check(FloatArray(200) { 3f })
        check(FloatArray(200) { (it % 4).toFloat() })
    }

    @Test
    fun looksOnlyAtTheFirstNValues() {
        val values = floatArrayOf(5f, 1f, 4f, -100f, -200f)
        assertEquals(4f, Select.kth(values, 3, 1), 0f)
        assertEquals(-100f, values[3], 0f)
        assertEquals(-200f, values[4], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnIndexOutsideTheRange() {
        Select.kth(floatArrayOf(1f, 2f), 2, 2)
    }
}
```

Create `core/src/test/kotlin/spectrum/SpectrumAnalyzerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.spectrum

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowType
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class SpectrumAnalyzerTest {
    // One 1,024-point frame per 1,024-sample block
    private val rate = 102_400
    private fun analyzer(window: WindowType = WindowType.BLACKMAN_HARRIS) =
        SpectrumAnalyzer(1_024, window, framesPerSecond = 100.0, maxFftsPerFrame = 1)

    private fun tone(bin: Int, amplitude: Double = 1.0) = Signals.complexTone(1_024, bin * 100.0, rate.toDouble(), amplitude)

    @Test
    fun fullScaleToneReadsZeroDbfsAtItsFrequency() {
        for (window in WindowType.values()) {
            val a = analyzer(window)
            val frame = SpectrumFrame(1_024)
            a.process(tone(128), 1_024, rate, 100_000_000L)
            assertTrue(a.latest(frame))
            assertEquals("$window", 0f, frame.levelsDb[512 + 128], 0.01f)
            assertTrue(frame.levelsDb[512 - 128] < -90f)
            assertEquals(100_000_000L, frame.centerHz)
            assertEquals(rate, frame.sampleRate)
            assertFalse("nothing new", a.latest(frame))
        }
    }

    @Test
    fun negativeFrequenciesSitBelowTheCentre() {
        val a = analyzer()
        val frame = SpectrumFrame(1_024)
        a.process(tone(-256, 0.1), 1_024, rate, 0L)
        a.latest(frame)
        assertEquals(-20f, frame.levelsDb[512 - 256], 0.01f)
        assertTrue(frame.levelsDb[512 + 256] < -90f)
    }

    @Test
    fun averagesLinearPower() {
        val a = analyzer()
        a.averaging = 0.5
        val frame = SpectrumFrame(1_024)
        repeat(3) { a.process(tone(100), 1_024, rate, 0L) }
        a.process(tone(100, 0.1), 1_024, rate, 0L)
        a.latest(frame)
        // 0.5·0.01 + 0.5·1 = 0.505 → −2.97 dB
        assertEquals(-2.97f, frame.levelsDb[612], 0.02f)
    }

    @Test
    fun peakHoldDecaysAtTheConfiguredRate() {
        val a = analyzer()
        a.averaging = 1.0
        a.peakDecayDbPerSecond = 20.0
        val frame = SpectrumFrame(1_024)
        a.process(tone(100), 1_024, rate, 0L)
        repeat(10) { a.process(tone(100, 0.001), 1_024, rate, 0L) }
        a.latest(frame)
        assertEquals(-60f, frame.levelsDb[612], 0.05f)
        assertEquals("ten frames of 10 ms at 20 dB/s", -2f, frame.peakDb[612], 0.05f)
    }

    @Test
    fun framesComeAtTheDisplayRateAndAverageSeveralFfts() {
        val a = SpectrumAnalyzer(4_096, framesPerSecond = 30.0)
        val frame = SpectrumFrame(4_096)
        val block = Signals.complexTone(16_384, 300_000.0, 2_400_000.0, 0.5)
        var frames = 0
        repeat(147) {                      // about one second at 2.4 MS/s
            a.process(block, 16_384, 2_400_000, 0L)
            if (a.latest(frame)) frames++
        }
        assertTrue("$frames frames", frames in 29..31)
        assertEquals(4, a.fftsInLastFrame)
        assertEquals(-6.02f, frame.levelsDb[2_048 + 512], 0.05f)
    }

    @Test
    fun resetForgetsHistory() {
        val a = analyzer()
        a.averaging = 0.1
        val frame = SpectrumFrame(1_024)
        repeat(3) { a.process(tone(100), 1_024, rate, 0L) }
        a.reset()
        a.process(tone(100, 0.01), 1_024, rate, 0L)
        a.latest(frame)
        assertEquals(-40f, frame.levelsDb[612], 0.05f)
        assertEquals(-40f, frame.peakDb[612], 0.05f)
    }

    @Test
    fun displayColumnsKeepTheLargestBin() {
        val levels = FloatArray(1_024) { -100f }
        levels[300] = -10f
        levels[301] = -30f
        val out = FloatArray(128)
        SpectrumDisplay.reduce(levels, 0.0, 1_024.0, out, 128)
        assertEquals(-10f, out[37], 0f)       // bins 296–303
        assertEquals(-100f, out[36], 0f)
        // Zoomed in to 16 bins across 64 columns: each bin fills four columns
        SpectrumDisplay.reduce(levels, 296.0, 312.0, out, 64)
        assertEquals(-100f, out[15], 0f)
        for (c in 16..19) assertEquals(-10f, out[c], 0f)
        for (c in 20..23) assertEquals(-30f, out[c], 0f)
    }

    @Test
    fun estimatesTheNoiseFloorWhileSignalsFillPartOfTheBand() {
        val fs = 2_400_000
        val a = SpectrumAnalyzer(4_096)
        assertEquals(Double.NEGATIVE_INFINITY, a.noiseDensityDb, 0.0)
        // 191 carriers on every 8th bin of the upper half, each 36 dB above the noise in its bin.
        // Their sum repeats every 512 samples.
        val carriers = FloatArray(2 * 512)
        for (t in 1..191) {
            for (k in 0 until 512) {
                val phase = 2 * PI * (8 * t) * k / 4_096
                carriers[2 * k] += (0.002 * cos(phase)).toFloat()
                carriers[2 * k + 1] += (0.002 * sin(phase)).toFloat()
            }
        }
        val sigma = 0.001
        val truth = 10 * log10(2 * sigma * sigma / fs)       // −120.8 dBFS per hertz
        val rnd = Random(3)
        val block = FloatArray(2 * 16_384)
        var first = Double.NaN
        repeat(150) {                                        // about one second
            for (i in block.indices) block[i] = (sigma * rnd.nextGaussian()).toFloat() + carriers[i % 1_024]
            a.process(block, 16_384, fs, 0L)
            if (first.isNaN() && a.noiseDensityDb > Double.NEGATIVE_INFINITY) first = a.noiseDensityDb
        }
        assertTrue("first estimate $first against $truth", first > truth - 3.5 && first < truth + 0.5)
        assertEquals(truth, a.noiseDensityDb, 1.0)
        a.reset()
        assertEquals(Double.NEGATIVE_INFINITY, a.noiseDensityDb, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSizesThatAreNotPowersOfTwo() {
        SpectrumAnalyzer(3_000)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzerTest`
Expected: the build fails with `unresolved reference 'SpectrumAnalyzer'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/dsp/Select.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

/** Order statistics without sorting and without allocating, for use in the signal path (§4.4). */
object Select {
    /**
     * The value that would be at index [k] if the first [n] elements of [a] were sorted. Those
     * elements are reordered. The cost is linear on average (quickselect, median-of-three pivot).
     */
    fun kth(a: FloatArray, n: Int, k: Int): Float {
        require(n <= a.size && k in 0 until n) { "k = $k is outside 0 until $n" }
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < a[lo]) swap(a, lo, mid)
            if (a[hi] < a[lo]) swap(a, lo, hi)
            if (a[hi] < a[mid]) swap(a, mid, hi)
            val pivot = a[mid]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    swap(a, i, j)
                    i++
                    j--
                }
            }
            // Everything up to j is at most the pivot, everything from i at least the pivot.
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }

    private fun swap(a: FloatArray, i: Int, j: Int) {
        val t = a[i]
        a[i] = a[j]
        a[j] = t
    }
}
```

Create `core/src/main/kotlin/spectrum/SpectrumAnalyzer.kt`:

```kotlin
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
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.SelectTest io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzerTest`
Expected: `OK (13 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (188 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): spectrum analyzer with averaging, peak hold and noise floor

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 10: The listening chain

One object that turns IQ blocks into sound for one mode at one sample rate: channelizer (§6.3), demodulator, squelch, audio output (§6.5). The receiver builds a new one whenever the mode, the bandwidth or the sample rate changes; the in-app benchmark (Plan 3) will run chains directly.

**Files:**
- Create: `core/src/main/kotlin/engine/AudioSink.kt`, `core/src/main/kotlin/engine/Mode.kt`, `core/src/main/kotlin/engine/Channelizer.kt`, `core/src/main/kotlin/engine/AudioOutput.kt`, `core/src/main/kotlin/engine/ReceiverChain.kt`
- Test: `core/src/test/kotlin/testutil/CollectingSink.kt`, `core/src/test/kotlin/testutil/TestStations.kt`, `core/src/test/kotlin/engine/AudioOutputTest.kt`, `core/src/test/kotlin/engine/ReceiverChainTest.kt`

**Interfaces:**
- Consumes: every demodulator and `Squelch` (Tasks 6 and 8); `RdsDecoder`, `RdsDemodulator` (Tasks 5 and 7); `SpectrumAnalyzer.noiseDensityDb` (Task 9, tests); `Nco`, `DecimatorChain.design`, `DecimatorChain.planStages(factor): List<Int>`, `FirDecimator`, `FirDesign`, `FractionalResampler`, `DriftController.update(fillLevel, dtSeconds)` (Plan 1).
- Produces:
  - `interface AudioSink { val sampleRate: Int; fun write(samples: FloatArray, frames: Int, channels: Int); fun fillLevel(): Float }` (§4.2).
  - `enum class Mode(val defaultBandwidthHz: Int, val bandwidthsHz: List<Int>, val minBandwidthHz: Int, val maxBandwidthHz: Int)` with `WFM`, `NFM`, `AM`, `USB`, `LSB`, `CW`.
  - `class Channelizer(inputRate: Double, decimation: Int, passbandHz: Double, stopbandHz: Double)` with `val outputRate: Double`, `val noiseBandwidthHz: Double`, `var offsetHz: Double`, `val powerDb: Double`, `fun maxOutput(inCount: Int): Int`, `fun process(input: FloatArray, inCount: Int, output: FloatArray): Int` (overwrites `input`), `fun reset()`; companion `forMode(inputRate: Double, mode: Mode, bandwidthHz: Int, cwPitchHz: Double = 700.0): Channelizer`, `wideDecimation(inputRate: Double): Int`, `narrowDecimation(inputRate: Double): Int`, `decimationFor(inputRate: Double, minRate: Double, maxRate: Double): Int`.
  - `class AudioOutput(sink: AudioSink, inputRate: Double, channels: Int)` with `var volume: Float`, `var muted: Boolean`, `fun write(audio: FloatArray, frames: Int)`.
  - `class ReceiverChain(val sampleRate: Int, val mode: Mode, val bandwidthHz: Int, sink: AudioSink, val deemphasisSeconds: Double = 50e-6, val cwPitchHz: Double = 700.0)` with `val rds: RdsDecoder?`, `val squelch: Squelch`, `val audio: AudioOutput`, `var offsetHz: Double`, `var noiseDensityDb: Double`, `val noiseFloorDb: Double`, `val channelPowerDb: Double`, `val stereo: Boolean`, `var stereoEnabled: Boolean`, `fun process(samples: FloatArray, count: Int)` (overwrites `samples`), `fun reset()`, `fun resetDecoders()`; companion `minimumSampleRate(mode: Mode): Int`, `supports(sampleRate: Int, mode: Mode): Boolean`.
  - Test helpers: `class CollectingSink(sampleRate: Int = 48_000) : AudioSink` with `val channels: Int`, `var fill: Float`, `val frames: Int`, `fun audio(): FloatArray`; `TestStations.fmBroadcast(rate: Double, offsetHz: Double, seconds: Double, leftHz: Double = 1_000.0, rightHz: Double = 0.0, rds: RdsEncoder? = null, amplitude: Float = 0.5f): FloatArray`.

- [ ] **Step 1: Write the test helpers and the failing tests**

Create `core/src/test/kotlin/testutil/CollectingSink.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioSink

/** An [AudioSink] that keeps everything it is given and reports a settable fill level. */
class CollectingSink(override val sampleRate: Int = 48_000) : AudioSink {
    private var data = FloatArray(1 shl 16)
    private var used = 0

    @Volatile
    var channels = 0
        private set

    @Volatile
    var fill = 0.5f

    /** Frames written so far. */
    val frames: Int
        @Synchronized get() = if (channels == 0) 0 else used / channels

    @Synchronized
    override fun write(samples: FloatArray, frames: Int, channels: Int) {
        this.channels = channels
        val n = frames * channels
        if (used + n > data.size) data = data.copyOf(maxOf(2 * data.size, used + n))
        System.arraycopy(samples, 0, data, used, n)
        used += n
    }

    override fun fillLevel(): Float = fill

    /** A copy of all interleaved audio written so far. */
    @Synchronized
    fun audio(): FloatArray = data.copyOf(used)
}
```

Create `core/src/test/kotlin/testutil/TestStations.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.MpxGenerator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsBaseband
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder

/** Wide-band test signals for the engine tests. */
object TestStations {
    /**
     * [seconds] of a stereo FM broadcast station [offsetHz] from the centre of a stream sampled at
     * [rate]: a tone of [leftHz] on the left, [rightHz] on the right (0 = silent), optional RDS.
     */
    fun fmBroadcast(
        rate: Double,
        offsetHz: Double,
        seconds: Double,
        leftHz: Double = 1_000.0,
        rightHz: Double = 0.0,
        rds: RdsEncoder? = null,
        amplitude: Float = 0.5f,
    ): FloatArray {
        val n = (seconds * rate).toInt()
        val mpx = MpxGenerator(rate, leftHz, rightHz, rds = rds?.let { RdsBaseband(it.bits, rate) })
        val modulator = FmModulator(rate, 75_000.0, offsetHz)
        val message = FloatArray(16_384)
        val block = FloatArray(2 * 16_384)
        val iq = FloatArray(2 * n)
        var done = 0
        while (done < n) {
            val m = minOf(16_384, n - done)
            mpx.generate(message, m)
            modulator.modulate(message, m, block, amplitude)
            System.arraycopy(block, 0, iq, 2 * done, 2 * m)
            done += m
        }
        return iq
    }
}
```

Create `core/src/test/kotlin/engine/AudioOutputTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputTest {
    private fun play(output: AudioOutput, audio: FloatArray, channels: Int = 1, block: Int = 1_024) {
        val chunk = FloatArray(block * channels)
        var done = 0
        val frames = audio.size / channels
        while (done < frames) {
            val n = minOf(block, frames - done)
            System.arraycopy(audio, done * channels, chunk, 0, n * channels)
            output.write(chunk, n)
            done += n
        }
    }

    @Test
    fun resamplesToTheSinkRate() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 12_000.0, 1)
        play(output, Signals.realTone(24_000, 1_000.0, 12_000.0, 0.5))
        assertEquals(96_000.0, sink.frames.toDouble(), 100.0)
        val audio = sink.audio()
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 0.5)
        assertEquals(0.5, Signals.toneAmplitude(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size), 0.005)
    }

    @Test
    fun volumeAndMuteRampWithoutSteps() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 48_000.0, 1)
        output.volume = 0.5f
        play(output, FloatArray(4_800) { 0.4f })
        output.muted = true
        play(output, FloatArray(4_800) { 0.4f })
        val audio = sink.audio()
        assertEquals(0.2f, audio[4_000], 1e-3f)
        assertEquals(0f, audio[audio.size - 1], 0f)
        // Once the constant input has settled, the only changes are the gain ramps: 0.4 / 480 per frame.
        var worstStep = 0f
        for (i in 2_400 until audio.size) worstStep = maxOf(worstStep, kotlin.math.abs(audio[i] - audio[i - 1]))
        assertTrue("largest step $worstStep", worstStep < 0.002f)
    }

    @Test
    fun softLimiterKeepsPeaksBelowFullScale() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 48_000.0, 1)
        play(output, Signals.realTone(9_600, 1_000.0, 48_000.0, 3.0))
        play(output, Signals.realTone(9_600, 1_000.0, 48_000.0, 0.5))
        val audio = sink.audio()
        val loud = Signals.peak(audio, 4_800, 9_600)
        assertTrue("limited peak $loud", loud > 0.9f && loud < 1.0f)
        assertEquals("quiet audio is untouched", 0.5, Signals.toneAmplitude(audio, 1_000.0, 48_000.0, 14_400, 19_200), 0.005)
    }

    @Test
    fun samplesThatAreNotNumbersBecomeSilence() {
        val sink = CollectingSink()
        val output = AudioOutput(sink, 48_000.0, 1)
        val audio = Signals.realTone(4_800, 1_000.0, 48_000.0, 0.5)
        audio[2_000] = Float.NaN
        play(output, audio)
        val heard = sink.audio()
        assertTrue(heard.all { it.isFinite() })
        assertEquals("the tone is back afterwards", 0.5f, Signals.peak(heard, heard.size - 480, heard.size), 0.01f)
    }

    @Test
    fun aFullSinkSlowsTheOutputAndAnEmptyOneSpeedsItUp() {
        fun framesFor(fill: Float): Int {
            val sink = CollectingSink()
            sink.fill = fill
            val output = AudioOutput(sink, 48_000.0, 2)
            play(output, FloatArray(2 * 480_000), channels = 2)
            return sink.frames
        }
        val nominal = framesFor(0.5f)
        assertEquals(480_000.0, nominal.toDouble(), 64.0)
        // The correction is limited to ±500 ppm: 240 frames in 10 s
        assertEquals(-240.0, (framesFor(1.0f) - nominal).toDouble(), 30.0)
        assertEquals(240.0, (framesFor(0.0f) - nominal).toDouble(), 30.0)
    }
}
```

Create `core/src/test/kotlin/engine/ReceiverChainTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.sim.FmModulator
import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzer
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.TestStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class ReceiverChainTest {
    private val rate = 960_000

    /** Feeds [iq] to [chain] in blocks of 16,384 samples, as a source would. */
    private fun feed(chain: ReceiverChain, iq: FloatArray) {
        val block = FloatArray(2 * 16_384)
        var done = 0
        val total = iq.size / 2
        while (done < total) {
            val n = minOf(16_384, total - done)
            System.arraycopy(iq, 2 * done, block, 0, 2 * n)
            chain.process(block, n)
            done += n
        }
    }

    private fun addNoise(iq: FloatArray, sigma: Double, seed: Long = 9) {
        val rnd = Random(seed)
        for (i in iq.indices) iq[i] += (sigma * rnd.nextGaussian()).toFloat()
    }

    @Test
    fun broadcastStationInStereoWithRds() {
        val encoder = RdsEncoder(0xD318, "SPECTRA ", "Chain test")
        val iq = TestStations.fmBroadcast(rate.toDouble(), 200_000.0, 4.0, 1_000.0, 0.0, encoder)
        addNoise(iq, 0.001)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.WFM, 200_000, sink)
        chain.offsetHz = 200_000.0
        feed(chain, iq)
        assertEquals(2, sink.channels)
        assertEquals(4 * 48_000.0, sink.frames.toDouble(), 100.0)
        assertTrue(chain.stereo)
        assertEquals(-6.0, chain.channelPowerDb, 0.5)
        val audio = sink.audio()
        val frames = sink.frames
        val left = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0))
        val right = 20 * log10(Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1))
        assertTrue("left $left dB, right $right dB", right <= left - 25.0)
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        assertTrue("SINAD $sinad dB", sinad >= 40.0)
        val rds = chain.rds
        assertNotNull(rds)
        assertEquals(0xD318, rds!!.state.pi)
        assertEquals("SPECTRA ", rds.state.ps)
        assertEquals("Chain test", rds.state.rt)
    }

    @Test
    fun narrowFmNextToAStrongNeighbour() {
        val n = 2 * rate
        val iq = FloatArray(2 * n)
        FmModulator(rate.toDouble(), 3_000.0, -150_000.0).modulate(Signals.realTone(n, 1_000.0, rate.toDouble()), n, iq, 0.05f)
        FmModulator(rate.toDouble(), 3_000.0, -125_000.0).modulate(Signals.realTone(n, 400.0, rate.toDouble()), n, iq, 0.5f, add = true)
        addNoise(iq, 0.0005)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.NFM, 12_500, sink)
        chain.offsetHz = -150_000.0
        feed(chain, iq)
        assertNull(chain.rds)
        assertFalse(chain.stereo)
        assertEquals(1, sink.channels)
        val audio = sink.audio()
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
        assertEquals(0.6, Signals.toneAmplitude(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size), 0.05)
    }

    @Test
    fun amStation() {
        val n = 2 * rate
        val iq = Signals.complexTone(n, 100_000.0, rate.toDouble(), 0.1)
        for (k in 0 until n) {
            val m = (1 + 0.5 * sin(2 * PI * 1_000.0 * k / rate)).toFloat()
            iq[2 * k] *= m
            iq[2 * k + 1] *= m
        }
        addNoise(iq, 0.0005)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.AM, 8_000, sink)
        chain.offsetHz = 100_000.0
        feed(chain, iq)
        val audio = sink.audio()
        val sinad = Signals.sinadDb(audio, 1_000.0, 48_000.0, audio.size / 2, audio.size)
        assertTrue("SINAD $sinad dB", sinad >= 30.0)
    }

    private fun toneThrough(mode: Mode, bandwidthHz: Int, toneOffsetHz: Double, tunedOffsetHz: Double): FloatArray {
        val n = 2 * rate
        val iq = Signals.complexTone(n, toneOffsetHz, rate.toDouble(), 0.05)
        addNoise(iq, 0.0002)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, mode, bandwidthHz, sink)
        chain.offsetHz = tunedOffsetHz
        feed(chain, iq)
        return sink.audio()
    }

    @Test
    fun sidebandsAndCwComeOutAtTheRightPitch() {
        var audio = toneThrough(Mode.USB, 2_500, 51_000.0, 50_000.0)
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 1.0)
        audio = toneThrough(Mode.LSB, 2_500, 48_500.0, 50_000.0)
        assertEquals(1_500.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 1.0)
        audio = toneThrough(Mode.CW, 500, 50_000.0, 50_000.0)
        assertEquals(700.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size / 2, audio.size), 1.0)
        assertEquals("AGC target", 0.5, Signals.peak(audio, audio.size / 2, audio.size).toDouble(), 0.06)
    }

    @Test
    fun closedSquelchGivesSilence() {
        val n = rate
        val iq = FloatArray(2 * n)
        FmModulator(rate.toDouble(), 3_000.0, 0.0).modulate(Signals.realTone(n, 1_000.0, rate.toDouble()), n, iq, 0.01f)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.NFM, 12_500, sink)
        chain.squelch.thresholdDb = -30.0          // the signal is at −40 dBFS
        feed(chain, iq)
        assertFalse(chain.squelch.open)
        assertEquals(-40.0, chain.channelPowerDb, 0.5)
        val audio = sink.audio()
        assertEquals(0f, Signals.peak(audio, audio.size / 2, audio.size), 0f)
    }

    @Test
    fun automaticSquelchUsesTheNoiseFloorOfTheStream() {
        val analyzer = SpectrumAnalyzer(4_096)
        val chain = ReceiverChain(rate, Mode.NFM, 12_500, CollectingSink())
        chain.squelch.auto = true
        chain.offsetHz = -150_000.0
        val rnd = Random(4)
        val block = FloatArray(2 * 16_384)
        val silence = FloatArray(16_384)
        fun run(blocks: Int, carrier: FmModulator?) = repeat(blocks) {
            for (i in block.indices) block[i] = (0.002 * rnd.nextGaussian()).toFloat()
            carrier?.modulate(silence, 16_384, block, 0.05f, add = true)
            analyzer.process(block, 16_384, rate, 0L)
            chain.noiseDensityDb = analyzer.noiseDensityDb
            chain.process(block, 16_384)
        }
        assertTrue("open until the floor is known", chain.squelch.open)
        run(30, null)
        // Noise of 0.002 per component is −51 dBFS over 960 kHz; the channel filter passes 14.1 kHz of it.
        assertEquals(-69.3, chain.noiseFloorDb, 1.0)
        assertEquals("an empty channel sits at its floor", chain.noiseFloorDb, chain.channelPowerDb, 2.0)
        assertFalse(chain.squelch.open)
        run(30, FmModulator(rate.toDouble(), 0.0, -150_000.0))   // a carrier at −26 dBFS that never stops
        assertTrue(chain.squelch.open)
        assertEquals("the carrier does not raise the floor", -69.3, chain.noiseFloorDb, 1.0)
        assertEquals(-26.0, chain.channelPowerDb, 0.2)
    }

    @Test
    fun automaticSquelchStaysOpenWhenTheChannelFillsTheStream() {
        val chain = ReceiverChain(240_000, Mode.WFM, 200_000, CollectingSink())
        chain.squelch.auto = true
        chain.noiseDensityDb = -100.0
        val iq = FloatArray(2 * 16_384)
        addNoise(iq, 0.001)
        chain.process(iq, 16_384)
        assertEquals(Double.NEGATIVE_INFINITY, chain.noiseFloorDb, 0.0)
        assertTrue(chain.squelch.open)
    }

    @Test
    fun everyModeRunsAtItsLowestSampleRate() {
        for (mode in Mode.values()) {
            val lowest = ReceiverChain.minimumSampleRate(mode)
            assertTrue(ReceiverChain.supports(lowest, mode))
            assertFalse(ReceiverChain.supports(lowest - 1, mode))
            val sink = CollectingSink()
            val chain = ReceiverChain(lowest, mode, mode.defaultBandwidthHz, sink)
            val iq = Signals.complexTone(lowest, 1_000.0, lowest.toDouble(), 0.1)   // one second
            val block = FloatArray(2 * 16_384)
            var done = 0
            while (done < lowest) {
                val n = minOf(16_384, lowest - done)
                System.arraycopy(iq, 2 * done, block, 0, 2 * n)
                chain.process(block, n)
                done += n
            }
            assertEquals("$mode", 48_000.0, sink.frames.toDouble(), 600.0)
            assertTrue("$mode", sink.audio().all { it.isFinite() })
        }
        try {
            ReceiverChain(100_000, Mode.WFM, 200_000, CollectingSink())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("120000"))
        }
    }

    @Test
    fun decimationFactorsKeepEveryStageSmall() {
        assertEquals(50, Channelizer.narrowDecimation(2_400_000.0))
        assertEquals(42, Channelizer.narrowDecimation(2_048_000.0))
        assertEquals(64, Channelizer.narrowDecimation(3_200_000.0))   // 66 would need a stage of 11
        assertEquals(20, Channelizer.narrowDecimation(1_000_000.0))
        assertEquals(5, Channelizer.narrowDecimation(250_000.0))
        assertEquals(2, Channelizer.narrowDecimation(96_000.0))
        assertEquals(1, Channelizer.narrowDecimation(48_000.0))
        assertEquals(10, Channelizer.wideDecimation(2_400_000.0))
        assertEquals(4, Channelizer.wideDecimation(960_000.0))
        assertEquals(12, Channelizer.wideDecimation(3_200_000.0))     // 13 is a prime above 8
        assertEquals(32, Channelizer.wideDecimation(8_000_000.0))     // 33 would need a stage of 11
        assertEquals(1, Channelizer.wideDecimation(250_000.0))
        assertEquals(1, Channelizer.wideDecimation(200_000.0))
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioOutputTest`
Expected: the build fails with `unresolved reference 'AudioSink'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/engine/AudioSink.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

/** Where the receiver's audio goes (§4.2): an AudioTrack on Android, a collector in tests. */
interface AudioSink {
    /** The rate the sink plays at, in Hz (48,000 on Android). */
    val sampleRate: Int

    /** Queues [frames] interleaved frames of [channels] channels. Must return within a few milliseconds. */
    fun write(samples: FloatArray, frames: Int, channels: Int)

    /** Buffer fill level from 0 (empty) to 1 (full); drives the drift compensation (§6.5). */
    fun fillLevel(): Float
}
```

Create `core/src/main/kotlin/engine/Mode.kt`:

```kotlin
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
```

Create `core/src/main/kotlin/engine/Channelizer.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChain
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimator
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesign
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.Nco
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

/**
 * Selects one channel from the IQ stream (§6.3): an NCO moves the wanted signal to 0 Hz, a
 * multistage decimator brings the rate down and a channel filter of ±[passbandHz] isolates it.
 * The channel power after the filter feeds the squelch and the S-meter.
 */
class Channelizer(inputRate: Double, decimation: Int, passbandHz: Double, stopbandHz: Double) {
    private val nco = Nco(inputRate)
    private val chain: DecimatorChain
    private val filter: FirDecimator
    private var scratch = FloatArray(0)

    /** Sample rate of the channel in Hz. */
    val outputRate: Double = inputRate / decimation

    /** Width of white noise that passes the channel filter, in Hz (both sides together). */
    val noiseBandwidthHz: Double

    init {
        // Leave room below Nyquist when the channel is as wide as the stream allows.
        val pass = minOf(passbandHz, 0.40 * outputRate)
        val stop = minOf(maxOf(stopbandHz, pass * 1.05), 0.49 * outputRate)
        chain = DecimatorChain.design(inputRate, decimation, pass)
        filter = FirDecimator(FirDesign.lowPassKaiser(pass, stop, outputRate, 60.0), 1)
        noiseBandwidthHz = pass + stop   // the filter is half way down midway between its two edges
    }

    /** Offset of the wanted signal from the centre of the IQ stream, in Hz. */
    var offsetHz = 0.0
        set(value) {
            field = value
            nco.frequencyHz = -value
        }

    /** Mean power of the last block of the channel in dBFS (a full-scale tone is 0). */
    var powerDb = Double.NEGATIVE_INFINITY
        private set

    fun maxOutput(inCount: Int): Int = chain.maxOutput(inCount)

    /** Channelizes [inCount] complex samples from [input], which is overwritten; returns the output count. */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        val need = 2 * chain.maxOutput(inCount)
        if (scratch.size < need) scratch = FloatArray(need)
        nco.mix(input, 0, inCount)
        val n = chain.process(input, inCount, scratch)
        filter.process(scratch, n, output)
        var sum = 0.0
        for (i in 0 until 2 * n) sum += output[i] * output[i]
        powerDb = if (n > 0) 10 * log10(sum / n + 1e-30) else Double.NEGATIVE_INFINITY
        return n
    }

    fun reset() {
        chain.reset()
        filter.reset()
    }

    companion object {
        /** The channelizer for [mode] with a channel [bandwidthHz] wide. */
        fun forMode(inputRate: Double, mode: Mode, bandwidthHz: Int, cwPitchHz: Double = 700.0): Channelizer = when (mode) {
            Mode.WFM -> Channelizer(inputRate, wideDecimation(inputRate), bandwidthHz / 2.0, bandwidthHz * 0.6)
            Mode.NFM, Mode.AM -> Channelizer(inputRate, narrowDecimation(inputRate), bandwidthHz / 2.0, bandwidthHz * 0.625)
            Mode.USB, Mode.LSB -> Channelizer(inputRate, narrowDecimation(inputRate), 3_200.0, 4_000.0)
            Mode.CW -> Channelizer(inputRate, narrowDecimation(inputRate), cwPitchHz + bandwidthHz, cwPitchHz + bandwidthHz + 800.0)
        }

        /** Broadcast FM runs at 240–480 kHz; a stream below 240 kHz is not decimated. */
        fun wideDecimation(inputRate: Double): Int = decimationFor(inputRate, 240_000.0, 480_000.0)

        /** Narrow modes run at 48–96 kHz. */
        fun narrowDecimation(inputRate: Double): Int = decimationFor(inputRate, 48_000.0, 96_000.0)

        /**
         * The decimation that brings [inputRate] into [minRate]..[maxRate]: the largest factor that
         * splits into stages of at most 8, so that no stage needs an oversized filter, or simply the
         * largest factor when none does. 1 when the stream is already below twice [minRate].
         */
        fun decimationFor(inputRate: Double, minRate: Double, maxRate: Double): Int {
            val largest = floor(inputRate / minRate).toInt()
            if (largest <= 1) return 1
            val smallest = maxOf(1, ceil(inputRate / maxRate).toInt())
            for (d in largest downTo smallest) {
                if (DecimatorChain.planStages(d).all { it <= 8 }) return d
            }
            return largest
        }
    }
}
```

Create `core/src/main/kotlin/engine/AudioOutput.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DriftController
import io.github.radhwanalhajjaj.spectrasdr.core.dsp.FractionalResampler
import kotlin.math.abs
import kotlin.math.tanh

/**
 * The last stage before the sink (§6.5): resampling to the sink's rate with drift correction that
 * holds the sink's buffer near half full, then volume, mute and a soft limiter. Used from the DSP
 * thread only.
 */
class AudioOutput(private val sink: AudioSink, private val inputRate: Double, private val channels: Int) {
    private val resampler = FractionalResampler(channels, inputRate, sink.sampleRate.toDouble())
    private val drift = DriftController()
    private val gainStep = (1.0 / (0.01 * sink.sampleRate)).toFloat()   // full swing in 10 ms
    private var gain = 0f
    private var out = FloatArray(0)

    /** Linear volume, 0–1. */
    var volume = 1f

    var muted = false

    /** Resamples [frames] interleaved frames of [audio] and hands them to the sink. */
    fun write(audio: FloatArray, frames: Int) {
        if (frames == 0) return
        resampler.correction = drift.update(sink.fillLevel().toDouble(), frames / inputRate)
        val need = channels * (resampler.maxOutput(frames) + 8)
        if (out.size < need) out = FloatArray(need)
        val n = resampler.process(audio, frames, out)
        val target = if (muted) 0f else volume.coerceIn(0f, 1f)
        var g = gain
        var i = 0
        for (f in 0 until n) {
            if (g < target) g = minOf(target, g + gainStep) else if (g > target) g = maxOf(target, g - gainStep)
            for (c in 0 until channels) {
                val x = out[i] * g
                out[i++] = if (abs(x) <= KNEE) x else limit(x)
            }
        }
        gain = g
        sink.write(out, n, channels)
    }

    private companion object {
        const val KNEE = 0.7f

        /** Soft limiter: unity gain up to the knee, then a smooth approach to ±1. */
        fun limit(x: Float): Float {
            if (x != x) return 0f   // not a number: silence instead of whatever a sink makes of it
            val over = (abs(x) - KNEE) / (1 - KNEE)
            val y = KNEE + (1 - KNEE) * tanh(over)
            return if (x < 0) -y else y
        }
    }
}
```

Create `core/src/main/kotlin/engine/ReceiverChain.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.demod.AmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.CwDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.Demodulator
import io.github.radhwanalhajjaj.spectrasdr.core.demod.NfmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.Sideband
import io.github.radhwanalhajjaj.spectrasdr.core.demod.Squelch
import io.github.radhwanalhajjaj.spectrasdr.core.demod.SsbDemod
import io.github.radhwanalhajjaj.spectrasdr.core.demod.WfmDemod
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsDecoder
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsDemodulator
import kotlin.math.log10

/**
 * The single-threaded listening path for one mode at one sample rate: channelizer, demodulator,
 * squelch gate and audio output. The [Receiver] builds a new chain whenever the mode, bandwidth
 * or sample rate changes; the in-app benchmark runs chains directly.
 */
class ReceiverChain(
    val sampleRate: Int,
    val mode: Mode,
    val bandwidthHz: Int,
    sink: AudioSink,
    val deemphasisSeconds: Double = 50e-6,
    val cwPitchHz: Double = 700.0,
) {
    init {
        require(supports(sampleRate, mode)) { "$mode needs at least ${minimumSampleRate(mode)} samples per second, got $sampleRate" }
    }

    private val channelizer = Channelizer.forMode(sampleRate.toDouble(), mode, bandwidthHz, cwPitchHz)
    private val channelRate = channelizer.outputRate

    /** The RDS decoder, present in WFM when the channel rate can carry the 57 kHz subcarrier. */
    val rds: RdsDecoder? = if (mode == Mode.WFM && channelRate >= 125_000.0) RdsDecoder() else null

    private val demod: Demodulator = when (mode) {
        Mode.WFM -> WfmDemod(channelRate, deemphasisSeconds).also { if (rds != null) it.tap = RdsDemodulator(channelRate, rds) }
        Mode.NFM -> NfmDemod(channelRate, deviationHz = minOf(5_000.0, 0.4 * bandwidthHz))
        Mode.AM -> AmDemod(channelRate)
        Mode.USB -> SsbDemod(channelRate, Sideband.USB)
        Mode.LSB -> SsbDemod(channelRate, Sideband.LSB)
        Mode.CW -> CwDemod(channelRate, cwPitchHz, bandwidthHz.toDouble())
    }

    val squelch = Squelch()
    val audio = AudioOutput(sink, demod.outputRate, demod.channels)

    // The spectrum cannot tell the floor when the channel takes up most of what it sees.
    private val floorKnown = channelizer.noiseBandwidthHz <= 0.5 * sampleRate
    private val noiseBandwidthDb = 10 * log10(channelizer.noiseBandwidthHz)
    private var channel = FloatArray(0)
    private var pcm = FloatArray(0)

    /**
     * Offset of the tuned frequency from the centre of the IQ stream, in Hz. In CW the channel is
     * placed one pitch below it, so the carrier is heard at the pitch.
     */
    var offsetHz = 0.0
        set(value) {
            field = value
            channelizer.offsetHz = if (mode == Mode.CW) value - cwPitchHz else value
        }

    /**
     * Noise floor of the IQ stream in dBFS per hertz, as the spectrum analyzer estimates it; the
     * automatic squelch needs it. Negative infinity (unknown) keeps the automatic squelch open.
     */
    var noiseDensityDb = Double.NEGATIVE_INFINITY

    /** Noise power expected in the empty channel in dBFS; negative infinity while unknown. */
    val noiseFloorDb: Double get() = if (floorKnown) noiseDensityDb + noiseBandwidthDb else Double.NEGATIVE_INFINITY

    init {
        offsetHz = 0.0
    }

    /** Channel power of the last block in dBFS. */
    val channelPowerDb: Double get() = channelizer.powerDb

    /** True while broadcast FM is decoded in stereo. */
    val stereo: Boolean get() = (demod as? WfmDemod)?.stereo ?: false

    /** False forces broadcast FM to mono. */
    var stereoEnabled = true
        set(value) {
            field = value
            (demod as? WfmDemod)?.stereoEnabled = value
        }

    /** Processes one block of [count] complex samples; [samples] is overwritten. */
    fun process(samples: FloatArray, count: Int) {
        val needChannel = 2 * channelizer.maxOutput(count)
        if (channel.size < needChannel) channel = FloatArray(needChannel)
        val n = channelizer.process(samples, count, channel)
        squelch.noiseFloorDb = noiseFloorDb
        squelch.update(channelizer.powerDb)
        val needPcm = demod.channels * demod.maxOutput(n)
        if (pcm.size < needPcm) pcm = FloatArray(needPcm)
        val frames = demod.process(channel, n, pcm)
        squelch.gate(pcm, frames, demod.channels, demod.outputRate)
        audio.write(pcm, frames)
    }

    /** Clears filter, loop and decoder state, for use after the tuner moved. */
    fun reset() {
        channelizer.reset()
        demod.reset()
        rds?.reset()
    }

    /** Forgets what was decoded from the station, for use after tuning to another one inside the stream. */
    fun resetDecoders() {
        rds?.reset()
    }

    companion object {
        /** The lowest sample rate [mode] can be demodulated from. */
        fun minimumSampleRate(mode: Mode): Int = if (mode == Mode.WFM) 120_000 else 48_000

        fun supports(sampleRate: Int, mode: Mode): Boolean = sampleRate >= minimumSampleRate(mode)
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioOutputTest io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverChainTest`
Expected: `OK (14 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (202 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): channelizer, audio output and the listening chain

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 11: The simulated band

Stations as a tuner would see them: FM broadcast with stereo and RDS, narrow FM bursts, AM, SSB and CW (§12). Rendering is a plain function of the block and the tuner frequency, without threads or clocks, so it is tested exactly; Task 13 wraps it into a source.

**Files:**
- Create: `core/src/main/kotlin/sim/SimBand.kt`
- Test: `core/src/test/kotlin/sim/SimBandTest.kt`

**Interfaces:**
- Consumes: the generators of Task 4; `ReceiverChain`, `CollectingSink` (Task 10, tests).
- Produces:
  - `sealed interface SimStation { val frequencyHz: Long; val level: Float }` with the data classes `FmBroadcast(frequencyHz: Long, name: String, text: String = "", pi: Int = 0x1000, leftHz: Double = 1_000.0, rightHz: Double = 400.0, level: Float = 0.2f)`, `NarrowFm(frequencyHz: Long, toneHz: Double = 1_000.0, deviationHz: Double = 3_000.0, burstSeconds: Double = 2.0, pauseSeconds: Double = 2.0, level: Float = 0.05f)`, `Am(frequencyHz: Long, toneHz: Double = 800.0, depth: Double = 0.5, level: Float = 0.05f)`, `Ssb(frequencyHz: Long, upper: Boolean = true, tone1Hz: Double = 700.0, tone2Hz: Double = 1_900.0, level: Float = 0.03f)`, `Cw(frequencyHz: Long, dotSeconds: Double = 0.1, level: Float = 0.03f)`.
  - `class SimBand(stations: List<SimStation>, sampleRate: Int, maxBlock: Int = 16_384)` with `fun render(out: FloatArray, count: Int, centerHz: Long)`, which **adds** the stations to `out`; companion `fun defaultStations(): List<SimStation>`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/sim/SimBandTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.sim

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverChain
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimBandTest {
    private val center = 100_000_000L

    /** [seconds] of [band] around [centerHz], rendered in blocks as the simulator does. */
    private fun render(band: SimBand, rate: Int, seconds: Double, centerHz: Long = center): FloatArray {
        val total = (seconds * rate).toInt()
        val iq = FloatArray(2 * total)
        val block = FloatArray(2 * 16_384)
        var done = 0
        while (done < total) {
            val n = minOf(16_384, total - done)
            block.fill(0f, 0, 2 * n)
            band.render(block, n, centerHz)
            System.arraycopy(block, 0, iq, 2 * done, 2 * n)
            done += n
        }
        return iq
    }

    @Test
    fun stationsAppearAtTheirOffsetsWithTheirLevels() {
        val rate = 960_000
        val band = SimBand(
            listOf(SimStation.Am(100_100_000, depth = 0.0, level = 0.1f), SimStation.Am(99_800_000, depth = 0.0, level = 0.05f)),
            rate,
        )
        val iq = render(band, rate, 0.1)
        assertEquals(-20.0, Signals.complexToneDb(iq, 100_000.0, rate.toDouble()), 0.05)
        assertEquals(-26.0, Signals.complexToneDb(iq, -200_000.0, rate.toDouble()), 0.05)
        val moved = render(band, rate, 0.1, centerHz = 100_050_000)
        assertEquals(-20.0, Signals.complexToneDb(moved, 50_000.0, rate.toDouble()), 0.05)
    }

    @Test
    fun aStationThatDoesNotFitIntoTheStreamIsLeftOut() {
        val rate = 960_000
        val station = SimStation.FmBroadcast(100_390_000, "EDGE FM ")
        // 390 + 100 kHz would pass the edge of the stream at 480 kHz; from 10 kHz higher it fits
        assertEquals(0f, Signals.peak(render(SimBand(listOf(station), rate), rate, 0.05)), 0f)
        val inside = render(SimBand(listOf(station), rate), rate, 0.05, centerHz = 100_010_000)
        assertEquals(-14.0, Signals.complexPowerDb(inside), 0.1)
    }

    @Test
    fun broadcastStationCarriesStereoAudioAndItsName() {
        val rate = 960_000
        val band = SimBand(listOf(SimStation.FmBroadcast(100_200_000, "SIM FM  ", "Simulated text", 0xD3B2, 1_000.0, 0.0)), rate)
        val sink = CollectingSink()
        val chain = ReceiverChain(rate, Mode.WFM, 200_000, sink)
        chain.offsetHz = 200_000.0
        val block = FloatArray(2 * 16_384)
        repeat(4 * rate / 16_384) {
            block.fill(0f)
            band.render(block, 16_384, center)
            chain.process(block, 16_384)
        }
        assertTrue(chain.stereo)
        assertEquals(0xD3B2, chain.rds!!.state.pi)
        assertEquals("SIM FM  ", chain.rds!!.state.ps)
        assertEquals("Simulated text", chain.rds!!.state.rt)
        val audio = sink.audio()
        val frames = sink.frames
        val left = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        val right = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1)
        assertTrue("left $left, right $right", left > 0.3 && right < left / 10)
    }

    @Test
    fun narrowFmComesInBurstsThatKeepTimeWhileOutOfView() {
        val rate = 240_000
        val band = SimBand(listOf(SimStation.NarrowFm(100_050_000, burstSeconds = 0.05, pauseSeconds = 0.05, level = 0.1f)), rate)
        val iq = render(band, rate, 0.1)
        assertEquals("transmitting", -20.0, Signals.complexPowerDb(iq, 0, 12_000), 0.1)
        assertEquals("pausing", 0f, Signals.peak(iq, 2 * 12_000, 2 * 24_000), 0f)
        // 50 ms tuned elsewhere is the next burst, missed; back in view the station pauses again
        render(band, rate, 0.05, centerHz = 200_000_000)
        val back = render(band, rate, 0.1)
        assertEquals(0f, Signals.peak(back, 0, 2 * 12_000), 0f)
        assertEquals(-20.0, Signals.complexPowerDb(back, 12_000, 24_000), 0.1)
    }

    @Test
    fun amCarrierIsModulatedToItsDepth() {
        val rate = 240_000
        val iq = render(SimBand(listOf(SimStation.Am(100_020_000, toneHz = 1_000.0, depth = 0.5, level = 0.1f)), rate), rate, 0.5)
        assertEquals(-20.0, Signals.complexToneDb(iq, 20_000.0, rate.toDouble()), 0.05)
        // Each sideband is depth / 2 of the carrier: −12 dB
        assertEquals(-32.0, Signals.complexToneDb(iq, 21_000.0, rate.toDouble()), 0.1)
        assertEquals(-32.0, Signals.complexToneDb(iq, 19_000.0, rate.toDouble()), 0.1)
    }

    @Test
    fun sidebandTonesSitOnTheirSideOfTheCarrier() {
        val rate = 240_000
        val stations = listOf(SimStation.Ssb(100_030_000, upper = true, level = 0.1f), SimStation.Ssb(99_970_000, upper = false, level = 0.1f))
        val iq = render(SimBand(stations, rate), rate, 0.5)
        val fs = rate.toDouble()
        assertEquals(-26.0, Signals.complexToneDb(iq, 30_700.0, fs), 0.1)
        assertEquals(-26.0, Signals.complexToneDb(iq, 31_900.0, fs), 0.1)
        assertTrue(Signals.complexToneDb(iq, 29_300.0, fs) < -80.0)
        assertEquals(-26.0, Signals.complexToneDb(iq, -30_700.0, fs), 0.1)
        assertEquals(-26.0, Signals.complexToneDb(iq, -31_900.0, fs), 0.1)
        assertTrue(Signals.complexToneDb(iq, -29_300.0, fs) < -80.0)
    }

    @Test
    fun cwIsKeyedInDots() {
        val rate = 240_000
        val iq = render(SimBand(listOf(SimStation.Cw(100_010_000, dotSeconds = 0.05, level = 0.1f)), rate), rate, 0.2)
        // Dots of 12,000 samples: on, off, on, off; the key takes 5 ms (1,200 samples) to rise and fall
        assertEquals(-20.0, Signals.complexPowerDb(iq, 2_000, 12_000), 0.05)
        assertEquals(0f, Signals.peak(iq, 2 * 14_000, 2 * 24_000), 0f)
        assertEquals(-20.0, Signals.complexPowerDb(iq, 26_000, 36_000), 0.05)
        assertTrue("the key is shaped", Signals.complexPowerDb(iq, 0, 600) < -24.0)
    }

    @Test
    fun theDefaultBandHasEveryKindOfSignal() {
        val kinds = SimBand.defaultStations().map { it::class }.toSet()
        assertEquals(
            setOf(SimStation.FmBroadcast::class, SimStation.NarrowFm::class, SimStation.Am::class, SimStation.Ssb::class, SimStation.Cw::class),
            kinds,
        )
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBandTest`
Expected: the build fails with `unresolved reference 'SimBand'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/sim/SimBand.kt`:

```kotlin
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
```

- [ ] **Step 4: Run it and watch it pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBandTest`
Expected: `OK (8 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (210 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): simulated band of FM, NFM, AM, SSB and CW stations

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 12: The receiver

The orchestrator of §4.3: the queue between the source's thread and the DSP thread, the settings, the tuning policy (decision 6), status and RDS reporting.

How it works, for the reviewer:

- **Threads.** The source's thread only puts blocks into the queue. The DSP thread does everything else, including every call to the listener. `update`, `spectrum`, `settings`, `status`, `start` and `stop` may be called from any thread.
- **Settings.** `update` swaps an immutable `ReceiverSettings`. The DSP thread notices the new object before the next block and applies it: it retunes the source if needed, rebuilds the chain if the mode, bandwidth, de-emphasis, pitch or the blocks' sample rate changed, and sets the chain's controls.
- **Tuning.** `TuningPolicy.plan` says whether the tuner must move. If it must, the receiver calls `setCenterFrequency`, keeps the returned epoch and discards blocks with an older `tuneEpoch` (§7.1). The first block that is new enough resets the chain and the spectrum. A source that cannot tune is never asked; the channel is kept inside its band.
- **The block decides.** Frequency offset, sample rate and chain are taken from what each block says about itself, not from what was asked of the source, so a source that ignores a request is still received correctly.

**Files:**
- Create: `core/src/main/kotlin/engine/TuningPolicy.kt`, `core/src/main/kotlin/engine/ReceiverSettings.kt`, `core/src/main/kotlin/engine/Receiver.kt`
- Test: `core/src/test/kotlin/testutil/ScriptedSource.kt`, `core/src/test/kotlin/engine/TuningPolicyTest.kt`, `core/src/test/kotlin/engine/ReceiverTest.kt`

**Interfaces:**
- Consumes: `ReceiverChain`, `Mode`, `AudioSink` (Task 10); `SpectrumAnalyzer`, `SpectrumFrame` (Task 9); `RdsState` (Task 5); `BlockQueue.capacity`, `IqSource.setSampleRate(sps): Int` (Task 2); `DcBlocker.reset` (Task 1); `IqSource`, `SourceListener`, `SourceInfo`, `SourceKind`, `SourceError`, `IqBlock`, `BlockPool` (Plan 1); `SimBand`, `SimStation` (Task 11, tests).
- Produces:
  - `object TuningPolicy` with `CENTER_GUARD_HZ = 1_000.0`, `fun usableFraction(kind: SourceKind): Double`, `fun halfWidthHz(mode: Mode, bandwidthHz: Int, cwPitchHz: Double): Double`, `fun fits(offsetHz: Double, sampleRate: Int, mode: Mode, halfWidthHz: Double, usableFraction: Double): Boolean`, `fun centerFor(frequencyHz: Long, sampleRate: Int, mode: Mode): Long`, `fun plan(frequencyHz: Long, currentCenterHz: Long?, sampleRate: Int, mode: Mode, bandwidthHz: Int, cwPitchHz: Double, usableFraction: Double): Long`, `fun clampOffset(offsetHz: Double, sampleRate: Int, halfWidthHz: Double): Double`.
  - `data class ReceiverSettings(val frequencyHz: Long, val sampleRate: Int, val mode: Mode = Mode.WFM, val bandwidthHz: Int = mode.defaultBandwidthHz, val squelchDb: Double = Double.NEGATIVE_INFINITY, val autoSquelch: Boolean = false, val volume: Float = 1f, val muted: Boolean = false, val stereo: Boolean = true, val deemphasisSeconds: Double = 50e-6, val cwPitchHz: Double = 700.0, val dcRemoval: Boolean? = null, val fftSize: Int = 4_096, val window: WindowType = WindowType.BLACKMAN_HARRIS, val framesPerSecond: Int = 30, val averaging: Double = 0.5, val peakDecayDbPerSecond: Double = 20.0)`, validated in `init`, with `fun withMode(mode: Mode): ReceiverSettings`.
  - `data class ReceiverStatus(val centerHz: Long = 0, val sampleRate: Int = 0, val frequencyHz: Long = 0, val tuned: Boolean = false, val demodulating: Boolean = false, val channelPowerDb: Double = −∞, val noiseFloorDb: Double = −∞, val squelchOpen: Boolean = false, val stereo: Boolean = false, val rdsSynced: Boolean = false, val blocks: Long = 0, val staleBlocks: Long = 0, val overruns: Long = 0, val load: Double = 0.0)`.
  - `interface ReceiverListener` with default-empty `onStatus(status: ReceiverStatus)`, `onRds(state: RdsState)`, `onWarning(message: String)`, `onSourceError(error: SourceError)`, `onStopped()`.
  - `class Receiver(source: IqSource, sink: AudioSink, initial: ReceiverSettings, listener: ReceiverListener)` with `val settings: ReceiverSettings`, `val status: ReceiverStatus`, `val fftSize: Int`, `fun update(change: (ReceiverSettings) -> ReceiverSettings)`, `fun spectrum(dst: SpectrumFrame): Boolean`, `fun start()`, `fun stop()`; companion `fun queueCapacity(sampleRate: Int, blockSize: Int): Int`, `fun defaultDcRemoval(kind: SourceKind): Boolean`.
  - Test helper `class ScriptedSource(stations: List<SimStation> = emptyList(), initialCenterHz: Long = 100_000_000, initialSampleRate: Int = 240_000, canTune: Boolean = true, canSetRate: Boolean = true, pipelineBlocks: Int = 0, maxFrequencyHz: Long = Long.MAX_VALUE, kind: SourceKind = SourceKind.SIMULATOR, blockSize: Int = 4_096, noiseLevel: Float = 0.0005f, dcOffset: Float = 0f) : IqSource` with `val calls: List<String>` and `fun fail(error: SourceError)`. It hands over one block at a time and waits for its release, so block counts in the tests are exact.

- [ ] **Step 1: Write the test source and the failing tests**

Create `core/src/test/kotlin/testutil/ScriptedSource.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBand
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockPool
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A source for receiver tests. It hands over one block at a time and waits until that block has
 * been released, so the receiver sees every block and counts are exact. Retunes and rate changes
 * requested while it streams take effect [pipelineBlocks] blocks later, and those blocks still
 * carry the old epoch, frequency and rate, like samples waiting in a driver's buffers.
 */
class ScriptedSource(
    private val stations: List<SimStation> = emptyList(),
    initialCenterHz: Long = 100_000_000,
    initialSampleRate: Int = 240_000,
    private val canTune: Boolean = true,
    private val canSetRate: Boolean = true,
    private val pipelineBlocks: Int = 0,
    /** Frequencies above this are refused with an IllegalArgumentException. */
    private val maxFrequencyHz: Long = Long.MAX_VALUE,
    kind: SourceKind = SourceKind.SIMULATOR,
    private val blockSize: Int = 4_096,
    private val noiseLevel: Float = 0.0005f,
    /** Added to the in-phase samples, like a tuner's DC offset. */
    private val dcOffset: Float = 0f,
) : IqSource {
    override val info = SourceInfo("Scripted", kind, SampleFormat.S16LE, canTune, listOf(initialSampleRate), GainModel.None, emptySet())

    /** Everything asked of the source, in order: "rate 240000", "freq 100000000", "open", "close". */
    val calls = CopyOnWriteArrayList<String>()

    private class Change(val atBlock: Long, val centerHz: Long, val rate: Int, val epoch: Int)

    private val lock = Any()
    private val pending = ArrayDeque<Change>()
    private var requestedCenterHz = initialCenterHz
    private var requestedRate = initialSampleRate
    private var requestedEpoch = 0
    private var emitted = 0L

    @Volatile private var running = false
    @Volatile private var failure: SourceError? = null
    private var thread: Thread? = null

    override val centerFrequencyHz: Long get() = synchronized(lock) { requestedCenterHz }

    override fun open(listener: SourceListener) {
        calls += "open"
        check(thread == null) { "already open" }
        running = true
        thread = Thread({ run(listener) }, "scripted-source").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        calls += "close"
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    }

    override fun setCenterFrequency(hz: Long): Int {
        calls += "freq $hz"
        require(hz in 0..maxFrequencyHz) { "Frequency $hz Hz is outside 0..$maxFrequencyHz Hz" }
        return synchronized(lock) {
            if (!canTune) return requestedEpoch
            requestedCenterHz = hz
            change()
        }
    }

    override fun setSampleRate(sps: Int): Int {
        calls += "rate $sps"
        return synchronized(lock) {
            if (!canSetRate) return requestedEpoch
            requestedRate = sps
            change()
        }
    }

    private fun change(): Int {
        requestedEpoch++
        pending.addLast(Change(if (running) emitted + pipelineBlocks else 0, requestedCenterHz, requestedRate, requestedEpoch))
        return requestedEpoch
    }

    override fun setGain(gain: GainSetting) {}

    override fun setPpm(ppm: Int) {}

    override fun setOption(option: SourceOption) {}

    /** Reports [error] and ends the stream, as a lost connection would. */
    fun fail(error: SourceError) {
        failure = error
    }

    private fun run(listener: SourceListener) {
        val pool = BlockPool(blockSize, preallocate = 1)
        val random = Random(5)
        var centerHz = 0L
        var rate = 0
        var epoch = 0
        var band: SimBand? = null
        var seq = 0L
        synchronized(lock) {
            centerHz = requestedCenterHz
            rate = requestedRate
            epoch = requestedEpoch
            pending.clear()   // everything asked before the stream started is already in force
        }
        try {
            listener.onStarted(info)
            while (running) {
                val error = failure
                if (error != null) {
                    listener.onError(error)
                    break
                }
                synchronized(lock) {
                    while (pending.isNotEmpty() && pending.first().atBlock <= emitted) {
                        val c = pending.removeFirst()
                        centerHz = c.centerHz
                        epoch = c.epoch
                        if (c.rate != rate) {
                            rate = c.rate
                            band = null
                        }
                    }
                    emitted++
                }
                val renderer = band ?: SimBand(stations, rate, blockSize).also { band = it }
                val block = pool.obtain()
                val out = block.samples
                for (n in 0 until blockSize) {
                    out[2 * n] = noiseLevel * random.nextGaussian().toFloat() + dcOffset
                    out[2 * n + 1] = noiseLevel * random.nextGaussian().toFloat()
                }
                renderer.render(out, blockSize, centerHz)
                block.length = blockSize
                block.seq = seq++
                block.centerHz = centerHz
                block.sampleRate = rate
                block.tuneEpoch = epoch
                listener.onBlock(block)
                while (running && pool.freeCount < pool.allocated) Thread.sleep(0, 100_000)   // until it is released
            }
        } finally {
            running = false
            listener.onStopped()
        }
    }
}
```

Create `core/src/test/kotlin/engine/TuningPolicyTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningPolicyTest {
    private val rate = 2_400_000   // with an RTL-SDR the usable band reaches ±900 kHz

    private fun plan(frequencyHz: Long, currentCenterHz: Long?, mode: Mode = Mode.WFM, bandwidthHz: Int = mode.defaultBandwidthHz) =
        TuningPolicy.plan(frequencyHz, currentCenterHz, rate, mode, bandwidthHz, 700.0, 0.75)

    @Test
    fun broadcastFmIsCentredOnTheStation() {
        assertEquals(100_000_000L, TuningPolicy.centerFor(100_000_000, rate, Mode.WFM))
        assertEquals(100_000_000L, plan(100_000_000, null))
    }

    @Test
    fun narrowModesSitAQuarterOfTheRateAboveTheCentre() {
        assertEquals(144_900_000L, TuningPolicy.centerFor(145_500_000, rate, Mode.NFM))
        assertEquals(144_900_000L, plan(145_500_000, null, Mode.NFM))
        assertEquals("never below 0 Hz", 0L, TuningPolicy.centerFor(100_000, rate, Mode.AM))
    }

    @Test
    fun theTunerStaysWhileTheChannelIsInsideTheUsableBand() {
        assertEquals(100_000_000L, plan(100_700_000, 100_000_000))
        assertEquals("700 + 100 kHz reaches the edge exactly", 100_000_000L, plan(100_800_000, 100_000_000))
        assertEquals(100_800_001L, plan(100_800_001, 100_000_000))
        assertEquals(99_100_000L, plan(99_100_000, 100_000_000))
    }

    @Test
    fun narrowChannelsKeepClearOfTheCentre() {
        // 12.5 kHz NFM reaches 6.25 kHz to either side and stays 1 kHz away from the centre
        assertTrue(TuningPolicy.fits(7_250.0, rate, Mode.NFM, 6_250.0, 0.75))
        assertFalse(TuningPolicy.fits(7_249.0, rate, Mode.NFM, 6_250.0, 0.75))
        assertFalse(TuningPolicy.fits(-5_000.0, rate, Mode.NFM, 6_250.0, 0.75))
        assertTrue("broadcast FM may sit on the centre", TuningPolicy.fits(0.0, rate, Mode.WFM, 100_000.0, 0.75))
        assertEquals(99_405_000L, plan(100_005_000, 100_000_000, Mode.NFM))
        assertEquals(100_000_000L, plan(100_300_000, 100_000_000, Mode.NFM))
        assertEquals("switching to AM on a centred station moves the tuner", 99_400_000L, plan(100_000_000, 100_000_000, Mode.AM))
    }

    @Test
    fun aWideChannelInANarrowStreamIsSimplyCentred() {
        // 200 kHz does not fit into the usable part of 240 kHz at all
        assertFalse(TuningPolicy.fits(0.0, 240_000, Mode.WFM, 100_000.0, 0.75))
        assertEquals(100_000_000L, TuningPolicy.plan(100_000_000, 100_000_000, 240_000, Mode.WFM, 200_000, 700.0, 0.75))
        assertEquals(100_010_000L, TuningPolicy.plan(100_010_000, 100_000_000, 240_000, Mode.WFM, 200_000, 700.0, 0.75))
    }

    @Test
    fun usableFractionAndChannelWidthsFollowTheSourceAndTheMode() {
        assertEquals(0.85, TuningPolicy.usableFraction(SourceKind.RSP_TCP), 0.0)
        assertEquals(0.75, TuningPolicy.usableFraction(SourceKind.RTL_TCP), 0.0)
        assertEquals(0.75, TuningPolicy.usableFraction(SourceKind.SIMULATOR), 0.0)
        assertEquals(100_000.0, TuningPolicy.halfWidthHz(Mode.WFM, 200_000, 700.0), 0.0)
        assertEquals(4_000.0, TuningPolicy.halfWidthHz(Mode.AM, 8_000, 700.0), 0.0)
        assertEquals(3_200.0, TuningPolicy.halfWidthHz(Mode.LSB, 2_500, 700.0), 0.0)
        assertEquals(1_200.0, TuningPolicy.halfWidthHz(Mode.CW, 500, 700.0), 0.0)
    }

    @Test
    fun aRecordingIsLimitedToItsOwnBand() {
        assertEquals(10_000.0, TuningPolicy.clampOffset(10_000.0, 240_000, 6_250.0), 0.0)
        assertEquals(113_750.0, TuningPolicy.clampOffset(500_000.0, 240_000, 6_250.0), 0.0)
        assertEquals(-113_750.0, TuningPolicy.clampOffset(-500_000.0, 240_000, 6_250.0), 0.0)
        assertEquals("a channel wider than the stream stays in the middle", 0.0, TuningPolicy.clampOffset(50_000.0, 240_000, 150_000.0), 0.0)
    }
}
```

Create `core/src/test/kotlin/engine/ReceiverTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.ScriptedSource
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class ReceiverTest {
    private class Events : ReceiverListener {
        val statuses = CopyOnWriteArrayList<ReceiverStatus>()
        val rds = CopyOnWriteArrayList<RdsState>()
        val warnings = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<SourceError>()
        val order = CopyOnWriteArrayList<String>()
        val stopped = CountDownLatch(1)
        val stops = AtomicInteger()

        override fun onStatus(status: ReceiverStatus) {
            statuses += status
        }

        override fun onRds(state: RdsState) {
            rds += state
        }

        override fun onWarning(message: String) {
            warnings += message
        }

        override fun onSourceError(error: SourceError) {
            errors += error
            order += "error"
        }

        override fun onStopped() {
            order += "stopped"
            stops.incrementAndGet()
            stopped.countDown()
        }
    }

    private var receiver: Receiver? = null

    @After
    fun stopReceiver() {
        receiver?.stop()
    }

    private fun start(source: ScriptedSource, settings: ReceiverSettings, sink: CollectingSink = CollectingSink()): Pair<Receiver, Events> {
        val events = Events()
        val r = Receiver(source, sink, settings, events)
        receiver = r
        r.start()
        return r to events
    }

    /** Waits until [condition] holds; fails with [what] after 20 s. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    private val station = SimStation.FmBroadcast(100_000_000, "TEST FM ", "Receiver test", 0xD3A1, 1_000.0, 0.0)

    @Test
    fun tunesTheSourceBeforeOpeningItAndPlaysTheStation() {
        val source = ScriptedSource(listOf(station))
        val sink = CollectingSink()
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000), sink)
        await("the station name") { events.rds.lastOrNull()?.ps == "TEST FM " }
        assertEquals(listOf("rate 240000", "freq 100000000", "open"), source.calls.take(3))
        await("a full status") { r.status.rdsSynced && r.status.stereo }
        val status = r.status
        assertTrue(status.tuned && status.demodulating && status.squelchOpen)
        assertEquals(100_000_000L, status.centerHz)
        assertEquals(100_000_000L, status.frequencyHz)
        assertEquals(240_000, status.sampleRate)
        assertEquals("a station at 0.2 of full scale", -14.0, status.channelPowerDb, 1.5)
        assertEquals(0L, status.staleBlocks)
        assertEquals(0L, status.overruns)
        assertTrue("load ${status.load}", status.load > 0.0)
        assertEquals(0xD3A1, events.rds.last().pi)
        r.stop()
        assertEquals(2, sink.channels)
        val audio = sink.audio()
        val frames = sink.frames
        val left = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 0)
        val right = Signals.toneAmplitude(audio, 1_000.0, 48_000.0, frames / 2, frames, 2, 1)
        assertTrue("left $left, right $right", left > 0.3 && right < left / 10)
    }

    @Test
    fun aFrequencyInsideTheStreamOnlyMovesTheChannel() {
        val source = ScriptedSource(
            listOf(SimStation.NarrowFm(100_300_000, pauseSeconds = 0.0), SimStation.Am(100_200_000)),
            initialSampleRate = 960_000,
        )
        val sink = CollectingSink()
        val (r, events) = start(source, ReceiverSettings(100_300_000, 960_000, Mode.NFM), sink)
        await("the first status") { r.status.tuned }
        assertEquals("a narrow channel sits a quarter of the rate above the tuner", 100_060_000L, r.status.centerHz)
        r.update { it.withMode(Mode.AM).copy(frequencyHz = 100_200_000) }
        await("the new channel") { r.status.frequencyHz == 100_200_000L }
        val before = sink.frames
        await("two seconds of AM audio") { sink.frames >= before + 96_000 }
        r.stop()
        assertEquals(1, source.calls.count { it.startsWith("freq") })
        assertTrue(events.statuses.none { it.blocks > 0 && !it.tuned })
        assertEquals(100_060_000L, r.status.centerHz)
        val audio = sink.audio()
        assertEquals(800.0, Signals.zeroCrossingFrequency(audio, 48_000.0, audio.size - 24_000, audio.size), 2.0)
    }

    @Test
    fun aFrequencyOutsideTheStreamRetunesAndDiscardsWhatWasTakenBefore() {
        val source = ScriptedSource(listOf(station), pipelineBlocks = 5)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the station name") { events.rds.lastOrNull()?.ps == "TEST FM " }
        r.update { it.copy(frequencyHz = 101_000_000) }
        await("the new frequency") { r.status.tuned && r.status.centerHz == 101_000_000L }
        assertEquals("the block in hand and the five in the pipeline", 6L, r.status.staleBlocks)
        assertEquals(listOf("freq 100000000", "freq 101000000"), source.calls.filter { it.startsWith("freq") })
        assertTrue("a status announces the retune", events.statuses.any { !it.tuned && it.centerHz == 100_000_000L })
        assertEquals("the station's data is cleared", "", events.rds.last().ps)
        val frame = SpectrumFrame(4_096)
        await("a spectrum of the new frequency") { r.spectrum(frame) && frame.centerHz == 101_000_000L }
    }

    @Test
    fun aSourceThatCannotTuneIsLimitedToItsBand() {
        val source = ScriptedSource(canTune = false, canSetRate = false, kind = SourceKind.IQ_FILE)
        val (r, _) = start(source, ReceiverSettings(100_500_000, 240_000, Mode.NFM))
        await("the first status") { r.status.tuned }
        assertEquals(100_000_000L, r.status.centerHz)
        assertEquals("120 kHz minus half the channel above the centre", 100_113_750L, r.status.frequencyHz)
        assertTrue(source.calls.none { it.startsWith("freq") })
        r.update { it.copy(frequencyHz = 100_050_000) }
        await("a frequency inside the band") { r.status.frequencyHz == 100_050_000L }
    }

    @Test
    fun aRefusedFrequencyIsReportedAndTheReceiverCarriesOn() {
        val source = ScriptedSource(maxFrequencyHz = 1_000_000_000)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.update { it.copy(frequencyHz = 2_000_000_000) }
        await("the warning") { events.warnings.isNotEmpty() }
        assertTrue(events.warnings.single(), events.warnings.single().contains("2000000000"))
        val blocks = r.status.blocks
        await("more blocks") { r.status.blocks > blocks + 10 }
        assertEquals(100_000_000L, r.status.centerHz)
        assertTrue(events.errors.isEmpty())
    }

    @Test
    fun aSourceErrorIsReportedAndEndsTheSession() {
        val source = ScriptedSource()
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        source.fail(SourceError.StreamEnded("The server closed the connection"))
        assertTrue(events.stopped.await(10, SECONDS))
        assertEquals("The server closed the connection", events.errors.single().message)
        assertEquals(listOf("error", "stopped"), events.order.toList())
        assertEquals("close", source.calls.last())
    }

    @Test
    fun stopClosesTheSourceAndReportsOnce() {
        val source = ScriptedSource()
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.stop()
        r.stop()
        assertEquals(1, events.stops.get())
        assertEquals(1, source.calls.count { it == "close" })
        assertTrue(events.errors.isEmpty())
        try {
            r.start()
            org.junit.Assert.fail("a second start() should throw")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aRateTooLowForTheModeStillShowsTheSpectrum() {
        val source = ScriptedSource(initialSampleRate = 48_000, canSetRate = false)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 48_000))
        await("the first status") { r.status.tuned }
        assertFalse(r.status.demodulating)
        assertTrue(events.warnings.single(), events.warnings.single().contains("WFM needs at least 120000"))
        val frame = SpectrumFrame(4_096)
        await("a spectrum") { r.spectrum(frame) }
        assertEquals(48_000, frame.sampleRate)
        r.update { it.withMode(Mode.NFM) }
        await("narrow FM") { r.status.demodulating }
    }

    @Test
    fun volumeMuteAndSquelchReachTheAudio() {
        val source = ScriptedSource(listOf(SimStation.Am(100_060_000)), initialSampleRate = 240_000)
        val sink = CollectingSink()
        val (r, _) = start(source, ReceiverSettings(100_060_000, 240_000, Mode.AM), sink)
        await("audio") { sink.frames > 48_000 }
        val audio = sink.audio()
        assertTrue(Signals.peak(audio, audio.size - 4_800, audio.size) > 0.2f)

        r.update { it.copy(muted = true) }
        await("silence") { sink.audio().let { a -> Signals.peak(a, a.size - 4_800, a.size) == 0f } }
        r.update { it.copy(muted = false, squelchDb = -10.0) }      // the carrier is at −26 dBFS
        await("a closed squelch") { !r.status.squelchOpen && r.status.blocks > 0 }
        await("silence") { sink.audio().let { a -> Signals.peak(a, a.size - 4_800, a.size) == 0f } }
        r.update { it.copy(squelchDb = -40.0) }
        await("an open squelch") { r.status.squelchOpen }
        assertEquals(-26.0, r.status.channelPowerDb, 1.5)
    }

    @Test
    fun automaticSquelchOpensOnASignalAndClosesOnAnEmptyChannel() {
        val source = ScriptedSource(listOf(SimStation.NarrowFm(100_300_000, pauseSeconds = 0.0)), initialSampleRate = 960_000, noiseLevel = 0.002f)
        val (r, _) = start(source, ReceiverSettings(100_300_000, 960_000, Mode.NFM, autoSquelch = true))
        await("an open squelch") { r.status.tuned && r.status.squelchOpen && r.status.noiseFloorDb > -200.0 }
        // Noise of 0.002 per component is −51 dBFS over 960 kHz; the channel filter passes 14.1 kHz of it.
        assertEquals(-69.3, r.status.noiseFloorDb, 2.0)
        assertEquals(-26.0, r.status.channelPowerDb, 1.0)
        r.update { it.copy(frequencyHz = 100_200_000) }
        await("a closed squelch") { r.status.frequencyHz == 100_200_000L && !r.status.squelchOpen }
        assertEquals(r.status.noiseFloorDb, r.status.channelPowerDb, 2.5)
    }

    @Test
    fun spectrumFollowsTheFftSizeAndDcRemoval() {
        val source = ScriptedSource(dcOffset = 0.1f, kind = SourceKind.RTL_TCP)
        val (r, _) = start(source, ReceiverSettings(100_000_000, 240_000, dcRemoval = false))
        val frame = SpectrumFrame(4_096)
        await("a spectrum") { r.spectrum(frame) }
        assertEquals("the DC offset stands in the centre", -20f, frame.levelsDb[2_048], 0.5f)
        r.update { it.copy(dcRemoval = null, fftSize = 1_024, averaging = 1.0) }   // null: on, because the source is an RTL-SDR
        await("the new FFT size") { r.fftSize == 1_024 }
        assertFalse("a frame of the old size gets nothing", r.spectrum(frame))
        val small = SpectrumFrame(1_024)
        await("a spectrum without the offset") { r.spectrum(small) && small.levelsDb[512] < -60f }
        assertTrue(Receiver.defaultDcRemoval(SourceKind.RTL_TCP))
        assertTrue(Receiver.defaultDcRemoval(SourceKind.ANDROID_DRIVER))
        assertFalse(Receiver.defaultDcRemoval(SourceKind.RSP_TCP))
    }

    @Test
    fun aNewSampleRateIsAskedOfTheSourceAndFollowed() {
        val source = ScriptedSource(listOf(station), pipelineBlocks = 2)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.update { it.copy(sampleRate = 480_000) }
        await("the new rate") { r.status.tuned && r.status.sampleRate == 480_000 }
        assertTrue(source.calls.contains("rate 480000"))
        assertEquals("the tuner did not have to move", 1, source.calls.count { it.startsWith("freq") })
        assertEquals(3L, r.status.staleBlocks)
        await("the station name at the new rate") { events.rds.lastOrNull()?.ps == "TEST FM " }
    }

    @Test
    fun aSourceThatNeverConfirmsARetuneDoesNotSilenceTheReceiverForEver() {
        val source = ScriptedSource(pipelineBlocks = Int.MAX_VALUE / 2)
        val (r, events) = start(source, ReceiverSettings(100_000_000, 240_000))
        await("the first status") { r.status.tuned }
        r.update { it.copy(frequencyHz = 101_000_000) }
        await("the warning") { events.warnings.isNotEmpty() }
        assertTrue(events.warnings.single(), events.warnings.single().contains("did not confirm"))
        await("reception at the old frequency") { r.status.tuned }
        assertEquals(100_000_000L, r.status.centerHz)
        // Just under five seconds of 4,096-sample blocks at 240 kS/s were discarded
        assertEquals(292L, r.status.staleBlocks)
        assertEquals("the channel is limited to the stream it is in", 100_020_000L, r.status.frequencyHz)
    }

    @Test
    fun theQueueHoldsAboutHalfASecond() {
        assertEquals(74, Receiver.queueCapacity(2_400_000, 16_384))
        assertEquals(8, Receiver.queueCapacity(240_000, 16_384))
        assertEquals(2, Receiver.queueCapacity(48_000, 16_384))
    }

    @Test
    fun settingsAreValidated() {
        val bad = listOf<() -> ReceiverSettings>(
            { ReceiverSettings(-1, 240_000) },
            { ReceiverSettings(100_000_000, 0) },
            { ReceiverSettings(100_000_000, 240_000, Mode.NFM, bandwidthHz = 200_000) },
            { ReceiverSettings(100_000_000, 240_000, volume = 1.5f) },
            { ReceiverSettings(100_000_000, 240_000, fftSize = 3_000) },
            { ReceiverSettings(100_000_000, 240_000, framesPerSecond = 5) },
            { ReceiverSettings(100_000_000, 240_000, averaging = 0.0) },
            { ReceiverSettings(100_000_000, 240_000, cwPitchHz = 50.0) },
            { ReceiverSettings(100_000_000, 240_000, squelchDb = Double.NaN) },
        )
        for ((i, make) in bad.withIndex()) {
            try {
                make()
                org.junit.Assert.fail("settings #$i should be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(12_500, ReceiverSettings(100_000_000, 240_000).withMode(Mode.NFM).bandwidthHz)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.engine.TuningPolicyTest`
Expected: the build fails with `unresolved reference 'TuningPolicy'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/engine/TuningPolicy.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import kotlin.math.abs

/**
 * Decides where the tuner sits for a wanted frequency. Moving the channel inside the stream costs
 * nothing (§7.1 step 4), so the tuner moves only when the channel would leave the usable part of
 * the stream or, for the narrow modes, touch its centre: there the DC removal cuts a notch and
 * most tuners leave a spike, which would take the carrier out of an AM signal. Broadcast FM is
 * 200 kHz wide and not harmed by either, and centring it leaves the most room at low rates.
 */
object TuningPolicy {
    /** Distance kept between the edge of a narrow channel and the centre of the stream, in Hz. */
    const val CENTER_GUARD_HZ = 1_000.0

    /** Share of the stream's bandwidth that is flat enough to listen in (§7). */
    fun usableFraction(kind: SourceKind): Double = if (kind == SourceKind.RSP_TCP) 0.85 else 0.75

    /** How far the channel reaches on either side of the tuned frequency, in Hz. */
    fun halfWidthHz(mode: Mode, bandwidthHz: Int, cwPitchHz: Double): Double = when (mode) {
        Mode.WFM, Mode.NFM, Mode.AM -> bandwidthHz / 2.0
        Mode.USB, Mode.LSB -> 3_200.0
        Mode.CW -> cwPitchHz + bandwidthHz
    }

    /** Whether a channel [offsetHz] from the centre of a stream of [sampleRate] is received cleanly. */
    fun fits(offsetHz: Double, sampleRate: Int, mode: Mode, halfWidthHz: Double, usableFraction: Double): Boolean {
        val distance = abs(offsetHz)
        if (distance + halfWidthHz > usableFraction * sampleRate / 2) return false
        return mode == Mode.WFM || distance - halfWidthHz >= CENTER_GUARD_HZ
    }

    /** Where the tuner goes when it has to move: on the station for broadcast FM, a quarter of the rate below it otherwise. */
    fun centerFor(frequencyHz: Long, sampleRate: Int, mode: Mode): Long =
        if (mode == Mode.WFM) frequencyHz else maxOf(0L, frequencyHz - sampleRate / 4)

    /**
     * The tuner frequency to use for [frequencyHz]: [currentCenterHz] while the channel is received
     * cleanly there, otherwise [centerFor]. A null [currentCenterHz] means the tuner was never set.
     */
    fun plan(
        frequencyHz: Long,
        currentCenterHz: Long?,
        sampleRate: Int,
        mode: Mode,
        bandwidthHz: Int,
        cwPitchHz: Double,
        usableFraction: Double,
    ): Long {
        if (currentCenterHz != null) {
            val half = halfWidthHz(mode, bandwidthHz, cwPitchHz)
            if (fits((frequencyHz - currentCenterHz).toDouble(), sampleRate, mode, half, usableFraction)) return currentCenterHz
        }
        return centerFor(frequencyHz, sampleRate, mode)
    }

    /**
     * The offset nearest to [offsetHz] that keeps the whole channel inside a stream of [sampleRate]:
     * what a source that cannot tune (a recording) is limited to.
     */
    fun clampOffset(offsetHz: Double, sampleRate: Int, halfWidthHz: Double): Double {
        val limit = maxOf(0.0, sampleRate / 2.0 - halfWidthHz)
        return offsetHz.coerceIn(-limit, limit)
    }
}
```

Create `core/src/main/kotlin/engine/ReceiverSettings.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowType
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind

/**
 * Everything the user can set on the receiver. Immutable: a change is a copy, handed to
 * [Receiver.update], and takes effect on the DSP thread at the next block.
 */
data class ReceiverSettings(
    /** The frequency listened to, in Hz. The receiver decides where the tuner goes. */
    val frequencyHz: Long,
    /** IQ sample rate asked of the source. */
    val sampleRate: Int,
    val mode: Mode = Mode.WFM,
    val bandwidthHz: Int = mode.defaultBandwidthHz,
    /** Squelch threshold in dBFS; negative infinity leaves the squelch open. Ignored while [autoSquelch]. */
    val squelchDb: Double = Double.NEGATIVE_INFINITY,
    val autoSquelch: Boolean = false,
    /** Linear volume, 0–1. */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** False forces broadcast FM to mono. */
    val stereo: Boolean = true,
    /** Broadcast FM de-emphasis: 50 µs, or 75 µs in the Americas and South Korea. */
    val deemphasisSeconds: Double = 50e-6,
    val cwPitchHz: Double = 700.0,
    /** Removes the DC offset from the IQ stream (§6.1). Null leaves it to the source: on for RTL-SDR, off otherwise. */
    val dcRemoval: Boolean? = null,
    val fftSize: Int = 4_096,
    val window: WindowType = WindowType.BLACKMAN_HARRIS,
    val framesPerSecond: Int = 30,
    /** Weight of the newest spectrum frame in the average, 0.1–1. */
    val averaging: Double = 0.5,
    val peakDecayDbPerSecond: Double = 20.0,
) {
    init {
        require(frequencyHz >= 0) { "frequency must not be negative" }
        require(sampleRate > 0) { "sample rate must be positive" }
        require(bandwidthHz in mode.minBandwidthHz..mode.maxBandwidthHz) {
            "$mode bandwidth must be ${mode.minBandwidthHz}–${mode.maxBandwidthHz} Hz, got $bandwidthHz"
        }
        require(!squelchDb.isNaN()) { "squelch threshold must be a number" }
        require(volume in 0f..1f) { "volume must be 0–1" }
        require(deemphasisSeconds in 10e-6..200e-6) { "de-emphasis must be 10–200 µs" }
        require(cwPitchHz in 300.0..1_200.0) { "CW pitch must be 300–1,200 Hz" }
        require(fftSize in 1_024..65_536 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two from 1,024 to 65,536" }
        require(framesPerSecond in 10..60) { "frame rate must be 10–60 per second" }
        require(averaging in 0.1..1.0) { "averaging must be 0.1–1" }
        require(peakDecayDbPerSecond >= 0) { "peak decay must not be negative" }
    }

    /** These settings in another [mode], with that mode's default bandwidth. */
    fun withMode(mode: Mode): ReceiverSettings = copy(mode = mode, bandwidthHz = mode.defaultBandwidthHz)
}

/** A snapshot of what the receiver is doing, published about ten times per second of signal. */
data class ReceiverStatus(
    /** Tuner frequency of the samples being processed, in Hz. */
    val centerHz: Long = 0,
    /** Their sample rate. */
    val sampleRate: Int = 0,
    /** The frequency actually listened to; differs from the request when a recording's band ends before it. */
    val frequencyHz: Long = 0,
    /** False from the moment the tuner is told to move until samples from the new frequency arrive. */
    val tuned: Boolean = false,
    /** False when the sample rate is too low for the mode: the spectrum still runs. */
    val demodulating: Boolean = false,
    /** Channel power in dBFS (the S-meter). */
    val channelPowerDb: Double = Double.NEGATIVE_INFINITY,
    /** Noise expected in the empty channel in dBFS; negative infinity while unknown. */
    val noiseFloorDb: Double = Double.NEGATIVE_INFINITY,
    val squelchOpen: Boolean = false,
    val stereo: Boolean = false,
    val rdsSynced: Boolean = false,
    /** Blocks processed since the start. */
    val blocks: Long = 0,
    /** Blocks discarded because they were taken before a retune had taken effect. */
    val staleBlocks: Long = 0,
    /** Blocks dropped because the DSP thread fell behind (§11). */
    val overruns: Long = 0,
    /** Share of real time the DSP thread needed lately: 0.25 means four times faster than real time. */
    val load: Double = 0.0,
)

/** Receiver events. Every call comes from the receiver's DSP thread. */
interface ReceiverListener {
    fun onStatus(status: ReceiverStatus) {}

    /** The decoded RDS data changed; an empty [RdsState] means there is none (any more). */
    fun onRds(state: RdsState) {}

    /** A request could not be carried out (for example a frequency the source cannot tune); the receiver carries on. */
    fun onWarning(message: String) {}

    /** The source failed; [onStopped] follows. */
    fun onSourceError(error: SourceError) {}

    /** The receiver has ended, by [Receiver.stop] or because its source did. Called exactly once. */
    fun onStopped() {}
}
```

Create `core/src/main/kotlin/engine/Receiver.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlocker
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueue
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceListener
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzer
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The receiver (§4.3). The source's thread hands blocks to a queue that holds about half a second;
 * the receiver's DSP thread takes them out and runs the spectrum analyzer and the listening chain
 * on each. Settings are replaced as a whole through [update] from any thread and take effect on
 * the DSP thread before the next block, so the signal path itself needs no locks.
 *
 * Tuning follows [TuningPolicy]: a new frequency inside the stream only moves the channel; one
 * outside it retunes the source, and the blocks taken before the retune took effect are discarded
 * by their tune epoch (§7.1).
 *
 * One receiver is one session: [start] it once and [stop] it once. The receiver opens and closes
 * the source.
 */
class Receiver(
    private val source: IqSource,
    private val sink: AudioSink,
    initial: ReceiverSettings,
    private val listener: ReceiverListener,
) {
    /** The settings in force or about to be. */
    @Volatile
    var settings: ReceiverSettings = initial
        private set

    /** The latest status snapshot. */
    @Volatile
    var status = ReceiverStatus()
        private set

    private val usableFraction = TuningPolicy.usableFraction(source.info.kind)
    private val dcRemovalByDefault = defaultDcRemoval(source.info.kind)
    private val queue = BlockQueue(8)
    private val errors = ConcurrentLinkedQueue<SourceError>()
    private val started = AtomicBoolean(false)
    private val settingsLock = Any()

    @Volatile private var running = false
    @Volatile private var sourceEnded = false
    @Volatile private var analyzer = newAnalyzer(initial)
    @Volatile private var thread: Thread? = null

    // Touched by the source's thread only.
    private var queuedRate = 0
    private var queuedBlockSize = 0

    // Everything below is touched by the DSP thread only.
    private var applied: ReceiverSettings? = null
    private var wantedCenterHz: Long? = null
    private var requiredEpoch = 0
    private var tuned = false
    private var chain: ReceiverChain? = null
    private var chainRate = 0
    private var chainSettings: ReceiverSettings? = null
    private var dcBlocker: DcBlocker? = null
    private var dcRate = 0
    private var lastRds = NO_RDS
    private var blocks = 0L
    private var staleBlocks = 0L
    private var staleSamples = 0L
    private var sinceStatus = 0L
    private var workNanos = 0L

    /** The FFT size of the frames [spectrum] delivers right now. */
    val fftSize: Int get() = analyzer.fftSize

    /** Replaces the settings with [change] applied to the current ones. */
    fun update(change: (ReceiverSettings) -> ReceiverSettings) {
        synchronized(settingsLock) { settings = change(settings) }
    }

    /**
     * Copies the newest spectrum into [dst] when there is one newer than [dst] holds; returns
     * whether it copied. A frame whose size is not [fftSize] gets nothing.
     */
    fun spectrum(dst: SpectrumFrame): Boolean {
        val a = analyzer
        return dst.size == a.fftSize && a.latest(dst)
    }

    /** Opens the source and starts receiving. Problems are reported through the listener. */
    fun start() {
        check(started.compareAndSet(false, true)) { "a Receiver can be started once" }
        running = true
        thread = Thread({ run() }, "receiver-dsp").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops receiving and closes the source; returns once the DSP thread has ended. */
    fun stop() {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) t.join(STOP_TIMEOUT_MS)
    }

    private val sourceListener = object : SourceListener {
        override fun onStarted(info: SourceInfo) {}

        override fun onBlock(block: IqBlock) {
            if (!running) {
                block.release()
                return
            }
            if (block.sampleRate != queuedRate || block.capacity != queuedBlockSize) {
                queuedRate = block.sampleRate
                queuedBlockSize = block.capacity
                queue.capacity = queueCapacity(block.sampleRate, block.capacity)
            }
            queue.offer(block)
        }

        override fun onError(error: SourceError) {
            errors.add(error)
        }

        override fun onStopped() {
            sourceEnded = true
        }
    }

    private fun run() {
        try {
            apply(settings)   // tunes the source before it is opened, so its first blocks are already right
            if (running) source.open(sourceListener)
            while (running) {
                reportErrors()
                val block = queue.take(POLL_MS)
                if (block == null) {
                    if (sourceEnded) break
                    continue
                }
                try {
                    process(block)
                } finally {
                    block.release()
                }
            }
        } catch (e: Exception) {
            if (running) errors.add(SourceError.Io("The receiver stopped: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            running = false
            runCatching { source.close() }
            queue.clear()
            reportErrors()
            listener.onStopped()
        }
    }

    private fun reportErrors() {
        while (true) listener.onSourceError(errors.poll() ?: return)
    }

    private fun process(block: IqBlock) {
        val began = System.nanoTime()
        val s = settings
        if (s !== applied) apply(s)
        if (block.tuneEpoch < requiredEpoch) {
            staleSamples += block.length
            if (staleSamples < STALE_LIMIT_SECONDS * block.sampleRate) {
                staleBlocks++
                return
            }
            // The source never marked the change. Carrying on beats staying silent for ever.
            listener.onWarning("The source did not confirm the retune; receiving at ${block.centerHz} Hz")
            requiredEpoch = block.tuneEpoch
        }
        staleSamples = 0
        if (!tuned) {
            tuned = true
            chain?.reset()
            analyzer.reset()
            dcBlocker?.reset()
        }
        val rate = block.sampleRate
        val samples = block.samples
        val count = block.length
        if (s.dcRemoval ?: dcRemovalByDefault) {
            val blocker = dcBlocker.takeIf { dcRate == rate } ?: DcBlocker(2, DC_CUTOFF_HZ, rate.toDouble()).also {
                dcBlocker = it
                dcRate = rate
            }
            blocker.process(samples, count)
        }
        analyzer.process(samples, count, rate, block.centerHz)
        val c = chainFor(rate, s)
        val half = TuningPolicy.halfWidthHz(s.mode, s.bandwidthHz, s.cwPitchHz)
        val offset = TuningPolicy.clampOffset((s.frequencyHz - block.centerHz).toDouble(), rate, half)
        if (c != null) {
            if (c.offsetHz != offset) c.offsetHz = offset
            c.noiseDensityDb = analyzer.noiseDensityDb
            c.process(samples, count)   // overwrites the block, so it comes last
        }
        blocks++
        val state = c?.rds?.state ?: NO_RDS
        if (state !== lastRds && state != lastRds) listener.onRds(state)
        lastRds = state
        workNanos += System.nanoTime() - began
        sinceStatus += count
        if (sinceStatus >= rate / STATUS_PER_SECOND) publish(block, c, offset)
    }

    private fun publish(block: IqBlock, c: ReceiverChain?, offsetHz: Double) {
        val seconds = sinceStatus.toDouble() / block.sampleRate
        val now = ReceiverStatus(
            centerHz = block.centerHz,
            sampleRate = block.sampleRate,
            frequencyHz = block.centerHz + offsetHz.roundToLong(),
            tuned = true,
            demodulating = c != null,
            channelPowerDb = c?.channelPowerDb ?: Double.NEGATIVE_INFINITY,
            noiseFloorDb = c?.noiseFloorDb ?: Double.NEGATIVE_INFINITY,
            squelchOpen = c?.squelch?.open ?: false,
            stereo = c?.stereo ?: false,
            rdsSynced = c?.rds?.synced ?: false,
            blocks = blocks,
            staleBlocks = staleBlocks,
            overruns = queue.overruns,
            load = workNanos / (seconds * 1e9),
        )
        sinceStatus = 0
        workNanos = 0
        status = now
        listener.onStatus(now)
    }

    /** Brings the analyzer, the chain's controls and the tuner in line with [s]. */
    private fun apply(s: ReceiverSettings) {
        val old = applied
        applied = s
        if (old != null && (s.fftSize != old.fftSize || s.window != old.window || s.framesPerSecond != old.framesPerSecond)) {
            analyzer = newAnalyzer(s)
        }
        analyzer.averaging = s.averaging
        analyzer.peakDecayDbPerSecond = s.peakDecayDbPerSecond
        chain?.let { configure(it, s) }

        var moved = false
        if (old == null || s.sampleRate != old.sampleRate) {
            try {
                requiredEpoch = maxOf(requiredEpoch, source.setSampleRate(s.sampleRate))
                moved = true
            } catch (e: IllegalArgumentException) {
                listener.onWarning(e.message ?: "The source cannot deliver ${s.sampleRate} samples per second")
            }
        }
        if (source.info.canTune) {
            val center = TuningPolicy.plan(s.frequencyHz, wantedCenterHz, s.sampleRate, s.mode, s.bandwidthHz, s.cwPitchHz, usableFraction)
            if (center != wantedCenterHz) {
                try {
                    requiredEpoch = maxOf(requiredEpoch, source.setCenterFrequency(center))
                    wantedCenterHz = center
                    moved = true
                } catch (e: IllegalArgumentException) {
                    listener.onWarning(e.message ?: "The source cannot tune to $center Hz")
                }
            }
        }
        if (moved) {
            if (tuned) {
                tuned = false
                val waiting = status.copy(tuned = false)
                status = waiting
                listener.onStatus(waiting)
            }
        } else if (old != null && s.frequencyHz != old.frequencyHz) {
            chain?.resetDecoders()   // another station inside the same stream
        }
    }

    /** The chain for blocks at [rate], rebuilt when the rate or a setting that shapes it changed. */
    private fun chainFor(rate: Int, s: ReceiverSettings): ReceiverChain? {
        val built = chainSettings
        if (rate == chainRate && built != null && s.mode == built.mode && s.bandwidthHz == built.bandwidthHz &&
            s.deemphasisSeconds == built.deemphasisSeconds && s.cwPitchHz == built.cwPitchHz
        ) {
            return chain
        }
        chainRate = rate
        chainSettings = s
        chain = if (ReceiverChain.supports(rate, s.mode)) {
            ReceiverChain(rate, s.mode, s.bandwidthHz, sink, s.deemphasisSeconds, s.cwPitchHz).also { configure(it, s) }
        } else {
            listener.onWarning("${s.mode} needs at least ${ReceiverChain.minimumSampleRate(s.mode)} samples per second; the source delivers $rate")
            null
        }
        return chain
    }

    private fun configure(c: ReceiverChain, s: ReceiverSettings) {
        c.squelch.thresholdDb = s.squelchDb
        c.squelch.auto = s.autoSquelch
        c.audio.volume = s.volume
        c.audio.muted = s.muted
        c.stereoEnabled = s.stereo
    }

    private fun newAnalyzer(s: ReceiverSettings) =
        SpectrumAnalyzer(s.fftSize, s.window, s.framesPerSecond.toDouble(), usableFraction = usableFraction)

    companion object {
        private val NO_RDS = RdsState()
        private const val QUEUE_SECONDS = 0.5
        private const val POLL_MS = 50L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val STATUS_PER_SECOND = 10
        private const val DC_CUTOFF_HZ = 30.0
        private const val STALE_LIMIT_SECONDS = 5.0

        /** Blocks of [blockSize] samples that hold about half a second at [sampleRate] (§4.3). */
        fun queueCapacity(sampleRate: Int, blockSize: Int): Int =
            maxOf(2, ceil(QUEUE_SECONDS * sampleRate / blockSize).toInt())

        /** DC removal is on by default for RTL-SDR sources (§6.1). */
        fun defaultDcRemoval(kind: SourceKind): Boolean = kind == SourceKind.RTL_TCP || kind == SourceKind.ANDROID_DRIVER
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.engine.TuningPolicyTest io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverTest`
Expected: `OK (22 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (232 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): receiver with DSP thread and tuning policy

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 13: The simulator source

`SimBand` behind the `IqSource` interface: its own thread, pacing against the clock, noise, tune epochs. With it the whole engine, and later the whole app, runs without hardware (§12).

**Files:**
- Create: `core/src/main/kotlin/source/SimulatorSource.kt`
- Test: `core/src/test/kotlin/source/SimulatorSourceTest.kt`

**Interfaces:**
- Consumes: `SimBand`, `SimStation` (Task 11); `IqSource` with `setSampleRate(sps): Int` (Task 2); `BlockPool`, `SourceInfo`, `SourceKind.SIMULATOR`, `SampleFormat`, `GainModel.None`, `WavIqFileSource.dueNanos(emitted: Long, sampleRate: Int): Long` (Plan 1); `Receiver`, `ReceiverSettings`, `ReceiverListener` (Task 12, tests); `CollectingListener` (Plan 1, tests).
- Produces:
  - `class SimulatorSource(stations: List<SimStation> = SimBand.defaultStations(), initialFrequencyHz: Long = 100_000_000, initialSampleRate: Int = 960_000, speed: Double = 1.0, noiseLevel: Float = 0.002f, blockSize: Int = 16_384) : IqSource`; companion `SAMPLE_RATES = listOf(240_000, 960_000, 1_200_000, 2_400_000)`, `MIN_SAMPLE_RATE = 48_000`, `MAX_SAMPLE_RATE = 3_200_000`. `speed` multiplies real time; a huge value removes the pacing.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/source/SimulatorSourceTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Receiver
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverListener
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class SimulatorSourceTest {
    private val carrier = listOf(SimStation.Am(100_100_000, depth = 0.0, level = 0.1f))
    private val unpaced = 1e9

    @Test
    fun anEmptyBandIsNoiseAtTheRequestedLevel() {
        val source = SimulatorSource(emptyList(), speed = unpaced, noiseLevel = 0.002f)
        val l = CollectingListener()
        source.open(l)
        val iq = l.awaitEpochSamples(epoch = 0, complex = 65_536)
        source.close()
        assertEquals("0.002 of full scale is −54 dBFS", -54.0, Signals.complexPowerDb(iq), 0.3)
    }

    @Test
    fun streamsTheBandAroundItsFrequency() {
        val source = SimulatorSource(carrier, speed = unpaced, noiseLevel = 0.002f)
        assertEquals(SourceKind.SIMULATOR, source.info.kind)
        assertTrue(source.info.canTune)
        assertEquals(100_000_000L, source.centerFrequencyHz)
        val l = CollectingListener()
        source.open(l)
        val iq = l.awaitEpochSamples(epoch = 0, complex = 65_536)
        source.close()
        assertTrue(l.stopped.await(5, SECONDS))
        val first = l.blocks.first()
        assertEquals(100_000_000L, first.centerHz)
        assertEquals(960_000, first.sampleRate)
        assertEquals(2 * 16_384, first.samples.size)
        assertEquals(-20.0, Signals.complexToneDb(iq, 100_000.0, 960_000.0), 0.1)
        assertTrue(l.errors.isEmpty())
    }

    @Test
    fun retunesTakeEffectAtABlockBoundary() {
        val source = SimulatorSource(carrier, speed = unpaced)
        val l = CollectingListener()
        source.open(l)
        l.awaitEpochSamples(epoch = 0, complex = 16_384)
        val epoch = source.setCenterFrequency(100_050_000)
        assertEquals(1, epoch)
        val iq = l.awaitEpochSamples(epoch = epoch, complex = 32_768)
        source.close()
        assertEquals(100_050_000L, source.centerFrequencyHz)
        assertTrue(l.blocksOf(0).all { it.centerHz == 100_000_000L })
        assertTrue(l.blocksOf(epoch).all { it.centerHz == 100_050_000L })
        assertEquals("the carrier is now 50 kHz above the centre", -20.0, Signals.complexToneDb(iq, 50_000.0, 960_000.0), 0.1)
    }

    @Test
    fun sampleRateChangesAreMarkedByAnEpochToo() {
        val source = SimulatorSource(carrier, speed = unpaced)
        val l = CollectingListener()
        source.open(l)
        l.awaitEpochSamples(epoch = 0, complex = 16_384)
        val epoch = source.setSampleRate(240_000)
        val iq = l.awaitEpochSamples(epoch = epoch, complex = 32_768)
        source.close()
        assertTrue(l.blocksOf(0).all { it.sampleRate == 960_000 })
        assertTrue(l.blocksOf(epoch).all { it.sampleRate == 240_000 })
        assertEquals(-20.0, Signals.complexToneDb(iq, 100_000.0, 240_000.0), 0.1)
    }

    @Test
    fun neverRunsAheadOfTheClock() {
        for (speed in doubleArrayOf(1.0, 4.0)) {
            val source = SimulatorSource(carrier, initialSampleRate = 240_000, speed = speed)
            val l = CollectingListener(keepPerEpoch = 0)
            val began = System.nanoTime()
            source.open(l)
            Thread.sleep(400)
            val samples = l.totalComplex.get()
            val seconds = (System.nanoTime() - began) / 1e9
            source.close()
            assertTrue("speed $speed: $samples samples in $seconds s", samples <= speed * 240_000 * seconds + 2 * 16_384)
            assertTrue("speed $speed: the stream runs", samples > 0)
        }
    }

    @Test
    fun rejectsWhatItCannotSimulate() {
        val source = SimulatorSource(carrier)
        for (bad in listOf<() -> Unit>(
            { source.setSampleRate(10_000) },
            { source.setSampleRate(10_000_000) },
            { source.setCenterFrequency(-1) },
            { SimulatorSource(carrier, initialSampleRate = 0) },
            { SimulatorSource(carrier, speed = 0.0) },
        )) {
            try {
                bad()
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(1, source.setSampleRate(1_024_000))
    }

    @Test
    fun theDefaultBandPlaysThroughTheReceiverWithStationNames() {
        val names = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener = object : ReceiverListener {
            override fun onRds(state: RdsState) {
                names += state.ps
            }
        }
        val source = SimulatorSource(speed = 4.0)
        val receiver = Receiver(source, CollectingSink(), ReceiverSettings(100_300_000, 960_000), listener)
        receiver.start()
        try {
            fun await(what: String, condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 30_000
                while (!condition()) {
                    check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; names so far: $names" }
                    Thread.sleep(5)
                }
            }
            await("the first station") { names.lastOrNull() == "DEMO FM " }
            assertTrue(receiver.status.stereo)
            assertEquals(100_300_000L, receiver.status.centerHz)
            receiver.update { it.copy(frequencyHz = 99_500_000) }
            await("the second station") { names.lastOrNull() == "SPECTRA1" }
            assertEquals(99_500_000L, receiver.status.centerHz)
            assertTrue(receiver.status.squelchOpen)
        } finally {
            receiver.stop()
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.SimulatorSourceTest`
Expected: the build fails with `unresolved reference 'SimulatorSource'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/source/SimulatorSource.kt`:

```kotlin
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
                var at = random.nextInt(NOISE_SIZE)
                for (n in 0 until blockSize) {
                    out[2 * n] = noiseLevel * noise[2 * at]
                    out[2 * n + 1] = noiseLevel * noise[2 * at + 1]
                    if (++at == NOISE_SIZE) at = 0
                }
                current.render(out, blockSize, centerHz)
                block.length = blockSize
                block.seq = seq++
                block.centerHz = centerHz
                block.sampleRate = rate
                block.tuneEpoch = epoch
                emitted += blockSize
                val wait = startNs + (WavIqFileSource.dueNanos(emitted, rate) / speed).toLong() - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                listener.onBlock(block)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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
```

- [ ] **Step 4: Run it and watch it pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.SimulatorSourceTest`
Expected: `OK (7 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (239 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
feat(core): simulator source

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 14: Throughput and zero allocation

Two rules of §4.4 become tests (§12): the heaviest chain (broadcast FM in stereo with RDS, plus a 4,096-point spectrum, at 2.4 MS/s) runs at least three times faster than real time on the JVM, and the signal path allocates nothing once it runs. The second test fails at first: `DecimatorChain` walks its stages with an iterator on every block. The fix also corrects the `planStages` KDoc (Plan 1 follow-up).

**Files:**
- Modify: `core/src/main/kotlin/dsp/DecimatorChain.kt`
- Test: `core/src/test/kotlin/engine/PerformanceTest.kt`

**Interfaces:**
- Consumes: `ReceiverChain`, `AudioSink`, `Mode` (Task 10); `SpectrumAnalyzer` (Task 9); `SimBand`, `SimStation` (Task 11); `TestStations` (Task 10); `RdsEncoder` (Task 4).
- Produces: no new API. `DecimatorChain.stageFactors` becomes a stored list instead of a list built on every access.

- [ ] **Step 1: Write the tests**

Create `core/src/test/kotlin/engine/PerformanceTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.engine

import io.github.radhwanalhajjaj.spectrasdr.core.sim.RdsEncoder
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBand
import io.github.radhwanalhajjaj.spectrasdr.core.sim.SimStation
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumAnalyzer
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.TestStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** The performance rules of §4.4, checked on the JVM (§12). */
class PerformanceTest {
    /** A sink that keeps nothing, so that it neither allocates nor costs time. */
    private class CountingSink : AudioSink {
        override val sampleRate = 48_000
        var frames = 0L

        override fun write(samples: FloatArray, frames: Int, channels: Int) {
            this.frames += frames
        }

        override fun fillLevel() = 0.5f
    }

    @Test
    fun wfmStereoWithRdsAndSpectrumRunsThreeTimesFasterThanRealTime() {
        val rate = 2_400_000
        val seconds = 1.0
        val iq = TestStations.fmBroadcast(rate.toDouble(), 300_000.0, seconds, 1_000.0, 400.0, RdsEncoder(0xD318, "SPECTRA ", "Throughput"))
        val sink = CountingSink()
        val chain = ReceiverChain(rate, Mode.WFM, 200_000, sink)
        chain.offsetHz = 300_000.0
        val analyzer = SpectrumAnalyzer(4_096)
        val block = FloatArray(2 * 16_384)
        val blocks = iq.size / 2 / 16_384
        var best = 0.0
        repeat(8) { round ->                  // the first rounds warm the JIT compiler up
            val began = System.nanoTime()
            for (b in 0 until blocks) {
                System.arraycopy(iq, 2 * b * 16_384, block, 0, 2 * 16_384)
                analyzer.process(block, 16_384, rate, 0L)
                chain.process(block, 16_384)
            }
            val elapsed = (System.nanoTime() - began) / 1e9
            if (round >= 2) best = maxOf(best, blocks * 16_384 / rate.toDouble() / elapsed)
        }
        println("WFM stereo + RDS + 4,096-point spectrum at 2.4 MS/s: %.1f times real time".format(best))
        assertTrue(chain.stereo)
        assertEquals("SPECTRA ", chain.rds!!.state.ps)
        assertEquals("audio came out at 48 kHz", 8 * blocks * 16_384 / 50.0, sink.frames.toDouble(), 500.0)
        assertTrue("only %.1f times real time".format(best), best >= 3.0)
    }

    @Test
    fun theSignalPathAllocatesNothingOnceItRuns() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return   // not measurable on this JVM
        val thread = Thread.currentThread().id
        val rate = 960_000
        val station = SimStation.FmBroadcast(100_100_000, "ALLOC FM", "No garbage")
        for (mode in Mode.values()) {
            val band = SimBand(listOf(station), rate)
            val chain = ReceiverChain(rate, mode, mode.defaultBandwidthHz, CountingSink())
            chain.offsetHz = 100_000.0
            val analyzer = SpectrumAnalyzer(4_096)
            val block = FloatArray(2 * 16_384)
            fun step() {
                block.fill(0f)
                band.render(block, 16_384, 100_000_000L)
                analyzer.process(block, 16_384, rate, 100_000_000L)
                chain.noiseDensityDb = analyzer.noiseDensityDb
                chain.process(block, 16_384)
            }
            repeat(200) { step() }            // 3.4 s: every buffer has its size and the RDS text is complete
            // Garbage made per block shows in every window. The JIT compiler, though, can charge a
            // one-off to this thread when it swaps running code, so the quietest window counts.
            var quietest = Long.MAX_VALUE
            repeat(5) {
                val before = bean.getThreadAllocatedBytes(thread)
                repeat(50) { step() }
                quietest = minOf(quietest, bean.getThreadAllocatedBytes(thread) - before)
            }
            assertTrue("$mode allocated $quietest bytes in 50 blocks", quietest < 500)
        }
    }
}
```

- [ ] **Step 2: Run them: the speed test passes, the allocation test fails**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.engine.PerformanceTest`
Expected: FAIL with `theSignalPathAllocatesNothingOnceItRuns` and `Tests run: 2,  Failures: 1`: a mode reports some thousand bytes allocated in 50 blocks. The speed test prints its result, about 13 times real time on the machine this plan was written on.

- [ ] **Step 3: Remove the iterators**

In `core/src/main/kotlin/dsp/DecimatorChain.kt`, replace:

```kotlin

/** Cascade of [FirDecimator] stages (§6.3); the total factor is the product of the stage factors. */
class DecimatorChain(private val stages: List<FirDecimator>, val inputRate: Double) {
    val factor: Int = stages.fold(1) { acc, s -> acc * s.factor }
    val outputRate: Double = inputRate / factor
    val stageFactors: List<Int> get() = stages.map { it.factor }
    private var scratchA = FloatArray(0)
    private var scratchB = FloatArray(0)
```

with:

```kotlin

/** Cascade of [FirDecimator] stages (§6.3); the total factor is the product of the stage factors. */
class DecimatorChain(stages: List<FirDecimator>, val inputRate: Double) {
    // An array, so that the per-block loops below create no iterator (§4.4).
    private val stages = stages.toTypedArray()
    val factor: Int = stages.fold(1) { acc, s -> acc * s.factor }
    val outputRate: Double = inputRate / factor
    val stageFactors: List<Int> = stages.map { it.factor }
    private var scratchA = FloatArray(0)
    private var scratchB = FloatArray(0)
```

In `core/src/main/kotlin/dsp/DecimatorChain.kt`, replace:

```kotlin
    }

    fun reset() = stages.forEach { it.reset() }

    private fun scratch(idx: Int, complex: Int): FloatArray {
```

with:

```kotlin
    }

    fun reset() {
        for (s in stages) s.reset()
    }

    private fun scratch(idx: Int, complex: Int): FloatArray {
```

In `core/src/main/kotlin/dsp/DecimatorChain.kt`, replace:

```kotlin

    companion object {
        /** Splits [factor] into at most 3 stages, largest first; each stage ≤ 8 unless a prime factor is larger. */
        fun planStages(factor: Int): List<Int> {
            require(factor >= 1) { "factor must be >= 1" }
```

with:

```kotlin

    companion object {
        /**
         * Splits [factor] into at most 3 stages, largest first. Prime factors are combined into stages
         * of at most 8 where that is possible: a prime factor above 8 is a stage of its own, and when
         * more than three stages would result the smallest are merged, so 1024 becomes 16, 8, 8.
         * Callers that can choose the factor should prefer one whose stages are all at most 8.
         */
        fun planStages(factor: Int): List<Int> {
            require(factor >= 1) { "factor must be >= 1" }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.engine.PerformanceTest`
Expected: `OK (2 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (241 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'MSG'
perf(core): throughput and allocation tests; decimator chain without iterators

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

### Task 15: Spec and README aligned with the engine

The decisions at the top of this plan change the wording of five places in the spec. This task writes them in, so the spec stays the authority for Plans 3 to 6.

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, `README.md`

**Interfaces:**
- Consumes: nothing.
- Produces: no code.

- [ ] **Step 1: Edit the spec**

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
- **Date:** 2026-11-12
- **Status:** Design approved in chat; this written spec awaits review
- **License:** GPL-3.0-or-later
- **Working name:** SpectraSDR (renamable before first release)
```

with:

```markdown
- **Date:** 2026-11-12
- **Status:** Design approved in chat; this written spec awaits review
- **Revised:** 2026-11-14 — §4.1, §4.2, §6.3, §6.4 and §7.1 aligned with the engine as built (Plans 1 and 2)
- **License:** GPL-3.0-or-later
- **Working name:** SpectraSDR (renamable before first release)
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
|---------|----------|
| `source` | `IqSource` interface, sample converters, pooled `IqBlock`, `RtlTcpClient` (rtl_tcp + rsp_tcp extended), `WavIqFileSource`, `SimulatorSource` (synthetic stations), `RetuneSync` (stream markers and latency probe, §7.1) |
| `dsp` | `Fft`, windows, `Nco`, `FirDesign` (Kaiser windowed-sinc), `FirDecimator` (polyphase, complex), `DecimatorChain`, `FractionalResampler` with drift control, `DcBlocker`, `Agc`, `Deemphasis`, `Pll` |
| `demod` | `Demodulator` interface; `WfmDemod`, `StereoDecoder`, `NfmDemod`, `AmDemod`, `SsbDemod` (USB/LSB), `CwDemod`; `Squelch` |
| `rds` | `RdsDemodulator` (57 kHz subcarrier → bits), `RdsDecoder` (block sync, groups → `RdsState`) |
| `spectrum` | `SpectrumAnalyzer` (FFT frames, averaging, peak hold, display binning) |
| `sweep` | `SweepPlanner`, `SweepEngine`, `SweepResult`, CSV export |
| `scan` | `Channel`, `ScanList`, `Scanner` state machine, `ActivityLog` |
| `record` | `WavWriter` (crash-safe), `IqRecorder`, `AudioRecorder`, file naming and parsing |
| `engine` | `Receiver` orchestrator, `AudioSink` interface, listener interfaces |

**`:app`** — Android: Compose UI, `RadioService` (foreground, `mediaPlayback` type), `DriverLauncher` (`iqsrc` hand-off), `AndroidAudioSink` (AudioTrack), `WaterfallView` (GLSurfaceView), persistence (Room + DataStore), recordings browser (FileProvider sharing).
```

with:

```markdown
|---------|----------|
| `source` | `IqSource` interface, sample converters, pooled `IqBlock`, `RtlTcpClient` (rtl_tcp + rsp_tcp extended), `WavIqFileSource`, `SimulatorSource` (synthetic stations), `RetuneSync` (stream markers and latency probe, §7.1) |
| `dsp` | `Fft`, windows, `Nco`, `FirDesign` (Kaiser windowed-sinc), `FirDecimator` (polyphase, complex), `DecimatorChain`, `FractionalResampler` with drift control, `DcBlocker`, `Agc`, `Deemphasis`, `Pll`, `BiquadCascade` (Butterworth sections) |
| `demod` | `Demodulator` interface; `WfmDemod`, `StereoDecoder`, `NfmDemod`, `AmDemod`, `SsbDemod` (USB/LSB), `CwDemod`; `Squelch` |
| `rds` | `RdsDemodulator` (57 kHz subcarrier → bits), `RdsDecoder` (block sync, groups → `RdsState`) |
| `spectrum` | `SpectrumAnalyzer` (FFT frames, averaging, peak hold, display binning, noise-floor estimate) |
| `sim` | Signal generators shared by the simulator and the tests: `MpxGenerator`, `FmModulator`, `RdsEncoder`, `RdsBaseband`, `SimBand` |
| `sweep` | `SweepPlanner`, `SweepEngine`, `SweepResult`, CSV export |
| `scan` | `Channel`, `ScanList`, `Scanner` state machine, `ActivityLog` |
| `record` | `WavWriter` (crash-safe), `IqRecorder`, `AudioRecorder`, file naming and parsing |
| `engine` | `Receiver` orchestrator, `ReceiverChain` (channelizer → demodulator → squelch → audio output), `TuningPolicy`, `AudioSink` interface, listener interfaces |

**`:app`** — Android: Compose UI, `RadioService` (foreground, `mediaPlayback` type), `DriverLauncher` (`iqsrc` hand-off), `AndroidAudioSink` (AudioTrack), `WaterfallView` (GLSurfaceView), persistence (Room + DataStore), recordings browser (FileProvider sharing).
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
    fun open(listener: SourceListener)    // non-blocking; starts the source thread
    fun close()
    fun setCenterFrequency(hz: Long)
    fun setSampleRate(sps: Int)
    fun setGain(gain: GainSetting)        // Auto, TunerTenthsDb, TunerIndex, RspLna, RspIfgr, RspAgc
    fun setPpm(ppm: Int)
```

with:

```markdown
    fun open(listener: SourceListener)    // non-blocking; starts the source thread
    fun close()
    fun setCenterFrequency(hz: Long): Int // returns a tune epoch: blocks with tuneEpoch ≥ it follow the change (§7.1)
    fun setSampleRate(sps: Int): Int      // likewise
    fun setGain(gain: GainSetting)        // Auto, TunerTenthsDb, TunerIndex, RspLna, RspIfgr, RspAgc
    fun setPpm(ppm: Int)
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
interface SourceListener {
    fun onStarted(info: SourceInfo)
    fun onBlock(block: IqBlock)           // block carries seq, centerHz, sampleRate, interleaved floats
    fun onError(error: SourceError)
    fun onStopped()
```

with:

```markdown
interface SourceListener {
    fun onStarted(info: SourceInfo)
    fun onBlock(block: IqBlock)           // block carries seq, tuneEpoch, centerHz, sampleRate, interleaved floats
    fun onError(error: SourceError)
    fun onStopped()
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
- NCO (double-precision phase) shifts the VFO offset to 0 Hz.
- Multistage polyphase FIR decimation (up to 3 stages, Kaiser-designed, ≥ 60 dB alias rejection), with the largest decimation first where the transition band is wide.
- **WFM:** decimation D = max(1, floor(fs / 240 kHz)), giving 240–480 kHz (2.4 MS/s → 240 kHz); rates below 240 kHz run undecimated. Channel filter ±100 kHz by default.
- **Narrow modes:** integer decimation to 48–96 kHz, then the fractional resampler to exactly 48 kHz.
- Mode filters: NFM 12.5 kHz (presets 6.25/25), AM 8 kHz (6/10), SSB 300–2,800 Hz, CW 500 Hz (250/1,000) around the pitch.
```

with:

```markdown
- NCO (double-precision phase) shifts the VFO offset to 0 Hz.
- Multistage polyphase FIR decimation (up to 3 stages, Kaiser-designed, ≥ 60 dB alias rejection), with the largest decimation first where the transition band is wide.
- **WFM:** decimation to 240–480 kHz: the largest D ≤ fs / 240 kHz whose stages are all at most 8 (2.4 MS/s → 10, giving 240 kHz; 3.2 MS/s → 12); rates below 480 kHz run undecimated. Channel filter ±100 kHz by default, never wider than ±0.4 of the channel rate.
- **Narrow modes:** integer decimation to 48–96 kHz, then the fractional resampler to exactly 48 kHz.
- Mode filters: NFM 12.5 kHz (presets 6.25/25), AM 8 kHz (6/10), SSB 300–2,800 Hz, CW 500 Hz (250/1,000) around the pitch.
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown

- **WFM:** polar discriminator arg(x[n]·x*[n−1]), MPX scaled so ±75 kHz deviation = ±1.0. Mono: 15 kHz LPF → de-emphasis (50 µs default, 75 µs option) → 48 kHz.
- **Stereo:** 19 kHz pilot PLL with lock detector; S = LPF₁₅ₖ(MPX · 2cos 2θ); L = M + S, R = M − S. M and S use identical filters so delays match. De-emphasis per channel; blend toward mono on weak pilot or noisy S.
- **RDS:** mix MPX down by 57 kHz (3θ from the pilot PLL when locked, otherwise a free-running Costas loop) → 2.4 kHz LPF → decimate → Costas loop for residual phase → symbol timing recovery at 2,375 baud → biphase decode to 1,187.5 bit/s → differential decode → block sync.
  - Block check: generator g(x) = x¹⁰ + x⁸ + x⁷ + x⁵ + x⁴ + x³ + 1; offset words A `0x0FC`, B `0x198`, C `0x168`, C′ `0x350`, D `0x1B4`. Syndromes are derived from the offset words at start-up.
```

with:

```markdown

- **WFM:** polar discriminator arg(x[n]·x*[n−1]), MPX scaled so ±75 kHz deviation = ±1.0. Mono: 15 kHz LPF → de-emphasis (50 µs default, 75 µs option) → 48 kHz.
- **Stereo:** 19 kHz pilot PLL with lock detector; S = LPF₁₅ₖ(MPX · 2 sin 2ωt), the pilot being sin ωt (ITU-R BS.450); the PLL tracks the pilot as cos θ, so the code multiplies by −2 sin 2θ. L = M + S, R = M − S. M and S use identical filters so delays match. De-emphasis per channel. The output blends toward mono without a locked pilot and on a noisy S: the noise is measured in quadrature to the subcarrier (2 kHz wide, relative to full deviation), with full stereo up to −52 dB and mono from −40 dB.
- **RDS:** mix MPX down by 57 kHz (3θ from the pilot PLL when locked, otherwise a free-running Costas loop) → 2.4 kHz LPF → decimate → Costas loop for residual phase → symbol timing recovery at 2,375 baud → biphase decode to 1,187.5 bit/s → differential decode → block sync.
  - Block check: generator g(x) = x¹⁰ + x⁸ + x⁷ + x⁵ + x⁴ + x³ + 1; offset words A `0x0FC`, B `0x198`, C `0x168`, C′ `0x350`, D `0x1B4`. Syndromes are derived from the offset words at start-up.
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
- **SSB:** shift the passband centre to 0 Hz (USB +1,550 Hz, LSB −1,550 Hz), complex LPF ±1,250 Hz, shift back, take the real part, AGC (attack 2 ms, decay 500 ms, hang 200 ms).
- **CW:** as USB with the passband centred on the pitch (default 700 Hz).
- **Squelch:** channel power in dBFS after the channel filter, 3 dB hysteresis. Auto squelch = noise floor (20th percentile of recent channel power) + 6 dB. Audio is gated with 5 ms ramps. Squelch state also drives the scanner.

### 6.5 Audio output
```

with:

```markdown
- **SSB:** shift the passband centre to 0 Hz (USB +1,550 Hz, LSB −1,550 Hz), complex LPF ±1,250 Hz, shift back, take the real part, AGC (attack 2 ms, decay 500 ms, hang 200 ms).
- **CW:** as USB with the passband centred on the pitch (default 700 Hz).
- **Squelch:** channel power in dBFS after the channel filter, 3 dB hysteresis. Auto squelch = noise floor + 6 dB; the floor is the 20th percentile of a slowly averaged spectrum over the usable part of the stream, scaled to the channel filter's noise bandwidth, so a station that never stops transmitting does not raise it. While the channel takes up more than half of the stream the floor is unknown and the auto squelch stays open. Audio is gated with 5 ms ramps. Squelch state also drives the scanner.

### 6.5 Audio output
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
1. **Own queue:** blocks are tagged with `seq`; every block received before the retune is discarded.
2. **RTL sources (exact):** send frequency, then test mode on, then (5 ms later) test mode off. Commands execute in order, so the counter burst in the stream begins only after the retune has completed. Samples are discarded until the burst ends (detected as ≥ 64 consecutive incrementing bytes, then the first byte breaking the pattern), plus a 1 ms guard. A timeout of 1 s falls back to the measured latency.
3. **Sources without test mode (rsp_tcp, legacy SDRplay driver):** at connect, a latency probe steps IF gain reduction by 20 dB and times the jump in noise power; settle = measured latency + 50 % margin, re-probed every 5 minutes and after reconnects.
4. **NCO-only moves:** scanner channels inside the current tuner window change in DSP and need no synchronisation.
```

with:

```markdown
1. **Own queue:** blocks are tagged with `seq`; every block received before the retune is discarded.
2. **RTL sources (exact):** send frequency, then test mode on, then (5 ms later) test mode off. Commands execute in order, so the counter burst in the stream begins only after the retune has completed. Samples are discarded until the burst ends (detected as ≥ 64 consecutive incrementing bytes, then the first byte breaking the pattern), plus a 1 ms guard. A timeout of 1 s falls back to the measured latency.
3. **Sources without test mode (rsp_tcp, legacy SDRplay driver):** at connect, a latency probe steps the gain from its highest to its lowest setting (on SDRplay, IF gain reduction from minimum to maximum) and times the fall in noise power; settle = measured latency + 50 % margin. Every new connection probes again. The sweep and the scanner re-probe before they start when the last measurement is older than 5 minutes; the listening receiver does not re-probe on a timer, because a probe silences the audio for one to three seconds and a stale latency costs it only a few milliseconds of the previous frequency.
4. **NCO-only moves:** scanner channels inside the current tuner window change in DSP and need no synchronisation.
```

- [ ] **Step 2: Edit the README**

In `README.md`, replace:

```markdown
(WFM stereo + RDS, NFM, AM, SSB, CW) for RTL-SDR and SDRplay receivers.

Status: early development (core engine).

## Layout
```

with:

```markdown
(WFM stereo + RDS, NFM, AM, SSB, CW) for RTL-SDR and SDRplay receivers.

Status: the core engine is complete and tested (sources, demodulators, stereo, RDS, spectrum,
receiver, simulator). The Android app is next.

## Layout
```

- [ ] **Step 3: Run everything one last time**

Run: `tools/test-core.sh`
Expected: `OK (241 tests)`

- [ ] **Step 4: Commit**

```bash
git add docs/ README.md
git commit -F - <<'MSG'
docs: align the spec and README with the engine as built

Spec §4.1, §4.2, §6.3, §6.4 and §7.1 now describe what Plans 1 and 2
built: tune epochs for rate changes, decimation factors with small
stages, the stereo convention and noise blend, the automatic squelch's
noise floor from the spectrum, and the latency probe without a timer.
Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
```

---

## Spec coverage (this plan)

| Spec | Covered by |
|------|-----------|
| §4.1 `demod`, `rds`, `spectrum`, `engine` packages; `SimulatorSource` | Tasks 5–13 (`sweep`, `scan`: Plans 4, 5; `IqRecorder`, `AudioRecorder`: Plan 5) |
| §4.2 `AudioSink` | Task 10 |
| §4.3 bounded queue of about 0.5 s, DSP thread, latest-value spectrum slot | Tasks 9, 12 |
| §4.4 zero allocation; spectrum at the frame rate; measured throughput | Tasks 9, 14 (thread priorities, `PerformanceHintManager`, in-app benchmark: Plan 3) |
| §6.1 DC removal, on by default for RTL sources | Task 12 |
| §6.2 FFT sizes, windows, dBFS, averaging, peak hold, frame rate, up to 4 FFTs per frame, display binning | Task 9 |
| §6.3 channelizer, mode filters and presets | Task 10 |
| §6.4 WFM, stereo with blend, RDS, NFM, AM, SSB, CW, squelch | Tasks 4–8 |
| §6.5 audio output: volume, mute, soft limiter, resampler with drift control | Task 10 |
| §7.1 step 1 (stale blocks discarded) and step 4 (NCO-only moves) | Task 12 |
| §11 DSP overload: oldest blocks dropped and counted | Task 12 (`ReceiverStatus.overruns`; the badge: Plan 3) |
| §12 WFM mono SINAD ≥ 40 dB; stereo ≤ −25 dB | Task 6 |
| §12 RDS exact text end to end, block-error-rate pass line, bursts ≤ 5 bits corrected | Tasks 5, 7 |
| §12 NFM / AM SINAD ≥ 30 dB; SSB / CW opposite sideband ≥ 40 dB, CW at the pitch | Task 8 |
| §12 squelch hysteresis | Task 8 |
| §12 throughput ≥ 3× real time | Task 14 |
| §12 simulator with FM (stereo + RDS), NFM bursts, AM, SSB, CW | Tasks 11, 13 |
| §14 milestone 1 complete | After Task 15 |

## Left for later plans on purpose

- **Plan 3 (Android):** `AndroidAudioSink` (it must accept one or two channels and fill underruns with silence, §11); audio-priority threads and `PerformanceHintManager` around the DSP thread; the in-app benchmark, which runs `ReceiverChain` per mode; the overload badge from `ReceiverStatus.overruns` and `blocks`; reconnect with back-off, which makes a new source and a new `Receiver` from `receiver.settings`; a de-emphasis switch for NFM if wanted (`NfmDemod` has the parameter; the receiver always uses it).
- **Plan 4 (sweep):** the sweep needs the blocks while the receiver is paused (§7): an exclusive block consumer on the `Receiver`. Re-probe the retune latency before a sweep when the last probe is older than five minutes (decision 4).
- **Plan 5 (scanner, recording):** scanning inside a tuner window uses `Receiver.update` (NCO-only moves) and `ReceiverStatus.squelchOpen`, `tuned` and `noiseFloorDb`; audio recording taps the chain before the volume control (§9).

## Measured while writing this plan

On the development machine (2 cores, OpenJDK 21), for orientation; none of these numbers is asserted beyond the limits in the tests.

| What | Result |
|---|---|
| WFM stereo + RDS + 4,096-point spectrum at 2.4 MS/s | 9–15× real time (limit: 3×) |
| Allocation per block once the path runs, every mode | 0 bytes |
| WFM mono SINAD, 1 kHz at full deviation | above 100 dB at 240 kHz; 46 dB through the channel filter at 960 kHz (limit: 40 dB) |
| Stereo separation | above 70 dB at 30 dB carrier-to-noise ratio; 31 dB through the ±100 kHz channel filter (limit: 25 dB) |
| Stereo after tuning in | 0.27 s |
| RDS blocks lost at 20 / 16 / 14 / 12 dB carrier-to-noise ratio in 240 kHz | 0 % / 0 % / 1.5 % / 13 % (pass line: 0.5 / 2 / 5 / 25 %) |
| NFM SINAD | 55 dB (limit: 30 dB) |
| SSB opposite sideband; CW neighbour 600 Hz away | −79 dB; −74 dB (limit: −40 dB) |
| Noise-floor estimate | 0.2–0.5 dB low once settled, up to 3 dB low on the first frame |
| Simulated band, default stations | 11–14× real time at 960 kS/s (one FM station in view), 3× at 2.4 MS/s (two in view) |
