# SpectraSDR Plan 1 — Core Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the tested, Android-free foundation of the SpectraSDR signal engine: test harness, DSP building blocks, IQ sources (rtl_tcp, rsp_tcp extended, IQ WAV files) and exact retune synchronisation.

**Architecture:** A pure-Kotlin `core` module (standard library only) holds every class. Sources run their own reader thread and hand pooled `IqBlock`s to a `SourceListener`; DSP blocks are allocation-free classes working on interleaved complex `FloatArray`s. Retune boundaries are found in the byte stream by RTL2832U test-mode markers or a measured latency, so every block carries a trustworthy tune epoch.

**Tech Stack:** Kotlin 2.1.21 (JVM target 17), JUnit 4.13.2 + hamcrest-core 1.3, bash test runner (`tools/test-core.sh`) using kotlinc directly.

**Spec:** `docs/superpowers/specs/2026-11-12-spectrasdr-design.md` (sections cited as §n).

## Global Constraints

- License GPL-3.0-or-later; every Kotlin file starts with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- `:core` uses the Kotlin standard library only (spec D6); tests use JUnit 4.13.2 and hamcrest-core 1.3 only.
- Kotlin 2.1.21, `-jvm-target 17`.
- Package root `io.github.radhwanalhajjaj.spectrasdr.core`. Source directories omit the common root (Kotlin coding conventions): `core/src/main/kotlin/dsp/Fft.kt` declares `package io.github.radhwanalhajjaj.spectrasdr.core.dsp`.
- Complex samples are interleaved `FloatArray`s (I0, Q0, I1, Q1, …). Counts are complex samples unless a name says bytes or frames.
- Signal-path code allocates nothing per sample (§4.4): preallocated arrays, no boxing, no collections, iterators or non-inline lambdas inside per-sample loops.
- Run tests with `tools/test-core.sh [fully.qualified.TestClass …]` from the repository root. The same sources will later run under Gradle in CI (Plan 3).
- Every commit message ends with these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
  ```

## Where this plan sits

| Plan | Scope | Spec milestone |
|------|-------|----------------|
| **1 (this)** | Harness, DSP blocks, sources and protocols, retune sync, WAV/IQ files | M1 (part 1) |
| 2 | Signal generators, demodulators, stereo, RDS, spectrum analyzer, simulator source, `Receiver`, throughput test | M1 (part 2) |
| 3 | Gradle + Android app, CI and emulator tests, driver hand-off, receiver screen, GL waterfall, diagnostics | M2 |
| 4 | Sweep analyzer | M3 |
| 5 | Scanner and recording | M4 |
| 6 | Polish and release | M5 |

Left for later plans on purpose: `SpectrumAnalyzer` and the DSP thread (Plan 2); the rsp_tcp link test and automatic rate step-down (§5.3) and `PerformanceHintManager` (Plan 3); 2 GiB recording split (Plan 5).

## File Structure

| File | Responsibility |
|------|----------------|
| `tools/test-core.sh` | Downloads kotlinc 2.1.21 once, compiles `core` main + test sources, runs JUnit |
| `LICENSE`, `README.md`, `.gitignore` | Project basics |
| `core/src/main/kotlin/CoreInfo.kt` | Engine name and version |
| `core/src/main/kotlin/source/SampleFormat.kt` | Wire formats (U8, S16LE) |
| `core/src/main/kotlin/source/SampleConverter.kt` | Wire bytes → interleaved floats |
| `core/src/main/kotlin/source/IqBlock.kt` | `IqBlock` and `BlockPool` |
| `core/src/main/kotlin/source/BlockQueue.kt` | Bounded drop-oldest queue between threads |
| `core/src/main/kotlin/source/IqSource.kt` | Source API: `IqSource`, `SourceListener`, `SourceInfo`, gain/option/error types |
| `core/src/main/kotlin/source/WavIqFileSource.kt` | IQ WAV playback source |
| `core/src/main/kotlin/source/rtltcp/RtlTcpProtocol.kt` | rtl_tcp header, commands, tuner gain tables, byte helpers |
| `core/src/main/kotlin/source/rtltcp/RspCapabilities.kt` | rsp_tcp `RSP0` structure and RSP models |
| `core/src/main/kotlin/source/rtltcp/RetuneSync.kt` | `RetuneSync` base, `TimedCommand`, `RetuneSyncMode`, `ImmediateSync` |
| `core/src/main/kotlin/source/rtltcp/CounterMarkerSync.kt` | Exact retune boundaries from test-mode bursts |
| `core/src/main/kotlin/source/rtltcp/LatencySync.kt` | `LatencySync` and `PowerStepDetector` |
| `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt` | `RtlTcpConfig` and the client source |
| `core/src/main/kotlin/dsp/Fft.kt`, `Window.kt` | FFT and analysis windows |
| `core/src/main/kotlin/dsp/Nco.kt`, `FirDesign.kt` | Frequency shifter and Kaiser FIR design |
| `core/src/main/kotlin/dsp/FirDecimator.kt`, `DecimatorChain.kt` | Polyphase decimation |
| `core/src/main/kotlin/dsp/FractionalResampler.kt`, `DriftController.kt` | Audio resampling and clock-drift control |
| `core/src/main/kotlin/dsp/DcBlocker.kt`, `Agc.kt`, `Deemphasis.kt`, `Pll.kt` | Small DSP blocks |
| `core/src/main/kotlin/record/WavWriter.kt`, `WavReader.kt`, `RecordingNames.kt` | Crash-safe WAV I/O and file naming |
| `core/src/test/kotlin/testutil/Signals.kt` | Test signal and measurement helpers |
| `core/src/test/kotlin/source/CollectingListener.kt` | Test listener collecting blocks per epoch |
| `core/src/test/kotlin/source/rtltcp/FakeRtlTcpServer.kt` | In-process rtl_tcp/rsp_tcp server with a synthetic band, pipeline delay and test mode |
| `core/src/test/kotlin/**/…Test.kt` | One test class per unit |

---

### Task 1: Repository scaffold and core test runner

**Files:**
- Create: `LICENSE`, `README.md`, `.gitignore`, `tools/test-core.sh`
- Create: `core/src/main/kotlin/CoreInfo.kt`
- Test: `core/src/test/kotlin/CoreInfoTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `tools/test-core.sh` (runs all `*Test` classes, or the FQCNs given as arguments); `object CoreInfo { const val NAME: String; const val VERSION: String }`.

- [ ] **Step 1: Add the license, README and ignore file**

```bash
cd /home/claude/spectrasdr
cp /usr/share/common-licenses/GPL-3 LICENSE
sha256sum LICENSE   # expect 3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986
```

`README.md`:

```markdown
# SpectraSDR

Open-source Android SDR receiver: spectrum and waterfall, wideband sweep analyzer, and listening
(WFM stereo + RDS, NFM, AM, SSB, CW) for RTL-SDR and SDRplay receivers.

Status: early development (core engine).

## Layout

- `core/` — pure-Kotlin signal engine with no Android dependencies
- `docs/superpowers/specs/` — design spec
- `docs/superpowers/plans/` — implementation plans
- `tools/test-core.sh` — runs the core unit tests with kotlinc and JUnit 4, without Gradle

## License

GPL-3.0-or-later. See `LICENSE`.
```

`.gitignore`:

```gitignore
# Build output
build/
.gradle/
*.apk
*.aab
# IDE and local files
.idea/
*.iml
local.properties
captures/
.DS_Store
```

- [ ] **Step 2: Write the test runner**

`tools/test-core.sh`:

```bash
#!/usr/bin/env bash
# Compiles and runs the :core unit tests with kotlinc + JUnit 4, without Gradle.
# Used on machines where Gradle cannot download its plugins.
# Usage: tools/test-core.sh [fully.qualified.TestClass ...]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_VERSION="2.1.21"
CACHE="${SPECTRASDR_CACHE:-$HOME/.cache/spectrasdr}"
KHOME="$CACHE/kotlinc-$KOTLIN_VERSION/kotlinc"

if [ ! -x "$KHOME/bin/kotlinc" ]; then
  mkdir -p "$CACHE/kotlinc-$KOTLIN_VERSION"
  curl -fsSL -o "$CACHE/kotlinc-$KOTLIN_VERSION.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  unzip -q -o "$CACHE/kotlinc-$KOTLIN_VERSION.zip" -d "$CACHE/kotlinc-$KOTLIN_VERSION"
  rm -f "$CACHE/kotlinc-$KOTLIN_VERSION.zip"
fi

JUNIT="${JUNIT_JAR:-$( (ls /opt/gradle*/lib/junit-4*.jar 2>/dev/null || true) | head -n1)}"
HAMCREST="${HAMCREST_JAR:-$( (ls /opt/gradle*/lib/hamcrest-core-*.jar 2>/dev/null || true) | head -n1)}"
if [ -z "$JUNIT" ] || [ -z "$HAMCREST" ]; then
  echo "Set JUNIT_JAR and HAMCREST_JAR to junit-4.13.2.jar and hamcrest-core-1.3.jar" >&2
  exit 2
fi

OUT="$ROOT/build/core-local"
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"
STDLIB="$KHOME/lib/kotlin-stdlib.jar"

mapfile -t MAIN_SRC < <(find "$ROOT/core/src/main/kotlin" -name '*.kt' 2>/dev/null | sort)
mapfile -t TEST_SRC < <(find "$ROOT/core/src/test/kotlin" -name '*.kt' 2>/dev/null | sort)

if [ "${#MAIN_SRC[@]}" -gt 0 ]; then
  "$KHOME/bin/kotlinc" -nowarn -jvm-target 17 -d "$OUT/main" "${MAIN_SRC[@]}"
fi
"$KHOME/bin/kotlinc" -nowarn -jvm-target 17 -cp "$OUT/main:$JUNIT:$HAMCREST" -d "$OUT/test" "${TEST_SRC[@]}"

if [ "$#" -gt 0 ]; then
  CLASSES=("$@")
else
  mapfile -t CLASSES < <(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' \
    | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
fi
java -Xss8m -cp "$OUT/main:$OUT/test:$JUNIT:$HAMCREST:$STDLIB" org.junit.runner.JUnitCore "${CLASSES[@]}"
```

```bash
chmod +x tools/test-core.sh
```

- [ ] **Step 3: Write the failing test**

`core/src/test/kotlin/CoreInfoTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreInfoTest {
    @Test
    fun nameAndVersion() {
        assertEquals("SpectraSDR", CoreInfo.NAME)
        assertEquals("0.1.0", CoreInfo.VERSION)
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `tools/test-core.sh`
Expected: FAIL — kotlinc reports `unresolved reference 'CoreInfo'`.

- [ ] **Step 5: Implement**

`core/src/main/kotlin/CoreInfo.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core

/** Identity of the core engine, shown in diagnostics and written into recordings. */
object CoreInfo {
    const val NAME = "SpectraSDR"
    const val VERSION = "0.1.0"
}
```

- [ ] **Step 6: Run it and watch it pass**

Run: `tools/test-core.sh`
Expected: `OK (1 test)`

- [ ] **Step 7: Commit**

```bash
git add LICENSE README.md .gitignore tools/test-core.sh core/
git commit -F - <<'EOF'
chore: scaffold repository and core test runner

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 2: Sample conversion, blocks, pool and queue

**Files:**
- Create: `core/src/main/kotlin/source/SampleFormat.kt`, `SampleConverter.kt`, `IqBlock.kt`, `BlockQueue.kt`
- Test: `core/src/test/kotlin/source/SampleConverterTest.kt`, `core/src/test/kotlin/source/BlockQueueTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class SampleFormat(val bytesPerComplex: Int) { U8(2), S16LE(4) }`
  - `object SampleConverter { fun convert(format: SampleFormat, src: ByteArray, srcOffset: Int, byteCount: Int, dst: FloatArray, dstComplexOffset: Int, maxComplex: Int): Int }` — returns complex samples written; never consumes a partial sample.
  - `class IqBlock` with `val capacity: Int`, `val samples: FloatArray` (2 × capacity), `var length: Int`, `var seq: Long`, `var centerHz: Long`, `var sampleRate: Int`, `var tuneEpoch: Int`, `fun release()`, `IqBlock.standalone(capacity)`.
  - `class BlockPool(val blockCapacity: Int, preallocate: Int = 0)` with `fun obtain(): IqBlock`, `val allocated: Int`, `val freeCount: Int`; releasing a block twice throws `IllegalStateException`.
  - `class BlockQueue(val capacity: Int)` with `fun offer(block: IqBlock)` (drops and releases the oldest when full), `fun take(timeoutMs: Long): IqBlock?`, `fun clear()`, `val size: Int`, `val overruns: Long`, `val offered: Long`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/source/SampleConverterTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleConverterTest {
    @Test
    fun u8MapsToSymmetricFloats() {
        val src = byteArrayOf(0, 255.toByte(), 127, 128.toByte())
        val dst = FloatArray(4)
        assertEquals(2, SampleConverter.convert(SampleFormat.U8, src, 0, src.size, dst, 0, 2))
        assertEquals(-1f, dst[0], 1e-6f)
        assertEquals(1f, dst[1], 1e-6f)
        assertEquals(-0.5f / 127.5f, dst[2], 1e-6f)
        assertEquals(0.5f / 127.5f, dst[3], 1e-6f)
    }

    @Test
    fun s16IsLittleEndianSigned() {
        // I = 32767, Q = -32768, then I = 1, Q = -1
        val src = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val dst = FloatArray(4)
        assertEquals(2, SampleConverter.convert(SampleFormat.S16LE, src, 0, src.size, dst, 0, 10))
        assertEquals(32767f / 32768f, dst[0], 1e-7f)
        assertEquals(-1f, dst[1], 1e-7f)
        assertEquals(1f / 32768f, dst[2], 1e-9f)
        assertEquals(-1f / 32768f, dst[3], 1e-9f)
    }

    @Test
    fun trailingPartialSampleIsLeftForTheCaller() {
        val src = ByteArray(7) { 127 }
        val dst = FloatArray(8)
        assertEquals(1, SampleConverter.convert(SampleFormat.S16LE, src, 0, 7, dst, 0, 4))
        assertEquals(3, SampleConverter.convert(SampleFormat.U8, src, 0, 7, dst, 0, 4))
    }

    @Test
    fun respectsOffsetsAndMaximum() {
        val src = ByteArray(10) { (it * 20).toByte() }
        val dst = FloatArray(10) { 9f }
        assertEquals(2, SampleConverter.convert(SampleFormat.U8, src, 2, 8, dst, 1, 2))
        assertEquals(9f, dst[0], 0f)
        assertEquals(9f, dst[1], 0f)
        assertEquals((40 - 127.5f) / 127.5f, dst[2], 1e-6f)
        assertEquals((100 - 127.5f) / 127.5f, dst[5], 1e-6f)
        assertEquals(9f, dst[6], 0f)
    }
}
```

`core/src/test/kotlin/source/BlockQueueTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BlockQueueTest {
    @Test
    fun deliversInFifoOrder() {
        val pool = BlockPool(4)
        val q = BlockQueue(3)
        q.offer(pool.obtain().apply { seq = 1 })
        q.offer(pool.obtain().apply { seq = 2 })
        assertEquals(1L, q.take(10)!!.seq)
        assertEquals(2L, q.take(10)!!.seq)
        assertNull(q.take(10))
    }

    @Test
    fun dropsOldestWhenFullAndReturnsItToThePool() {
        val pool = BlockPool(4)
        val q = BlockQueue(2)
        for (i in 0 until 3) q.offer(pool.obtain().apply { seq = i.toLong() })
        assertEquals(1L, q.overruns)
        assertEquals(1, pool.freeCount)
        assertEquals(1L, q.take(10)!!.seq)
        assertEquals(2L, q.take(10)!!.seq)
    }

    @Test
    fun takeWaitsForTheProducer() {
        val pool = BlockPool(4)
        val q = BlockQueue(2)
        val producer = Thread { Thread.sleep(50); q.offer(pool.obtain().apply { seq = 7 }) }
        producer.start()
        val got = q.take(2_000)
        producer.join()
        assertEquals(7L, got!!.seq)
    }

    @Test
    fun poolReusesReleasedBlocks() {
        val pool = BlockPool(8, preallocate = 1)
        val b = pool.obtain()
        b.length = 5
        b.release()
        val again = pool.obtain()
        assertSame(b, again)
        assertEquals(0, again.length)
        assertEquals(1, pool.allocated)
    }

    @Test(expected = IllegalStateException::class)
    fun releasingTwiceIsAnError() {
        val b = BlockPool(8).obtain()
        b.release()
        b.release()
    }

    @Test
    fun clearReleasesEverything() {
        val pool = BlockPool(4)
        val q = BlockQueue(4)
        repeat(3) { q.offer(pool.obtain()) }
        q.clear()
        assertEquals(0, q.size)
        assertEquals(3, pool.freeCount)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.SampleConverterTest io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueueTest`
Expected: FAIL — `unresolved reference 'SampleConverter'`, `'BlockPool'`, `'BlockQueue'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/source/SampleFormat.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/** Wire format of interleaved I/Q samples. */
enum class SampleFormat(val bytesPerComplex: Int) {
    /** 8-bit unsigned I then Q, centred on 127.5 (RTL-SDR, rtl_tcp). */
    U8(2),

    /** 16-bit signed little-endian I then Q (rsp_tcp extended mode). */
    S16LE(4),
}
```

`core/src/main/kotlin/source/SampleConverter.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/** Converts wire samples to interleaved float I/Q in [-1, 1]. */
object SampleConverter {
    private val u8Table = FloatArray(256) { (it - 127.5f) / 127.5f }

    /**
     * Converts whole complex samples from [src] (starting at [srcOffset], at most [byteCount] bytes)
     * into [dst] starting at complex index [dstComplexOffset], writing at most [maxComplex] samples.
     * Returns the number written. Trailing bytes of an incomplete sample are left for the caller.
     */
    fun convert(
        format: SampleFormat,
        src: ByteArray,
        srcOffset: Int,
        byteCount: Int,
        dst: FloatArray,
        dstComplexOffset: Int,
        maxComplex: Int,
    ): Int {
        val n = minOf(byteCount / format.bytesPerComplex, maxComplex)
        var s = srcOffset
        var d = dstComplexOffset * 2
        when (format) {
            SampleFormat.U8 -> repeat(n) {
                dst[d] = u8Table[src[s].toInt() and 0xFF]
                dst[d + 1] = u8Table[src[s + 1].toInt() and 0xFF]
                s += 2
                d += 2
            }
            SampleFormat.S16LE -> repeat(n) {
                val i = (src[s].toInt() and 0xFF) or (src[s + 1].toInt() shl 8)
                val q = (src[s + 2].toInt() and 0xFF) or (src[s + 3].toInt() shl 8)
                dst[d] = i.toShort() / 32768f
                dst[d + 1] = q.toShort() / 32768f
                s += 4
                d += 2
            }
        }
        return n
    }
}
```

`core/src/main/kotlin/source/IqBlock.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/**
 * A run of complex samples (interleaved I, Q floats). Blocks come from a [BlockPool] and must be
 * handed back with [release] once consumed.
 */
class IqBlock internal constructor(val capacity: Int, private val pool: BlockPool?) {
    /** Interleaved I, Q; only the first [length] complex samples are valid. */
    val samples = FloatArray(capacity * 2)

    /** Number of valid complex samples. */
    var length = 0

    /** Increases by one per block from the same source. */
    var seq = 0L

    /** Tuner centre frequency of these samples, in Hz. */
    var centerHz = 0L

    var sampleRate = 0

    /** Tune epoch: blocks with epoch ≥ E are at the frequency requested by the retune that returned E. */
    var tuneEpoch = 0

    internal var inPool = false

    /** Returns the block to its pool. Releasing twice is a programming error. */
    fun release() {
        pool?.recycle(this)
    }

    companion object {
        /** A block that belongs to no pool (tests, one-off buffers). */
        fun standalone(capacity: Int) = IqBlock(capacity, null)
    }
}

/** Thread-safe pool of equally sized blocks; allocates a new block when empty. */
class BlockPool(val blockCapacity: Int, preallocate: Int = 0) {
    private val free = ArrayDeque<IqBlock>()
    private val lock = Any()

    @Volatile
    var allocated = 0
        private set

    init {
        repeat(preallocate) {
            free.addLast(IqBlock(blockCapacity, this).also { it.inPool = true })
            allocated++
        }
    }

    fun obtain(): IqBlock {
        val block = synchronized(lock) {
            free.removeLastOrNull() ?: IqBlock(blockCapacity, this).also { allocated++ }
        }
        block.inPool = false
        block.length = 0
        return block
    }

    internal fun recycle(block: IqBlock) {
        synchronized(lock) {
            check(!block.inPool) { "IqBlock released twice" }
            block.inPool = true
            free.addLast(block)
        }
    }

    val freeCount: Int
        get() = synchronized(lock) { free.size }
}
```

`core/src/main/kotlin/source/BlockQueue.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded FIFO between a source thread and the DSP thread. When full, the oldest block is released
 * back to its pool and [overruns] increases, so a slow consumer never stalls the source (§4.3).
 */
class BlockQueue(val capacity: Int) {
    private val items = ArrayDeque<IqBlock>(capacity)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    @Volatile
    var overruns = 0L
        private set

    @Volatile
    var offered = 0L
        private set

    fun offer(block: IqBlock) {
        var dropped: IqBlock? = null
        lock.withLock {
            if (items.size >= capacity) {
                dropped = items.removeFirst()
                overruns++
            }
            items.addLast(block)
            offered++
            notEmpty.signal()
        }
        dropped?.release()
    }

    /** Waits up to [timeoutMs] for a block; returns null on timeout. */
    fun take(timeoutMs: Long): IqBlock? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (items.isEmpty()) {
            if (nanos <= 0L) return null
            nanos = notEmpty.awaitNanos(nanos)
        }
        items.removeFirst()
    }

    /** Releases every queued block. */
    fun clear() {
        val drained = lock.withLock { items.toList().also { items.clear() } }
        drained.forEach { it.release() }
    }

    val size: Int
        get() = lock.withLock { items.size }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.SampleConverterTest io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueueTest`
Expected: `OK (10 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): sample conversion, pooled IQ blocks and drop-oldest queue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 3: FFT, analysis windows and test signal helpers

**Files:**
- Create: `core/src/main/kotlin/dsp/Fft.kt`, `core/src/main/kotlin/dsp/Window.kt`
- Create: `core/src/test/kotlin/testutil/Signals.kt`
- Test: `core/src/test/kotlin/dsp/FftTest.kt`, `core/src/test/kotlin/dsp/WindowTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class Fft(val size: Int)` with `fun transform(data: FloatArray)` — in-place forward FFT of `size` interleaved complex samples, no scaling.
  - `enum class WindowType { HANN, BLACKMAN_HARRIS }`; `object Windows { fun create(type: WindowType, n: Int): FloatArray; fun sum(window: FloatArray): Double }` — periodic windows.
  - Test helpers `object Signals` (package `io.github.radhwanalhajjaj.spectrasdr.core.testutil`): `complexTone(n, freqHz, rate, amplitude = 1.0, phase = 0.0): FloatArray`, `realTone(n, freqHz, rate, amplitude = 1.0): FloatArray`, `complexToneDb(x, freqHz, rate, from = 0, to = x.size / 2): Double` (amplitude dB of a complex tone), `complexPowerDb(x, from, to): Double`, `peak(x, from, to): Float`, `zeroCrossingFrequency(x, rate, from, to, channels = 1, channel = 0): Double`.

- [ ] **Step 1: Write the test helpers and failing tests**

`core/src/test/kotlin/testutil/Signals.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.testutil

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/** Signal generation and measurement helpers shared by the core tests. */
object Signals {
    /** Interleaved complex tone amplitude · exp(j(2π·f·n/rate + phase)). */
    fun complexTone(n: Int, freqHz: Double, rate: Double, amplitude: Double = 1.0, phase: Double = 0.0): FloatArray {
        val x = FloatArray(2 * n)
        for (k in 0 until n) {
            val a = 2 * PI * freqHz * k / rate + phase
            x[2 * k] = (amplitude * cos(a)).toFloat()
            x[2 * k + 1] = (amplitude * sin(a)).toFloat()
        }
        return x
    }

    fun realTone(n: Int, freqHz: Double, rate: Double, amplitude: Double = 1.0): FloatArray =
        FloatArray(n) { (amplitude * sin(2 * PI * freqHz * it / rate)).toFloat() }

    /** Amplitude in dB (re. 1.0) of the complex tone at [freqHz] in [x], by single-bin DFT over [from, to). */
    fun complexToneDb(x: FloatArray, freqHz: Double, rate: Double, from: Int = 0, to: Int = x.size / 2): Double {
        var re = 0.0
        var im = 0.0
        for (k in from until to) {
            val a = -2 * PI * freqHz * k / rate
            val c = cos(a)
            val s = sin(a)
            re += x[2 * k] * c - x[2 * k + 1] * s
            im += x[2 * k] * s + x[2 * k + 1] * c
        }
        return 20 * log10(hypot(re, im) / (to - from) + 1e-15)
    }

    /** Mean power in dB of complex samples [from, to). */
    fun complexPowerDb(x: FloatArray, from: Int = 0, to: Int = x.size / 2): Double {
        var p = 0.0
        for (k in from until to) p += x[2 * k] * x[2 * k] + x[2 * k + 1] * x[2 * k + 1]
        return 10 * log10(p / (to - from) + 1e-30)
    }

    fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var p = 0f
        for (i in from until to) p = maxOf(p, abs(x[i]))
        return p
    }

    /** Frequency of a real tone from interpolated rising zero crossings in frames [from, to). */
    fun zeroCrossingFrequency(x: FloatArray, rate: Double, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        var first = -1.0
        var last = -1.0
        var count = 0
        for (i in from + 1 until to) {
            val a = x[(i - 1) * channels + channel]
            val b = x[i * channels + channel]
            if (a < 0 && b >= 0) {
                val t = (i - 1) + a / (a - b).toDouble()
                if (first < 0) first = t else { last = t; count++ }
            }
        }
        return count / ((last - first) / rate)
    }
}
```

`core/src/test/kotlin/dsp/FftTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class FftTest {
    private fun naiveDft(x: FloatArray, n: Int): DoubleArray {
        val out = DoubleArray(2 * n)
        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                re += x[2 * t] * cos(a) - x[2 * t + 1] * sin(a)
                im += x[2 * t] * sin(a) + x[2 * t + 1] * cos(a)
            }
            out[2 * k] = re
            out[2 * k + 1] = im
        }
        return out
    }

    @Test
    fun matchesNaiveDftForAllSizes() {
        val rnd = Random(42)
        var size = 8
        while (size <= 4096) {
            val x = FloatArray(2 * size) { rnd.nextFloat() * 2 - 1 }
            val expected = naiveDft(x, size)
            val y = x.copyOf()
            Fft(size).transform(y)
            var maxErr = 0.0
            var maxMag = 0.0
            for (i in 0 until 2 * size) {
                maxErr = max(maxErr, abs(y[i] - expected[i]))
                maxMag = max(maxMag, abs(expected[i]))
            }
            assertTrue("size $size relative error ${maxErr / maxMag}", maxErr / maxMag < 1e-4)
            size *= 2
        }
    }

    @Test
    fun complexToneLandsInItsBin() {
        val n = 1024
        val x = Signals.complexTone(n, 100.0, n.toDouble())
        Fft(n).transform(x)
        fun mag(k: Int) = hypot(x[2 * k].toDouble(), x[2 * k + 1].toDouble())
        assertEquals(n.toDouble(), mag(100), 1e-2)
        assertTrue(mag(101) < 1e-2)
        assertTrue(mag(n - 100) < 1e-2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSizesThatAreNotPowersOfTwo() {
        Fft(1000)
    }
}
```

`core/src/test/kotlin/dsp/WindowTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

class WindowTest {
    @Test
    fun hannIsPeriodic() {
        val w = Windows.create(WindowType.HANN, 8)
        assertEquals(0f, w[0], 1e-7f)
        assertEquals(1f, w[4], 1e-6f)
        assertEquals(w[1], w[7], 1e-6f)
    }

    @Test
    fun blackmanHarrisGainAndPeak() {
        val n = 4096
        val w = Windows.create(WindowType.BLACKMAN_HARRIS, n)
        assertEquals(0.35875, Windows.sum(w) / n, 1e-4)
        assertEquals(1.0, w[n / 2].toDouble(), 1e-4)
    }

    @Test
    fun blackmanHarrisLeakageIsBelowMinus90dB() {
        val n = 1024
        val w = Windows.create(WindowType.BLACKMAN_HARRIS, n)
        val x = FloatArray(2 * n)
        for (t in 0 until n) {
            val a = 2 * PI * 100.5 * t / n
            x[2 * t] = (cos(a) * w[t]).toFloat()
            x[2 * t + 1] = (sin(a) * w[t]).toFloat()
        }
        Fft(n).transform(x)
        fun db(k: Int) = 20 * log10(hypot(x[2 * k].toDouble(), x[2 * k + 1].toDouble()) + 1e-30)
        val peak = max(db(100), db(101))
        assertTrue(db(109) - peak < -90)
        assertTrue(db(92) - peak < -90)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.FftTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowTest`
Expected: FAIL — `unresolved reference 'Fft'`, `'Windows'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/dsp/Fft.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT on interleaved I, Q arrays. Forward transform, unscaled. */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two >= 2" }
    }

    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { (-sin(2.0 * PI * it / size)).toFloat() }
    private val bitReverse = IntArray(size).also { rev ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Transforms data[0 until 2 * size] in place. */
    fun transform(data: FloatArray) {
        require(data.size >= 2 * size) { "data holds fewer than $size complex samples" }
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val re = data[2 * i]
                val im = data[2 * i + 1]
                data[2 * i] = data[2 * j]
                data[2 * i + 1] = data[2 * j + 1]
                data[2 * j] = re
                data[2 * j + 1] = im
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val a = 2 * (start + j)
                    val b = 2 * (start + j + half)
                    val br = data[b]
                    val bi = data[b + 1]
                    val tr = br * wr - bi * wi
                    val ti = br * wi + bi * wr
                    data[b] = data[a] - tr
                    data[b + 1] = data[a + 1] - ti
                    data[a] += tr
                    data[a + 1] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
```

`core/src/main/kotlin/dsp/Window.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.cos

enum class WindowType { HANN, BLACKMAN_HARRIS }

object Windows {
    /** Periodic (DFT-even) window of length [n] for spectral analysis (§6.2). */
    fun create(type: WindowType, n: Int): FloatArray = FloatArray(n) { i ->
        val x = 2.0 * PI * i / n
        when (type) {
            WindowType.HANN -> 0.5 - 0.5 * cos(x)
            WindowType.BLACKMAN_HARRIS ->
                0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
        }.toFloat()
    }

    /** Sum of the window values; dBFS = 10·log10(|X|²) − 20·log10(sum) (§6.2). */
    fun sum(window: FloatArray): Double {
        var s = 0.0
        for (v in window) s += v
        return s
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.FftTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.WindowTest`
Expected: `OK (6 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): radix-2 FFT, analysis windows and test signal helpers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 4: NCO and Kaiser FIR design

**Files:**
- Create: `core/src/main/kotlin/dsp/Nco.kt`, `core/src/main/kotlin/dsp/FirDesign.kt`
- Test: `core/src/test/kotlin/dsp/NcoTest.kt`, `core/src/test/kotlin/dsp/FirDesignTest.kt`

**Interfaces:**
- Consumes: `Fft` (Task 3), `Signals` (Task 3, tests only).
- Produces:
  - `class Nco(sampleRate: Double)` with `var frequencyHz: Double`, `fun mix(data: FloatArray, offset: Int, count: Int)` (multiplies by exp(j·2π·f·n/fs), phase continuous across calls), `fun reset()`.
  - `object FirDesign` with `kaiserBeta(attenuationDb): Double`, `kaiserTaps(transitionHz, sampleRate, attenuationDb): Int` (odd, ≥ 3), `lowPass(numTaps, cutoffHz, sampleRate, beta): FloatArray` (odd, symmetric, unity DC gain), `lowPassKaiser(passbandHz, stopbandHz, sampleRate, attenuationDb): FloatArray`, `responseDb(taps, freqHz, sampleRate): Double`, `besselI0(x): Double`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/dsp/NcoTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot

class NcoTest {
    private fun dc(n: Int) = FloatArray(2 * n) { if (it % 2 == 0) 1f else 0f }

    @Test
    fun shiftsDcToTheRequestedBin() {
        val fs = 48_000.0
        val n = 4096
        val data = dc(n)
        Nco(fs).apply { frequencyHz = fs * 64 / n }.mix(data, 0, n)
        Fft(n).transform(data)
        assertEquals(n.toDouble(), hypot(data[128].toDouble(), data[129].toDouble()), n * 1e-4)
    }

    @Test
    fun amplitudeStaysAtOneOverMillionsOfSamples() {
        val nco = Nco(2_400_000.0).apply { frequencyHz = 123_456.7 }
        val block = 16_384
        val data = FloatArray(2 * block)
        repeat(200) {
            for (i in 0 until block) { data[2 * i] = 1f; data[2 * i + 1] = 0f }
            nco.mix(data, 0, block)
        }
        assertEquals(1.0, hypot(data[2 * block - 2].toDouble(), data[2 * block - 1].toDouble()), 1e-6)
    }

    @Test
    fun negativeFrequencyUndoesPositive() {
        val fs = 1_000_000.0
        val n = 1000
        val data = FloatArray(2 * n) { if (it % 2 == 0) 0.5f else -0.25f }
        val original = data.copyOf()
        Nco(fs).apply { frequencyHz = 70_000.0 }.mix(data, 0, n)
        Nco(fs).apply { frequencyHz = -70_000.0 }.mix(data, 0, n)
        for (i in data.indices) assertEquals(original[i], data[i], 1e-5f)
    }

    @Test
    fun phaseIsContinuousAcrossCalls() {
        val fs = 48_000.0
        val whole = dc(100)
        val split = dc(100)
        Nco(fs).apply { frequencyHz = 1_000.0 }.mix(whole, 0, 100)
        val nco = Nco(fs).apply { frequencyHz = 1_000.0 }
        nco.mix(split, 0, 37)
        nco.mix(split, 37, 63)
        for (i in whole.indices) assertEquals(whole[i], split[i], 1e-6f)
    }
}
```

`core/src/test/kotlin/dsp/FirDesignTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirDesignTest {
    @Test
    fun meetsPassbandAndStopband() {
        val fs = 240_000.0
        val taps = FirDesign.lowPassKaiser(15_000.0, 19_000.0, fs, 66.0)
        assertTrue(taps.size % 2 == 1)
        for (f in listOf(0.0, 5_000.0, 10_000.0, 15_000.0)) {
            assertEquals("passband at $f Hz", 0.0, FirDesign.responseDb(taps, f, fs), 0.5)
        }
        var f = 19_000.0
        while (f < fs / 2) {
            assertTrue("stopband at $f Hz", FirDesign.responseDb(taps, f, fs) <= -60.0)
            f += 250.0
        }
    }

    @Test
    fun unityDcGainAndSymmetry() {
        val taps = FirDesign.lowPass(31, 1_000.0, 48_000.0, 6.0)
        assertEquals(1.0, taps.sum().toDouble(), 1e-5)
        for (i in 0 until 15) assertEquals(taps[i], taps[30 - i], 1e-7f)
    }

    @Test
    fun kaiserTapCountIsOdd() {
        assertTrue(FirDesign.kaiserTaps(1_000.0, 48_000.0, 60.0) % 2 == 1)
    }

    @Test
    fun besselI0KnownValues() {
        assertEquals(1.0, FirDesign.besselI0(0.0), 1e-12)
        assertEquals(1.2660658777520082, FirDesign.besselI0(1.0), 1e-12)
        assertEquals(27.239871823604442, FirDesign.besselI0(5.0), 1e-9)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.NcoTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesignTest`
Expected: FAIL — `unresolved reference 'Nco'`, `'FirDesign'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/dsp/Nco.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Numerically controlled oscillator that shifts interleaved complex samples by multiplying them
 * with exp(j·2π·f·n/fs). Phasor recursion in double precision with periodic renormalisation keeps
 * trigonometry out of the per-sample loop (§4.4).
 */
class Nco(private val sampleRate: Double) {
    private var re = 1.0
    private var im = 0.0
    private var stepRe = 1.0
    private var stepIm = 0.0
    private var sinceRenorm = 0

    var frequencyHz = 0.0
        set(value) {
            field = value
            val w = 2.0 * PI * value / sampleRate
            stepRe = cos(w)
            stepIm = sin(w)
        }

    /** Multiplies [count] complex samples of [data], starting at complex index [offset]. */
    fun mix(data: FloatArray, offset: Int, count: Int) {
        var i = offset * 2
        var r = re
        var m = im
        val sr = stepRe
        val si = stepIm
        for (n in 0 until count) {
            val x = data[i].toDouble()
            val y = data[i + 1].toDouble()
            data[i] = (x * r - y * m).toFloat()
            data[i + 1] = (x * m + y * r).toFloat()
            val nr = r * sr - m * si
            m = r * si + m * sr
            r = nr
            i += 2
            if (++sinceRenorm == 1024) {
                val g = 1.0 / sqrt(r * r + m * m)
                r *= g
                m *= g
                sinceRenorm = 0
            }
        }
        re = r
        im = m
    }

    fun reset() {
        re = 1.0
        im = 0.0
        sinceRenorm = 0
    }
}
```

`core/src/main/kotlin/dsp/FirDesign.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Kaiser-window FIR design. Filters are odd-length, linear phase and normalised to unity DC gain. */
object FirDesign {
    /** Kaiser β for a stopband attenuation of [attenuationDb]. */
    fun kaiserBeta(attenuationDb: Double): Double = when {
        attenuationDb > 50 -> 0.1102 * (attenuationDb - 8.7)
        attenuationDb >= 21 -> 0.5842 * (attenuationDb - 21).pow(0.4) + 0.07886 * (attenuationDb - 21)
        else -> 0.0
    }

    /** Odd tap count for a transition band of [transitionHz] at [sampleRate] and [attenuationDb]. */
    fun kaiserTaps(transitionHz: Double, sampleRate: Double, attenuationDb: Double): Int {
        val dw = 2 * PI * transitionHz / sampleRate
        var n = ceil((attenuationDb - 8) / (2.285 * dw)).toInt() + 1
        if (n % 2 == 0) n++
        return maxOf(n, 3)
    }

    /** Windowed-sinc low-pass; [cutoffHz] is the −6 dB point. */
    fun lowPass(numTaps: Int, cutoffHz: Double, sampleRate: Double, beta: Double): FloatArray {
        require(numTaps >= 3 && numTaps % 2 == 1) { "numTaps must be odd and >= 3" }
        val fc = cutoffHz / sampleRate
        val m = (numTaps - 1) / 2
        val i0Beta = besselI0(beta)
        val h = DoubleArray(numTaps) { n ->
            val k = n - m
            val sinc = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val r = k.toDouble() / m
            sinc * besselI0(beta * sqrt(maxOf(0.0, 1 - r * r))) / i0Beta
        }
        val sum = h.sum()
        return FloatArray(numTaps) { (h[it] / sum).toFloat() }
    }

    /** Low-pass flat to [passbandHz] with at least [attenuationDb] rejection from [stopbandHz]. */
    fun lowPassKaiser(passbandHz: Double, stopbandHz: Double, sampleRate: Double, attenuationDb: Double): FloatArray {
        require(stopbandHz > passbandHz) { "stopband must lie above the passband" }
        val n = kaiserTaps(stopbandHz - passbandHz, sampleRate, attenuationDb)
        return lowPass(n, (passbandHz + stopbandHz) / 2, sampleRate, kaiserBeta(attenuationDb))
    }

    /** Magnitude response in dB of real [taps] at [freqHz]. */
    fun responseDb(taps: FloatArray, freqHz: Double, sampleRate: Double): Double {
        val w = 2 * PI * freqHz / sampleRate
        var re = 0.0
        var im = 0.0
        for (n in taps.indices) {
            re += taps[n] * cos(w * n)
            im -= taps[n] * sin(w * n)
        }
        return 20 * log10(hypot(re, im) + 1e-300)
    }

    /** Zeroth-order modified Bessel function of the first kind (power series). */
    fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-14 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.NcoTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDesignTest`
Expected: `OK (8 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): phasor NCO and Kaiser FIR design

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 5: Polyphase decimator and multistage decimation chain

**Files:**
- Create: `core/src/main/kotlin/dsp/FirDecimator.kt`, `core/src/main/kotlin/dsp/DecimatorChain.kt`
- Test: `core/src/test/kotlin/dsp/FirDecimatorTest.kt`, `core/src/test/kotlin/dsp/DecimatorChainTest.kt`

**Interfaces:**
- Consumes: `FirDesign.lowPassKaiser` (Task 4); `Signals` (tests).
- Produces:
  - `class FirDecimator(taps: FloatArray, val factor: Int)` — taps must be odd-length and symmetric; `fun process(input: FloatArray, inCount: Int, output: FloatArray): Int`, `fun maxOutput(inCount: Int): Int`, `fun reset()`.
  - `class DecimatorChain(stages: List<FirDecimator>, val inputRate: Double)` with `val factor: Int`, `val outputRate: Double`, `val stageFactors: List<Int>`, `fun process(input, inCount, output): Int`, `fun maxOutput(inCount): Int`, `fun reset()`; `companion fun planStages(factor: Int): List<Int>`; `companion fun design(inputRate: Double, factor: Int, passbandHz: Double, attenuationDb: Double = 66.0): DecimatorChain`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/dsp/FirDecimatorTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class FirDecimatorTest {
    @Test
    fun impulseResponseEqualsTheTaps() {
        val taps = floatArrayOf(0.1f, 0.2f, 0.4f, 0.2f, 0.1f)
        val d = FirDecimator(taps, 1)
        val x = FloatArray(16)
        x[0] = 1f
        val y = FloatArray(16)
        assertEquals(8, d.process(x, 8, y))
        for (k in 0 until 5) assertEquals(taps[k], y[2 * k], 1e-7f)
        for (k in 5 until 8) assertEquals(0f, y[2 * k], 1e-7f)
    }

    @Test
    fun keepsEveryFactorthOutput() {
        val delayTwo = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        val d = FirDecimator(delayTwo, 3)
        val x = FloatArray(18) { if (it % 2 == 0) (it / 2).toFloat() else 0f }   // I = 0..8
        val y = FloatArray(8)
        assertEquals(3, d.process(x, 9, y))
        assertEquals(listOf(0f, 1f, 4f), listOf(y[0], y[2], y[4]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAsymmetricTaps() {
        FirDecimator(floatArrayOf(1f, 2f, 3f), 2)
    }
}
```

`core/src/test/kotlin/dsp/DecimatorChainTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecimatorChainTest {
    private val fs = 2_400_000.0

    @Test
    fun planStagesSplitsLargestFirst() {
        assertEquals(listOf(5, 2), DecimatorChain.planStages(10))
        assertEquals(listOf(5, 5, 2), DecimatorChain.planStages(50))
        assertEquals(listOf(8, 4), DecimatorChain.planStages(32))
        assertEquals(listOf(13), DecimatorChain.planStages(13))
        assertEquals(listOf(6, 2), DecimatorChain.planStages(12))
        assertEquals(emptyList<Int>(), DecimatorChain.planStages(1))
    }

    private fun levelThrough(chain: DecimatorChain, freqHz: Double): Double {
        val n = 240_000
        val input = Signals.complexTone(n, freqHz, fs)
        val out = FloatArray(2 * chain.maxOutput(n))
        val m = chain.process(input, n, out)
        return Signals.complexPowerDb(out, m / 2, m)
    }

    @Test
    fun wfmChainKeepsThePassbandAndRejectsAliases() {
        fun chain() = DecimatorChain.design(fs, 10, 100_000.0)
        assertEquals(240_000.0, chain().outputRate, 1e-9)
        assertEquals(0.0, levelThrough(chain(), 50_000.0), 0.5)
        assertEquals(0.0, levelThrough(chain(), -95_000.0), 0.5)
        assertTrue(levelThrough(chain(), 290_000.0) < -60.0)   // would alias to +50 kHz
        assertTrue(levelThrough(chain(), -500_000.0) < -60.0)
    }

    @Test
    fun narrowChannelChain() {
        fun chain() = DecimatorChain.design(fs, 50, 6_250.0)
        assertEquals(listOf(5, 5, 2), chain().stageFactors)
        assertEquals(48_000.0, chain().outputRate, 1e-9)
        assertEquals(0.0, levelThrough(chain(), 6_000.0), 0.5)
        assertTrue(levelThrough(chain(), 51_000.0) < -60.0)    // would alias to +3 kHz
    }

    @Test
    fun blockSplittingDoesNotChangeTheOutput() {
        val n = 50_000
        val input = FloatArray(2 * n) { ((it * 7919) % 1000) / 1000f - 0.5f }
        val whole = DecimatorChain.design(fs, 50, 10_000.0)
        val outWhole = FloatArray(2 * whole.maxOutput(n))
        val mWhole = whole.process(input, n, outWhole)

        val split = DecimatorChain.design(fs, 50, 10_000.0)
        val outSplit = FloatArray(2 * split.maxOutput(n))
        val tmp = FloatArray(2 * split.maxOutput(n))
        var mSplit = 0
        var offset = 0
        for (len in intArrayOf(1, 999, 17_000, 12_345, n - 1 - 999 - 17_000 - 12_345)) {
            val k = split.process(input.copyOfRange(2 * offset, 2 * (offset + len)), len, tmp)
            System.arraycopy(tmp, 0, outSplit, 2 * mSplit, 2 * k)
            mSplit += k
            offset += len
        }
        assertEquals(mWhole, mSplit)
        for (i in 0 until 2 * mWhole) assertEquals(outWhole[i], outSplit[i], 1e-5f)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimatorTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChainTest`
Expected: FAIL — `unresolved reference 'FirDecimator'`, `'DecimatorChain'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/dsp/FirDecimator.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs

/**
 * Decimating FIR for interleaved complex samples with real, odd-length, symmetric taps. Only the
 * kept outputs are computed, and symmetric taps are folded to halve the multiplies (§4.4).
 */
class FirDecimator(taps: FloatArray, val factor: Int) {
    private val n = taps.size
    private val mid = (n - 1) / 2
    private val h: FloatArray
    private var buf: FloatArray
    private var phase = 0

    init {
        require(factor >= 1) { "factor must be >= 1" }
        require(n % 2 == 1) { "taps must have odd length" }
        val scale = taps.maxOf { abs(it) }
        for (i in 0 until mid) {
            require(abs(taps[i] - taps[n - 1 - i]) <= 1e-6f * scale) { "taps must be symmetric" }
        }
        h = FloatArray(n) { (taps[it] + taps[n - 1 - it]) / 2 }
        buf = FloatArray(2 * (n - 1 + 4096))
    }

    /** Upper bound of the outputs produced for [inCount] inputs. */
    fun maxOutput(inCount: Int) = inCount / factor + 1

    /** Filters and decimates [inCount] complex samples from [input] into [output]; returns the output count. */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        val hist = n - 1
        val need = 2 * (hist + inCount)
        if (buf.size < need) buf = buf.copyOf(need)
        val b = buf
        System.arraycopy(input, 0, b, 2 * hist, 2 * inCount)
        var out = 0
        var i = phase
        while (i < inCount) {
            var re = 0f
            var im = 0f
            var lo = 2 * i
            var hi = 2 * (i + n - 1)
            for (t in 0 until mid) {
                val c = h[t]
                re += c * (b[lo] + b[hi])
                im += c * (b[lo + 1] + b[hi + 1])
                lo += 2
                hi -= 2
            }
            val c = h[mid]
            re += c * b[lo]
            im += c * b[lo + 1]
            output[2 * out] = re
            output[2 * out + 1] = im
            out++
            i += factor
        }
        phase = i - inCount
        System.arraycopy(b, 2 * inCount, b, 0, 2 * hist)
        return out
    }

    fun reset() {
        buf.fill(0f)
        phase = 0
    }
}
```

`core/src/main/kotlin/dsp/DecimatorChain.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

/** Cascade of [FirDecimator] stages (§6.3); the total factor is the product of the stage factors. */
class DecimatorChain(private val stages: List<FirDecimator>, val inputRate: Double) {
    val factor: Int = stages.fold(1) { acc, s -> acc * s.factor }
    val outputRate: Double = inputRate / factor
    val stageFactors: List<Int> get() = stages.map { it.factor }
    private var scratchA = FloatArray(0)
    private var scratchB = FloatArray(0)

    /** Decimates [inCount] complex samples; [output] must hold [maxOutput] complex samples. */
    fun process(input: FloatArray, inCount: Int, output: FloatArray): Int {
        if (stages.isEmpty()) {
            System.arraycopy(input, 0, output, 0, 2 * inCount)
            return inCount
        }
        var src = input
        var count = inCount
        for (idx in stages.indices) {
            val stage = stages[idx]
            val dst = if (idx == stages.lastIndex) output else scratch(idx, stage.maxOutput(count))
            count = stage.process(src, count, dst)
            src = dst
        }
        return count
    }

    fun maxOutput(inCount: Int): Int {
        var c = inCount
        for (s in stages) c = s.maxOutput(c)
        return c
    }

    fun reset() = stages.forEach { it.reset() }

    private fun scratch(idx: Int, complex: Int): FloatArray {
        val need = 2 * complex
        return if (idx % 2 == 0) {
            if (scratchA.size < need) scratchA = FloatArray(need)
            scratchA
        } else {
            if (scratchB.size < need) scratchB = FloatArray(need)
            scratchB
        }
    }

    companion object {
        /** Splits [factor] into at most 3 stages, largest first; each stage ≤ 8 unless a prime factor is larger. */
        fun planStages(factor: Int): List<Int> {
            require(factor >= 1) { "factor must be >= 1" }
            val primes = mutableListOf<Int>()
            var rest = factor
            var p = 2
            while (rest > 1) {
                while (rest % p == 0) {
                    primes += p
                    rest /= p
                }
                p++
            }
            primes.sortDescending()
            val stages = mutableListOf<Int>()
            for (q in primes) {
                if (stages.isNotEmpty() && stages.last() * q <= 8) stages[stages.lastIndex] = stages.last() * q
                else stages += q
            }
            while (stages.size > 3) {
                val tail = stages.removeAt(stages.lastIndex)
                stages[stages.lastIndex] = stages.last() * tail
            }
            return stages.sortedDescending()
        }

        /**
         * Designs a chain that decimates by [factor], keeps ±[passbandHz] flat and rejects everything
         * that would alias into it by at least [attenuationDb].
         */
        fun design(inputRate: Double, factor: Int, passbandHz: Double, attenuationDb: Double = 66.0): DecimatorChain {
            val stages = mutableListOf<FirDecimator>()
            var rate = inputRate
            for (f in planStages(factor)) {
                val outRate = rate / f
                val stopband = outRate - passbandHz
                require(stopband > passbandHz) { "passband ±$passbandHz Hz is too wide for $outRate Hz" }
                stages += FirDecimator(FirDesign.lowPassKaiser(passbandHz, stopband, rate, attenuationDb), f)
                rate = outRate
            }
            return DecimatorChain(stages, inputRate)
        }
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.FirDecimatorTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DecimatorChainTest`
Expected: `OK (7 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): polyphase FIR decimator and multistage chain

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 6: Fractional resampler and drift controller

**Files:**
- Create: `core/src/main/kotlin/dsp/FractionalResampler.kt`, `core/src/main/kotlin/dsp/DriftController.kt`
- Test: `core/src/test/kotlin/dsp/FractionalResamplerTest.kt`, `core/src/test/kotlin/dsp/DriftControllerTest.kt`

**Interfaces:**
- Consumes: `FirDesign.kaiserTaps`, `kaiserBeta`, `besselI0` (Task 4); `Signals` (tests).
- Produces:
  - `class FractionalResampler(val channels: Int, val inputRate: Double, val outputRate: Double, passbandHz: Double = 0.4 * min(inputRate, outputRate), attenuationDb: Double = 80.0, phases: Int = 128)` with `var correction: Double` (relative trim of the input step), `fun process(input: FloatArray, frames: Int, output: FloatArray): Int` (interleaved frames in and out), `fun maxOutput(frames: Int): Int`.
  - `class DriftController(bufferSeconds: Double = 0.15, bandwidthHz: Double = 0.005, damping: Double = 0.8, maxCorrection: Double = 500e-6, target: Double = 0.5)` with `fun update(fillLevel: Double, dtSeconds: Double): Double` (returns the correction to apply) and `fun reset()`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/dsp/FractionalResamplerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class FractionalResamplerTest {
    @Test
    fun preservesToneFrequencyAndAmplitude() {
        val r = FractionalResampler(1, 64_000.0, 48_000.0)
        val input = Signals.realTone(64_000, 1_000.0, 64_000.0, 0.5)
        val out = FloatArray(r.maxOutput(64_000))
        val m = r.process(input, 64_000, out)
        assertEquals(48_000.0, m.toDouble(), 2.0)
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(out, 48_000.0, m / 2, m), 0.05)
        assertEquals(0.5f, Signals.peak(out, m / 2, m), 0.005f)
    }

    @Test
    fun rejectsContentThatWouldAlias() {
        val r = FractionalResampler(1, 96_000.0, 48_000.0)
        val input = Signals.realTone(96_000, 36_000.0, 96_000.0, 0.5)   // would alias to 12 kHz
        val out = FloatArray(r.maxOutput(96_000))
        val m = r.process(input, 96_000, out)
        var p = 0.0
        for (i in m / 2 until m) p += out[i] * out[i]
        val levelDb = 10 * log10(p / (m - m / 2) / 0.125)   // relative to the input tone power
        assertTrue("alias level $levelDb dB", levelDb < -70)
    }

    @Test
    fun stereoChannelsStayIndependent() {
        val frames = 50_000
        val input = FloatArray(2 * frames)
        for (f in 0 until frames) {
            input[2 * f] = (0.5 * sin(2 * PI * 1_000.0 * f / 50_000.0)).toFloat()
            input[2 * f + 1] = (0.5 * sin(2 * PI * 3_000.0 * f / 50_000.0)).toFloat()
        }
        val r = FractionalResampler(2, 50_000.0, 48_000.0)
        val out = FloatArray(2 * r.maxOutput(frames))
        val m = r.process(input, frames, out)
        assertEquals(1_000.0, Signals.zeroCrossingFrequency(out, 48_000.0, m / 2, m, 2, 0), 0.05)
        assertEquals(3_000.0, Signals.zeroCrossingFrequency(out, 48_000.0, m / 2, m, 2, 1), 0.1)
    }

    @Test
    fun correctionChangesTheOutputCount() {
        val input = Signals.realTone(480_000, 500.0, 48_000.0)
        val plain = FractionalResampler(1, 48_000.0, 48_000.0)
        val trimmed = FractionalResampler(1, 48_000.0, 48_000.0).apply { correction = 1e-3 }
        val mPlain = plain.process(input, 480_000, FloatArray(plain.maxOutput(480_000)))
        val mTrimmed = trimmed.process(input, 480_000, FloatArray(trimmed.maxOutput(480_000)))
        assertEquals(480_000.0, mPlain.toDouble(), 2.0)
        assertEquals(480_000.0 / 1.001, mTrimmed.toDouble(), 2.0)
    }

    @Test
    fun blockSplittingIsSeamless() {
        val input = Signals.realTone(20_000, 2_345.0, 64_000.0)
        val whole = FractionalResampler(1, 64_000.0, 48_000.0)
        val outWhole = FloatArray(whole.maxOutput(20_000))
        val mWhole = whole.process(input, 20_000, outWhole)
        val split = FractionalResampler(1, 64_000.0, 48_000.0)
        val outSplit = FloatArray(whole.maxOutput(20_000))
        val tmp = FloatArray(split.maxOutput(20_000))
        var mSplit = 0
        var offset = 0
        for (len in intArrayOf(1, 333, 7_000, 12_666)) {
            val k = split.process(input.copyOfRange(offset, offset + len), len, tmp)
            System.arraycopy(tmp, 0, outSplit, mSplit, k)
            mSplit += k
            offset += len
        }
        assertEquals(mWhole, mSplit)
        for (i in 0 until mWhole) assertEquals(outWhole[i], outSplit[i], 1e-6f)
    }
}
```

`core/src/test/kotlin/dsp/DriftControllerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DriftControllerTest {
    @Test
    fun holdsTheFillLevelAgainstAClockOffset() {
        val bufferSeconds = 0.15
        val controller = DriftController(bufferSeconds = bufferSeconds)
        val sdrClockFast = 120e-6
        var fill = 0.5
        var correction = 0.0
        val dt = 0.05
        repeat((1_800 / dt).toInt()) {   // 30 simulated minutes
            fill += (sdrClockFast - correction) * dt / bufferSeconds
            correction = controller.update(fill, dt)
        }
        assertEquals(0.5, fill, 0.02)
        assertEquals(sdrClockFast, correction, 5e-6)
    }

    @Test
    fun neverExceedsTheMaximumCorrection() {
        val controller = DriftController(maxCorrection = 500e-6)
        repeat(1_000) { assertTrue(abs(controller.update(1.0, 0.05)) <= 500e-6) }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.FractionalResamplerTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DriftControllerTest`
Expected: FAIL — `unresolved reference 'FractionalResampler'`, `'DriftController'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/dsp/FractionalResampler.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polyphase windowed-sinc resampler for interleaved real audio (§6.5). Coefficients are linearly
 * interpolated between [phases] phases; [correction] trims the ratio at run time for drift control.
 * Callers band-limit the input to [passbandHz] beforehand (every demodulator does).
 */
class FractionalResampler(
    val channels: Int,
    val inputRate: Double,
    val outputRate: Double,
    passbandHz: Double = 0.4 * minOf(inputRate, outputRate),
    attenuationDb: Double = 80.0,
    private val phases: Int = 128,
) {
    /** Relative trim of the input step: +0.001 consumes 0.1 % more input per output frame. */
    var correction = 0.0

    private val baseStep = inputRate / outputRate
    private val tapsPerPhase: Int
    private val table: Array<FloatArray>
    private var buf = FloatArray(0)
    private var pos: Double

    init {
        require(channels >= 1 && inputRate > 0 && outputRate > 0)
        val edge = minOf(inputRate, outputRate)
        val stopbandHz = edge - passbandHz
        require(stopbandHz > passbandHz) { "passband too wide for the lower rate" }
        val protoRate = inputRate * phases
        val n = FirDesign.kaiserTaps(stopbandHz - passbandHz, protoRate, attenuationDb)
        tapsPerPhase = (n + phases - 1) / phases
        val len = tapsPerPhase * phases
        val cutoff = (passbandHz + stopbandHz) / 2 / protoRate
        val beta = FirDesign.kaiserBeta(attenuationDb)
        val i0Beta = FirDesign.besselI0(beta)
        val centre = (len - 1) / 2.0
        val proto = DoubleArray(len) { k ->
            val x = k - centre
            val sinc = sin(2 * PI * cutoff * x) / (PI * x)   // x is never 0: len is even
            val r = x / centre
            sinc * FirDesign.besselI0(beta * sqrt(maxOf(0.0, 1 - r * r))) / i0Beta
        }
        val gain = phases / proto.sum()
        table = Array(phases + 1) { p ->
            FloatArray(tapsPerPhase) { j ->
                val idx = j * phases + p
                if (idx < len) (proto[idx] * gain).toFloat() else 0f
            }
        }
        pos = (tapsPerPhase - 1).toDouble()
    }

    /** Upper bound of output frames for [frames] input frames at the current correction. */
    fun maxOutput(frames: Int): Int = (frames / (baseStep * (1 - abs(correction) - 1e-3))).toInt() + 2

    /** Resamples [frames] interleaved frames from [input] into [output]; returns output frames. */
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        val hist = tapsPerPhase - 1
        val need = channels * (hist + frames)
        if (buf.size < need) buf = buf.copyOf(need)
        System.arraycopy(input, 0, buf, channels * hist, channels * frames)
        val step = baseStep * (1 + correction)
        val last = hist + frames - 1
        var out = 0
        var p = pos
        while (true) {
            val n = floor(p).toInt()
            if (n > last) break
            val ph = (p - n) * phases
            val pi = ph.toInt()
            val frac = (ph - pi).toFloat()
            val h0 = table[pi]
            val h1 = table[pi + 1]
            for (c in 0 until channels) {
                var acc = 0f
                var idx = n * channels + c
                for (j in 0 until tapsPerPhase) {
                    acc += (h0[j] + (h1[j] - h0[j]) * frac) * buf[idx]
                    idx -= channels
                }
                output[out * channels + c] = acc
            }
            out++
            p += step
        }
        pos = p - frames
        System.arraycopy(buf, channels * frames, buf, 0, channels * hist)
        return out
    }
}
```

`core/src/main/kotlin/dsp/DriftController.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI

/**
 * PI controller that trims [FractionalResampler.correction] so the audio sink's buffer stays near
 * [target] while the SDR and phone clocks drift (§6.5). Positive output consumes input faster.
 * Gains place the closed loop at [bandwidthHz] for a sink holding [bufferSeconds] of audio.
 */
class DriftController(
    bufferSeconds: Double = 0.15,
    bandwidthHz: Double = 0.005,
    damping: Double = 0.8,
    private val maxCorrection: Double = 500e-6,
    private val target: Double = 0.5,
) {
    private val kp: Double
    private val ki: Double
    private var integral = 0.0

    init {
        val w = 2 * PI * bandwidthHz
        kp = 2 * damping * w * bufferSeconds
        ki = w * w * bufferSeconds
    }

    /** Takes the sink fill level (0..1) measured [dtSeconds] after the last call; returns the correction. */
    fun update(fillLevel: Double, dtSeconds: Double): Double {
        val error = fillLevel - target
        integral = (integral + error * dtSeconds).coerceIn(-maxCorrection / ki, maxCorrection / ki)
        return (kp * error + ki * integral).coerceIn(-maxCorrection, maxCorrection)
    }

    fun reset() {
        integral = 0.0
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.FractionalResamplerTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DriftControllerTest`
Expected: `OK (7 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): polyphase fractional resampler and clock-drift controller

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 7: DC blocker, AGC and de-emphasis

**Files:**
- Create: `core/src/main/kotlin/dsp/DcBlocker.kt`, `core/src/main/kotlin/dsp/Agc.kt`, `core/src/main/kotlin/dsp/Deemphasis.kt`
- Test: `core/src/test/kotlin/dsp/DcBlockerTest.kt`, `core/src/test/kotlin/dsp/AgcTest.kt`, `core/src/test/kotlin/dsp/DeemphasisTest.kt`

**Interfaces:**
- Consumes: `Signals` (tests).
- Produces:
  - `class DcBlocker(channels: Int, cutoffHz: Double, sampleRate: Double)` with `fun process(data: FloatArray, frames: Int)` (in place; `channels = 2` for interleaved IQ).
  - `class Agc(sampleRate: Double, attackMs: Double = 2.0, decayMs: Double = 500.0, hangMs: Double = 200.0, targetPeak: Float = 0.5f, maxGainDb: Double = 60.0)` with `fun process(data: FloatArray, count: Int)` (real audio, in place).
  - `class Deemphasis(channels: Int, tauSeconds: Double, sampleRate: Double)` with `fun process(data: FloatArray, frames: Int)`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/dsp/DcBlockerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Test

class DcBlockerTest {
    @Test
    fun removesDcFromIq() {
        val n = 240_000
        val x = FloatArray(2 * n) { if (it % 2 == 0) 0.3f else -0.2f }
        DcBlocker(2, 1_000.0, 2_400_000.0).process(x, n)
        assertEquals(0f, x[2 * n - 2], 1e-3f)
        assertEquals(0f, x[2 * n - 1], 1e-3f)
    }

    @Test
    fun passesSignalsWellAboveTheCutoff() {
        val fs = 48_000.0
        val x = Signals.realTone(48_000, 1_000.0, fs, 0.5)
        DcBlocker(1, 20.0, fs).process(x, x.size)
        assertEquals(0.5f, Signals.peak(x, 24_000, 48_000), 0.005f)
    }
}
```

`core/src/test/kotlin/dsp/AgcTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgcTest {
    private val fs = 48_000.0

    @Test
    fun bringsAWeakSignalToTheTarget() {
        val agc = Agc(fs, targetPeak = 0.5f)
        val x = Signals.realTone(48_000, 1_000.0, fs, 0.01)
        agc.process(x, x.size)
        assertEquals(0.5f, Signals.peak(x, 24_000, 48_000), 0.05f)
    }

    @Test
    fun settlesQuicklyAfterALoudStep() {
        val agc = Agc(fs, targetPeak = 0.5f)
        val quiet = Signals.realTone(48_000, 1_000.0, fs, 0.01)
        agc.process(quiet, quiet.size)
        val loud = Signals.realTone(48_000, 1_000.0, fs, 0.1)
        agc.process(loud, loud.size)
        assertTrue(Signals.peak(loud, 960, 2_400) < 0.75f)   // 20–50 ms after the step
        assertEquals(0.5f, Signals.peak(loud, 24_000, 48_000), 0.05f)
    }

    @Test
    fun gainIsCapped() {
        val agc = Agc(fs, targetPeak = 0.5f, maxGainDb = 40.0)
        val x = Signals.realTone(48_000, 1_000.0, fs, 1e-5)
        agc.process(x, x.size)
        assertTrue(Signals.peak(x, 24_000, 48_000) <= 1e-3f * 1.01f)
    }
}
```

`core/src/test/kotlin/dsp/DeemphasisTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10

class DeemphasisTest {
    private fun gainDb(freqHz: Double, fs: Double, tau: Double): Double {
        val n = (fs / 4).toInt()
        val x = Signals.realTone(n, freqHz, fs)
        Deemphasis(1, tau, fs).process(x, n)
        return 20 * log10(Signals.peak(x, n / 2, n).toDouble())
    }

    @Test
    fun fiftyMicrosecondResponse() {
        val fs = 240_000.0
        val tau = 50e-6
        assertEquals(0.0, gainDb(100.0, fs, tau), 0.1)
        assertEquals(-3.0, gainDb(1 / (2 * PI * tau), fs, tau), 0.3)
        assertEquals(-10.4, gainDb(10_000.0, fs, tau), 0.5)
    }

    @Test
    fun channelsAreIndependent() {
        val x = FloatArray(2_000) { if (it % 2 == 0) 1f else 0f }
        Deemphasis(2, 75e-6, 240_000.0).process(x, 1_000)
        assertEquals(1f, x[1_998], 1e-3f)
        assertEquals(0f, x[1_999], 0f)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlockerTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.AgcTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DeemphasisTest`
Expected: FAIL — `unresolved reference 'DcBlocker'`, `'Agc'`, `'Deemphasis'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/dsp/DcBlocker.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.exp

/** One-pole DC blocker y[n] = x[n] − x[n−1] + a·y[n−1], per interleaved channel (§6.1). */
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
    }
}
```

`core/src/main/kotlin/dsp/Agc.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Peak-tracking AGC for real audio (AM, SSB, CW — §6.4): fast attack, hang, then slow decay. The
 * output peak settles at [targetPeak]; gain never exceeds [maxGainDb].
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
        for (i in 0 until count) {
            val x = data[i]
            val a = abs(x)
            if (a > envelope) {
                envelope += (a - envelope) * attack
                hang = hangSamples
            } else if (hang > 0) {
                hang--
            } else {
                envelope += (a - envelope) * decay
            }
            if (envelope < minEnvelope) envelope = minEnvelope
            data[i] = x * (targetPeak / envelope)
        }
    }
}
```

`core/src/main/kotlin/dsp/Deemphasis.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.exp

/** FM de-emphasis: one-pole low-pass with time constant [tauSeconds] (50 µs or 75 µs), per channel. */
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
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.DcBlockerTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.AgcTest io.github.radhwanalhajjaj.spectrasdr.core.dsp.DeemphasisTest`
Expected: `OK (7 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): DC blocker, peak AGC and FM de-emphasis

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 8: Pilot PLL

**Files:**
- Create: `core/src/main/kotlin/dsp/Pll.kt`
- Test: `core/src/test/kotlin/dsp/PllTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class Pll(sampleRate: Double, centerHz: Double, loopBandwidthHz: Double = 10.0, maxDeviationHz: Double = 20.0, armFilterHz: Double = 400.0, damping: Double = 0.707)` with `fun process(input: FloatArray, count: Int, phaseOut: FloatArray)` (writes oscillator phase θ in [0, 2π) per sample; when locked, input ≈ A·cos θ), `val locked: Boolean`, `val amplitude: Double`, `val frequencyHz: Double`. Plan 2's stereo decoder uses cos 2θ and the RDS demodulator 3θ.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/dsp/PllTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class PllTest {
    private val fs = 240_000.0

    @Test
    fun locksToAnOffsetPilotAmongOtherSignals() {
        val n = 240_000
        val rnd = Random(1)
        val x = FloatArray(n) { i ->
            val t = i / fs
            (0.09 * cos(2 * PI * 19_003.0 * t + 1.0) +                      // pilot, 3 Hz off nominal
                0.4 * sin(2 * PI * 1_000.0 * t) +                          // audio
                0.2 * sin(2 * PI * 38_000.0 * t) * sin(2 * PI * 2_000.0 * t) + // stereo sidebands
                0.02 * rnd.nextGaussian()).toFloat()
        }
        val pll = Pll(fs, 19_000.0)
        val phase = FloatArray(n)
        pll.process(x, n, phase)
        assertTrue(pll.locked)
        assertEquals(19_003.0, pll.frequencyHz, 0.5)
        assertEquals(0.09, pll.amplitude, 0.01)
        var worst = 0.0
        for (i in n - 24_000 until n) {
            val truth = (2 * PI * 19_003.0 * i / fs + 1.0) % (2 * PI)
            val d = phase[i] - truth
            worst = maxOf(worst, abs(atan2(sin(d), cos(d))))
        }
        assertTrue("phase error $worst rad", worst < 0.1)
    }

    @Test
    fun doesNotLockOnNoise() {
        val n = 240_000
        val rnd = Random(2)
        val x = FloatArray(n) { (0.3 * rnd.nextGaussian()).toFloat() }
        val pll = Pll(fs, 19_000.0)
        pll.process(x, n, FloatArray(n))
        assertFalse(pll.locked)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.PllTest`
Expected: FAIL — `unresolved reference 'Pll'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/dsp/Pll.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Second-order PLL that locks to a real sinusoid near [centerHz] (the 19 kHz stereo pilot, §6.4).
 * The input is mixed to baseband and low-passed by a two-pole arm filter, so the phase error is
 * amplitude-independent and signals more than a few kHz away do not disturb the loop. When locked,
 * input ≈ amplitude · cos(θ), where θ is written to phaseOut for every sample.
 */
class Pll(
    private val sampleRate: Double,
    private val centerHz: Double,
    loopBandwidthHz: Double = 10.0,
    maxDeviationHz: Double = 20.0,
    armFilterHz: Double = 400.0,
    damping: Double = 0.707,
) {
    private val twoPi = 2 * PI
    private val armAlpha = 1 - exp(-twoPi * armFilterHz / sampleRate)
    private val lockAlpha = 1 - exp(-twoPi * 2.0 / sampleRate)
    private val centerStep = twoPi * centerHz / sampleRate
    private val maxOffset = twoPi * maxDeviationHz / sampleRate
    private val kp: Double
    private val ki: Double
    private var phase = 0.0
    private var freqOffset = 0.0
    private var aRe = 0.0
    private var aIm = 0.0
    private var zRe = 0.0
    private var zIm = 0.0
    private var lockMetric = 0.0
    private var halfAmplitude = 0.0

    init {
        val wn = twoPi * loopBandwidthHz / sampleRate
        kp = 2 * damping * wn
        ki = wn * wn
    }

    /** True while the loop holds phase lock. */
    val locked: Boolean get() = lockMetric > 0.8

    /** Estimated amplitude of the tracked sinusoid. */
    val amplitude: Double get() = 2 * halfAmplitude

    /** Tracked frequency in Hz. */
    val frequencyHz: Double get() = (centerStep + freqOffset) * sampleRate / twoPi

    fun process(input: FloatArray, count: Int, phaseOut: FloatArray) {
        for (n in 0 until count) {
            val x = input[n].toDouble()
            // For x = A·cos(θ + φ), mixing with e^(−jθ) leaves (A/2)·e^(jφ) after the arm filter.
            aRe += armAlpha * (x * cos(phase) - aRe)
            aIm += armAlpha * (-x * sin(phase) - aIm)
            zRe += armAlpha * (aRe - zRe)
            zIm += armAlpha * (aIm - zIm)
            val err = atan2(zIm, zRe)
            halfAmplitude = hypot(zRe, zIm)
            lockMetric += lockAlpha * (cos(err) - lockMetric)
            freqOffset = (freqOffset + ki * err).coerceIn(-maxOffset, maxOffset)
            phaseOut[n] = phase.toFloat()
            phase += centerStep + freqOffset + kp * err
            if (phase >= twoPi) phase -= twoPi else if (phase < 0) phase += twoPi
        }
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.dsp.PllTest`
Expected: `OK (2 tests)`

- [ ] **Step 5: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): amplitude-independent pilot PLL with lock detection

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 9: Source API and rtl_tcp client

**Files:**
- Create: `core/src/main/kotlin/source/IqSource.kt`
- Create: `core/src/main/kotlin/source/rtltcp/RtlTcpProtocol.kt`, `RetuneSync.kt`, `RtlTcpClient.kt`
- Create (test helpers): `core/src/test/kotlin/source/CollectingListener.kt`, `core/src/test/kotlin/source/rtltcp/FakeRtlTcpServer.kt`
- Test: `core/src/test/kotlin/source/rtltcp/RtlTcpProtocolTest.kt`, `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`

**Interfaces:**
- Consumes: `SampleFormat`, `SampleConverter`, `IqBlock`, `BlockPool` (Task 2).
- Produces:
  - `data class SourceInfo(name, kind: SourceKind, sampleFormat, canTune, sampleRates: List<Int>, gainModel: GainModel, options: Set<OptionKind>)`; `enum SourceKind { RTL_TCP, RSP_TCP, ANDROID_DRIVER, IQ_FILE, SIMULATOR }`.
  - `sealed interface GainModel { TunerSteps(tenthsDb: List<Int>), Rsp(lnaStates, ifgrMin, ifgrMax, agc), None }`; `sealed interface GainSetting { Auto, TunerTenthsDb(tenthsDb), TunerIndex(index), RspLna(state), RspIfgr(db), RspAgc(enabled, setpointDbfs = -30) }`.
  - `enum OptionKind { BIAS_T, DIRECT_SAMPLING, OFFSET_TUNING, RSP_ANTENNA, RSP_NOTCHES }`; `sealed interface SourceOption { val kind; BiasT(enabled), DirectSampling(mode), OffsetTuning(enabled), RspAntenna(port), RspNotches(mask) }`.
  - `sealed class SourceError(val message: String) { ConnectFailed, ProtocolError, StreamEnded, DriverRejected(code, message), DriverNotInstalled, Io }`.
  - `interface SourceListener { onStarted(info); onBlock(block); onError(error); onStopped() }` — `onBlock` runs on the source thread and the listener owns (and must release) the block.
  - `interface IqSource { val info; val centerFrequencyHz: Long; open(listener); close(); setCenterFrequency(hz): Int; setSampleRate(sps); setGain(gain); setPpm(ppm); setOption(option); recalibrate() }` — `setCenterFrequency` returns the tune epoch E; blocks with `tuneEpoch ≥ E` are at the requested frequency or a later one.
  - `object RtlTcpProtocol` — constants for every command in §5.2/§5.3, `HEADER_SIZE = 12`, `COMMAND_SIZE = 5`, `data class DongleInfo(magic, tunerType: TunerType, gainCount)`, `enum TunerType(id, gainsTenthsDb) { UNKNOWN, E4000, FC0012, FC0013, FC2580, R820T, R828D }` with `of(id)`, `parseHeader`, `encodeCommand(command, param, out = ByteArray(5))`, `readIntBE`, `readIntLE`, `writeIntBE`, `writeIntLE`.
  - `data class TimedCommand(command: Int, param: Int, delayBeforeMs: Long = 0)`; `enum RetuneSyncMode { AUTO, IMMEDIATE }` (Tasks 11–12 add entries); `abstract class RetuneSync { wrap(commands); onRetuneRequested(count, bytesPerSample, sampleRate); scan(bytes, off, len): Int }`; `class ImmediateSync`.
  - `data class RtlTcpConfig(host, port, kind = RTL_TCP, name, initialFrequencyHz = 100_000_000, initialSampleRate = 2_400_000, supportedCommands: Set<Int>? = null, detectExtended = true, sendExitOnClose = false, retuneSync = AUTO, blockSize = 16_384, connectTimeoutMs = 5_000, receiveBufferBytes = 8 MiB)`.
  - `class RtlTcpClient(config) : IqSource` with `val dongle: RtlTcpProtocol.DongleInfo?`; companion `RTL_SAMPLE_RATES`, `freqParam(hz: Long): Int`.
  - Test helpers: `CollectingListener(keepPerEpoch: Long = 1_000_000)` with `started`, `stopped` (latches), `info`, `errors`, `blocks`, `totalComplex`, `blocksOf(epoch)`, `awaitEpochSamples(epoch, complex, timeoutMs = 20_000): FloatArray`; `FakeRtlTcpServer(tunerType = 5, gainCount = 29, extraHandshake = empty, format = U8, sampleRate = 1e6, pipelineDelaySamples = 0, supportsTestMode = true, startInTestMode = false, stations: List<Pair<Double, Double>> = empty, noiseRms = 0.01, totalSamples: Long? = null, pacedSamplesPerSecond: Double? = null, initialCenterHz = 100_000_000)` with `start()`, `port`, `commands`, `awaitCommands(n, timeoutMs = 5_000)`, `close()`.

- [ ] **Step 1: Write the test helpers**

`core/src/test/kotlin/source/CollectingListener.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/** Test listener: copies blocks (up to [keepPerEpoch] samples per tune epoch) and records events. */
class CollectingListener(private val keepPerEpoch: Long = 1_000_000) : SourceListener {
    class Captured(val seq: Long, val epoch: Int, val centerHz: Long, val sampleRate: Int, val samples: FloatArray)

    val started = CountDownLatch(1)
    val stopped = CountDownLatch(1)

    @Volatile
    var info: SourceInfo? = null

    val errors = CopyOnWriteArrayList<SourceError>()
    val blocks = CopyOnWriteArrayList<Captured>()
    val totalComplex = AtomicLong()
    private val kept = ConcurrentHashMap<Int, Long>()

    override fun onStarted(info: SourceInfo) {
        this.info = info
        started.countDown()
    }

    override fun onBlock(block: IqBlock) {
        val already = kept.getOrDefault(block.tuneEpoch, 0L)
        if (already < keepPerEpoch) {
            blocks += Captured(block.seq, block.tuneEpoch, block.centerHz, block.sampleRate, block.samples.copyOf(2 * block.length))
            kept[block.tuneEpoch] = already + block.length
        }
        totalComplex.addAndGet(block.length.toLong())
        block.release()
    }

    override fun onError(error: SourceError) {
        errors += error
    }

    override fun onStopped() {
        stopped.countDown()
    }

    fun blocksOf(epoch: Int): List<Captured> = blocks.filter { it.epoch == epoch }

    /** Waits for [complex] samples of [epoch]; returns them concatenated (interleaved I, Q). */
    fun awaitEpochSamples(epoch: Int, complex: Int, timeoutMs: Long = 20_000): FloatArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val got = blocksOf(epoch)
            if (got.sumOf { it.samples.size / 2 } >= complex) {
                val out = FloatArray(2 * complex)
                var pos = 0
                for (c in got) {
                    val n = minOf(c.samples.size, out.size - pos)
                    System.arraycopy(c.samples, 0, out, pos, n)
                    pos += n
                    if (pos == out.size) break
                }
                return out
            }
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $complex samples of epoch $epoch; errors=${errors.map { it.message }}"
            }
            Thread.sleep(5)
        }
    }
}
```

`core/src/test/kotlin/source/rtltcp/FakeRtlTcpServer.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * In-process rtl_tcp / rsp_tcp server for tests. Streams a synthetic band (complex tones at
 * absolute frequencies plus noise) as seen by a tuner at the current centre frequency. Commands take
 * effect [pipelineDelaySamples] samples after they arrive, like a real driver's USB buffers, and
 * test mode replaces samples with the RTL2832U's incrementing byte counter.
 */
class FakeRtlTcpServer(
    private val tunerType: Int = 5,
    private val gainCount: Int = 29,
    private val extraHandshake: ByteArray = ByteArray(0),
    private val format: SampleFormat = SampleFormat.U8,
    private val sampleRate: Double = 1_000_000.0,
    private val pipelineDelaySamples: Int = 0,
    private val supportsTestMode: Boolean = true,
    startInTestMode: Boolean = false,
    /** Absolute frequency (Hz) and amplitude (full scale = 1) of each station. */
    private val stations: List<Pair<Double, Double>> = emptyList(),
    private val noiseRms: Double = 0.01,
    /** Close the connection after about this many samples; null streams until closed. */
    private val totalSamples: Long? = null,
    /** Stream at this many samples per second instead of as fast as possible. */
    private val pacedSamplesPerSecond: Double? = null,
    initialCenterHz: Long = 100_000_000,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    /** Every command received, in order, as (code, parameter). */
    val commands = CopyOnWriteArrayList<Pair<Int, Int>>()

    @Volatile private var closed = false
    @Volatile private var sampleIndex = 0L
    @Volatile private var nextApplyAt = Long.MAX_VALUE
    private val pending = ArrayDeque<Triple<Long, Int, Int>>()   // (applyAt, code, param)
    private val lock = Any()
    private var center = initialCenterHz.toDouble()
    private var testMode = startInTestMode
    private var gainDb = 0.0
    private var counter = 0
    private var curI = 0.0
    private var curQ = 0.0

    fun start(): FakeRtlTcpServer {
        Thread({ runCatching { server.accept().use { handle(it) } } }, "fake-rtltcp").apply {
            isDaemon = true
            start()
        }
        return this
    }

    /** Waits until at least [n] commands have arrived. */
    fun awaitCommands(n: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (commands.size < n) {
            check(System.currentTimeMillis() < deadline) { "expected $n commands, got ${commands.toList()}" }
            Thread.sleep(5)
        }
    }

    private fun handle(socket: Socket) {
        val out = socket.getOutputStream()
        val header = ByteArray(RtlTcpProtocol.HEADER_SIZE)
        "RTL0".toByteArray(Charsets.US_ASCII).copyInto(header)
        RtlTcpProtocol.writeIntBE(header, 4, tunerType)
        RtlTcpProtocol.writeIntBE(header, 8, gainCount)
        out.write(header)
        out.write(extraHandshake)
        out.flush()
        val input = DataInputStream(socket.getInputStream())
        Thread({
            val cmd = ByteArray(RtlTcpProtocol.COMMAND_SIZE)
            runCatching {
                while (!closed) {
                    input.readFully(cmd)
                    val code = cmd[0].toInt() and 0xFF
                    val param = RtlTcpProtocol.readIntBE(cmd, 1)
                    commands += code to param
                    synchronized(lock) {
                        pending.addLast(Triple(sampleIndex + pipelineDelaySamples, code, param))
                        nextApplyAt = pending.first().first
                    }
                }
            }
        }, "fake-rtltcp-cmd").apply {
            isDaemon = true
            start()
        }

        val rnd = Random(7)
        val chunk = 4096
        val bytes = ByteArray(chunk * format.bytesPerComplex)
        val startNs = System.nanoTime()
        while (!closed) {
            val limit = totalSamples
            if (limit != null && sampleIndex >= limit) break
            var b = 0
            for (k in 0 until chunk) {
                if (sampleIndex >= nextApplyAt) applyDue()
                when (format) {
                    SampleFormat.U8 -> if (testMode) {
                        bytes[b] = counter.toByte()
                        bytes[b + 1] = (counter + 1).toByte()
                        counter = (counter + 2) and 0xFF
                    } else {
                        generate(rnd)
                        bytes[b] = u8(curI)
                        bytes[b + 1] = u8(curQ)
                    }
                    SampleFormat.S16LE -> {
                        generate(rnd)
                        writeShortLE(bytes, b, s16(curI))
                        writeShortLE(bytes, b + 2, s16(curQ))
                    }
                }
                b += format.bytesPerComplex
                sampleIndex++
            }
            out.write(bytes, 0, b)
            val rate = pacedSamplesPerSecond
            if (rate != null) {
                val wait = startNs + (sampleIndex * 1e9 / rate).toLong() - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        }
        out.flush()
    }

    private fun applyDue() {
        synchronized(lock) {
            while (pending.isNotEmpty() && pending.first().first <= sampleIndex) {
                val (_, code, param) = pending.removeFirst()
                when (code) {
                    RtlTcpProtocol.SET_FREQ -> center = (param.toLong() and 0xFFFFFFFFL).toDouble()
                    RtlTcpProtocol.SET_TEST_MODE -> if (supportsTestMode) testMode = param != 0
                    RtlTcpProtocol.SET_GAIN -> gainDb = (param - 400) / 10.0   // 40.0 dB of tuner gain = unity
                    RtlTcpProtocol.SET_GAIN_MODE -> if (param == 0) gainDb = 0.0
                    RtlTcpProtocol.RSP_SET_IF_GAIN_R -> gainDb = -param.toDouble()
                    RtlTcpProtocol.RSP_SET_AGC -> if (param != 0) gainDb = 0.0
                }
            }
            nextApplyAt = if (pending.isEmpty()) Long.MAX_VALUE else pending.first().first
        }
    }

    private fun generate(rnd: Random) {
        var i = noiseRms * rnd.nextGaussian()
        var q = noiseRms * rnd.nextGaussian()
        val t = sampleIndex / sampleRate
        for (s in stations.indices) {
            val (freq, amplitude) = stations[s]
            val offset = freq - center
            if (abs(offset) < sampleRate / 2) {
                val ph = 2 * PI * offset * t
                i += amplitude * cos(ph)
                q += amplitude * sin(ph)
            }
        }
        val g = 10.0.pow(gainDb / 20)
        curI = i * g
        curQ = q * g
    }

    private fun u8(v: Double) = (v * 127.5 + 127.5).roundToInt().coerceIn(0, 255).toByte()

    private fun s16(v: Double) = (v * 32767).roundToInt().coerceIn(-32768, 32767)

    private fun writeShortLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v shr 8).toByte()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`core/src/test/kotlin/source/rtltcp/RtlTcpProtocolTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtlTcpProtocolTest {
    @Test
    fun parsesTheDongleHeader() {
        val h = ByteArray(12)
        "RTL0".toByteArray(Charsets.US_ASCII).copyInto(h)
        RtlTcpProtocol.writeIntBE(h, 4, 5)
        RtlTcpProtocol.writeIntBE(h, 8, 29)
        val d = RtlTcpProtocol.parseHeader(h)
        assertEquals("RTL0", d.magic)
        assertEquals(RtlTcpProtocol.TunerType.R820T, d.tunerType)
        assertEquals(29, d.gainCount)
        assertEquals(29, d.tunerType.gainsTenthsDb.size)
        assertEquals(496, d.tunerType.gainsTenthsDb.last())
    }

    @Test
    fun encodesCommandsBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x05, 0xF5.toByte(), 0xE1.toByte(), 0x00),
            RtlTcpProtocol.encodeCommand(RtlTcpProtocol.SET_FREQ, 100_000_000),
        )
        assertArrayEquals(
            byteArrayOf(0x05, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFD.toByte()),
            RtlTcpProtocol.encodeCommand(RtlTcpProtocol.SET_FREQ_CORRECTION, -3),
        )
    }

    @Test
    fun frequenciesAboveTwoGigahertzAreSentUnsigned() {
        assertEquals(2_400_000_000L, RtlTcpClient.freqParam(2_400_000_000L).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun unknownTunerIdsMapToUnknown() {
        assertEquals(RtlTcpProtocol.TunerType.UNKNOWN, RtlTcpProtocol.TunerType.of(42))
    }

    @Test
    fun littleEndianHelpersRoundTrip() {
        val b = ByteArray(4)
        RtlTcpProtocol.writeIntLE(b, 0, 60_000_000)
        assertEquals(0x00, b[0].toInt() and 0xFF)
        assertEquals(0x87, b[1].toInt() and 0xFF)
        assertEquals(60_000_000, RtlTcpProtocol.readIntLE(b, 0))
    }
}
```

`core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
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
    private fun client(port: Int, tweak: (RtlTcpConfig) -> RtlTcpConfig = { it }) =
        RtlTcpClient(tweak(RtlTcpConfig("127.0.0.1", port, retuneSync = RetuneSyncMode.IMMEDIATE)))

    @Test
    fun reportsTunerAndGainsFromTheHeader() {
        FakeRtlTcpServer(tunerType = 6).start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            val gains = (l.info!!.gainModel as GainModel.TunerSteps).tenthsDb
            assertEquals(29, gains.size)
            assertEquals(RtlTcpProtocol.TunerType.R828D, c.dongle!!.tunerType)
            c.close()
            assertTrue(l.stopped.await(5, SECONDS))
        }
    }

    @Test
    fun convertsU8SamplesToFloats() {
        FakeRtlTcpServer(stations = listOf(100_000_000.0 to 0.5), noiseRms = 0.0).start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            val x = l.awaitEpochSamples(epoch = 1, complex = 10_000)
            var meanI = 0.0
            var meanQ = 0.0
            for (k in 0 until 10_000) {
                meanI += x[2 * k]
                meanQ += x[2 * k + 1]
            }
            assertEquals((191 - 127.5) / 127.5, meanI / 10_000, 1e-3)
            assertEquals((128 - 127.5) / 127.5, meanQ / 10_000, 1e-3)
            c.close()
        }
    }

    @Test
    fun sendsInitialSettingsThenCommandsInOrder() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port) { it.copy(initialSampleRate = 2_048_000, initialFrequencyHz = 145_500_000) }
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setGain(GainSetting.TunerTenthsDb(297))
            c.setPpm(-3)
            assertEquals(2, c.setCenterFrequency(433_920_000))
            server.awaitCommands(6)
            assertEquals(
                listOf(0x02 to 2_048_000, 0x01 to 145_500_000, 0x03 to 1, 0x04 to 297, 0x05 to -3, 0x01 to 433_920_000),
                server.commands.toList(),
            )
            c.close()
        }
    }

    @Test
    fun skipsCommandsTheServerDoesNotSupport() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port) { it.copy(supportedCommands = setOf(0x01, 0x02)) }
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setPpm(5)
            c.setCenterFrequency(200_000_000)
            server.awaitCommands(3)
            Thread.sleep(100)
            assertTrue(server.commands.none { it.first == RtlTcpProtocol.SET_FREQ_CORRECTION })
            assertFalse(OptionKind.BIAS_T in l.info!!.options)
            c.close()
        }
    }

    @Test
    fun reportsTheEndOfTheStream() {
        FakeRtlTcpServer(totalSamples = 50_000).start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            assertTrue(l.stopped.await(10, SECONDS))
            assertTrue(l.errors.single() is SourceError.StreamEnded)
            c.close()
        }
    }

    @Test
    fun reportsConnectionFailure() {
        val freePort = ServerSocket(0).use { it.localPort }
        val l = CollectingListener()
        client(freePort).open(l)
        assertTrue(l.stopped.await(10, SECONDS))
        assertTrue(l.errors.single() is SourceError.ConnectFailed)
    }

    @Test
    fun sendsExitBeforeClosingTheAndroidDriver() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port) { it.copy(sendExitOnClose = true, kind = SourceKind.ANDROID_DRIVER) }
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.close()
            server.awaitCommands(3)
            assertEquals(RtlTcpProtocol.ANDROID_EXIT, server.commands.last().first)
        }
    }

    @Test
    fun blocksCarryTheirEpochAndFrequency() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = client(server.port)
            c.open(l)
            l.awaitEpochSamples(epoch = 1, complex = 1_000)
            val epoch = c.setCenterFrequency(300_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 1_000)
            assertTrue(l.blocksOf(epoch).all { it.centerHz == 300_000_000L })
            c.close()
        }
    }
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpProtocolTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClientTest`
Expected: FAIL — `unresolved reference` for `RtlTcpProtocol`, `RtlTcpClient`, `SourceListener` and the other new types.

- [ ] **Step 4: Implement the source API**

`core/src/main/kotlin/source/IqSource.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

/** What a source is and can do; drives which controls the UI shows and which commands are sent. */
data class SourceInfo(
    val name: String,
    val kind: SourceKind,
    val sampleFormat: SampleFormat,
    val canTune: Boolean,
    val sampleRates: List<Int>,
    val gainModel: GainModel,
    val options: Set<OptionKind>,
)

enum class SourceKind { RTL_TCP, RSP_TCP, ANDROID_DRIVER, IQ_FILE, SIMULATOR }

/** How a source expresses gain. */
sealed interface GainModel {
    /** Tuner gain steps in tenths of a dB (rtl_tcp), plus automatic gain. */
    data class TunerSteps(val tenthsDb: List<Int>) : GainModel

    /** SDRplay: LNA states 0 until [lnaStates], IF gain reduction [ifgrMin]..[ifgrMax] dB, optional AGC. */
    data class Rsp(val lnaStates: Int, val ifgrMin: Int, val ifgrMax: Int, val agc: Boolean) : GainModel

    data object None : GainModel
}

sealed interface GainSetting {
    data object Auto : GainSetting
    data class TunerTenthsDb(val tenthsDb: Int) : GainSetting
    data class TunerIndex(val index: Int) : GainSetting
    data class RspLna(val state: Int) : GainSetting
    data class RspIfgr(val db: Int) : GainSetting
    data class RspAgc(val enabled: Boolean, val setpointDbfs: Int = -30) : GainSetting
}

enum class OptionKind { BIAS_T, DIRECT_SAMPLING, OFFSET_TUNING, RSP_ANTENNA, RSP_NOTCHES }

sealed interface SourceOption {
    val kind: OptionKind

    data class BiasT(val enabled: Boolean) : SourceOption {
        override val kind get() = OptionKind.BIAS_T
    }

    /** 0 = off, 1 = I branch, 2 = Q branch. */
    data class DirectSampling(val mode: Int) : SourceOption {
        override val kind get() = OptionKind.DIRECT_SAMPLING
    }

    data class OffsetTuning(val enabled: Boolean) : SourceOption {
        override val kind get() = OptionKind.OFFSET_TUNING
    }

    /** 0 = A, 1 = B, 2 = Hi-Z. */
    data class RspAntenna(val port: Int) : SourceOption {
        override val kind get() = OptionKind.RSP_ANTENNA
    }

    /** Bit mask: AM 1, broadcast FM 2, DAB 4, RF 8. */
    data class RspNotches(val mask: Int) : SourceOption {
        override val kind get() = OptionKind.RSP_NOTCHES
    }
}

/** Plain-language source failures (§11). */
sealed class SourceError(val message: String) {
    class ConnectFailed(message: String) : SourceError(message)
    class ProtocolError(message: String) : SourceError(message)
    class StreamEnded(message: String) : SourceError(message)
    class DriverRejected(val code: Int, message: String) : SourceError(message)
    class DriverNotInstalled : SourceError("The SDR driver app is not installed")
    class Io(message: String) : SourceError(message)
}

interface SourceListener {
    fun onStarted(info: SourceInfo)

    /** Runs on the source thread. The listener owns [block] and must release it. */
    fun onBlock(block: IqBlock)

    fun onError(error: SourceError)

    fun onStopped()
}

/**
 * A stream of IQ samples produced on the source's own thread. [setCenterFrequency] returns a tune
 * epoch E: blocks with tuneEpoch ≥ E are at the requested frequency or a later one (§7.1).
 */
interface IqSource {
    val info: SourceInfo
    val centerFrequencyHz: Long

    fun open(listener: SourceListener)

    fun close()

    fun setCenterFrequency(hz: Long): Int

    fun setSampleRate(sps: Int)

    fun setGain(gain: GainSetting)

    fun setPpm(ppm: Int)

    fun setOption(option: SourceOption)

    /** Re-measures retune latency where a source needs it (§7.1 step 3). Default: nothing to do. */
    fun recalibrate() {}
}
```

- [ ] **Step 5: Implement the protocol and retune-sync base**

`core/src/main/kotlin/source/rtltcp/RtlTcpProtocol.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/** rtl_tcp wire protocol: 12-byte dongle header and 5-byte big-endian commands (§5.2, §5.3). */
object RtlTcpProtocol {
    const val HEADER_SIZE = 12
    const val COMMAND_SIZE = 5

    const val SET_FREQ = 0x01
    const val SET_SAMPLE_RATE = 0x02
    const val SET_GAIN_MODE = 0x03
    const val SET_GAIN = 0x04
    const val SET_FREQ_CORRECTION = 0x05
    const val SET_IF_GAIN = 0x06
    const val SET_TEST_MODE = 0x07
    const val SET_AGC_MODE = 0x08
    const val SET_DIRECT_SAMPLING = 0x09
    const val SET_OFFSET_TUNING = 0x0a
    const val SET_RTL_XTAL = 0x0b
    const val SET_TUNER_XTAL = 0x0c
    const val SET_GAIN_BY_INDEX = 0x0d
    const val SET_BIAS_TEE = 0x0e

    const val ANDROID_EXIT = 0x7e
    const val ANDROID_GAIN_PERCENT = 0x7f
    const val ANDROID_ENABLE_16BIT = 0x80

    const val RSP_SET_ANTENNA = 0x1f
    const val RSP_SET_LNA_STATE = 0x20
    const val RSP_SET_IF_GAIN_R = 0x21
    const val RSP_SET_AGC = 0x22
    const val RSP_SET_AGC_SETPOINT = 0x23
    const val RSP_SET_NOTCH = 0x24
    const val RSP_SET_BIAS_T = 0x25
    const val RSP_SET_REF_OUT = 0x26

    data class DongleInfo(val magic: String, val tunerType: TunerType, val gainCount: Int)

    /** librtlsdr tuner ids and their gain tables in tenths of a dB. */
    enum class TunerType(val id: Int, val gainsTenthsDb: List<Int>) {
        UNKNOWN(0, emptyList()),
        E4000(1, listOf(-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420)),
        FC0012(2, listOf(-99, -40, 71, 179, 192)),
        FC0013(3, listOf(-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197)),
        FC2580(4, emptyList()),
        R820T(5, R82XX_GAINS),
        R828D(6, R82XX_GAINS);

        companion object {
            fun of(id: Int): TunerType = entries.firstOrNull { it.id == id } ?: UNKNOWN
        }
    }

    fun parseHeader(b: ByteArray): DongleInfo {
        require(b.size >= HEADER_SIZE) { "header needs $HEADER_SIZE bytes" }
        return DongleInfo(String(b, 0, 4, Charsets.US_ASCII), TunerType.of(readIntBE(b, 4)), readIntBE(b, 8))
    }

    fun encodeCommand(command: Int, param: Int, out: ByteArray = ByteArray(COMMAND_SIZE)): ByteArray {
        out[0] = command.toByte()
        writeIntBE(out, 1, param)
        return out
    }

    fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    fun writeIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}

/** R820T / R828D gain steps (librtlsdr r82xx_gains), tenths of a dB. */
private val R82XX_GAINS = listOf(
    0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
    280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
)
```

`core/src/main/kotlin/source/rtltcp/RetuneSync.kt`:

```kotlin
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
```

- [ ] **Step 6: Implement the client**

`core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`:

```kotlin
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
        val wasRunning = running
        running = false
        if (wasRunning && config.sendExitOnClose) {
            runCatching { commands.submit { writeCommand(RtlTcpProtocol.ANDROID_EXIT, 0) }.get(1, TimeUnit.SECONDS) }
        }
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
        lastGain = gain
        submit(gainCommands(gain))
    }

    override fun setPpm(ppm: Int) {
        lastPpm = ppm
        submit(listOf(TimedCommand(RtlTcpProtocol.SET_FREQ_CORRECTION, ppm)))
    }

    override fun setOption(option: SourceOption) {
        lastOptions[option.kind] = option
        val cmds = optionCommands(option)
        // Direct sampling and offset tuning change what the samples mean, so they count as retunes.
        if (option is SourceOption.DirectSampling || option is SourceOption.OffsetTuning) retune(cmds) {} else submit(cmds)
    }

    private fun retune(cmds: List<TimedCommand>, update: () -> Unit): Int {
        val wrapped = sync.wrap(cmds)
        val epoch = synchronized(tuneLock) {
            update()
            requestedEpoch += 1
            requestedEpoch
        }
        submit(wrapped)
        return epoch
    }

    private fun submit(cmds: List<TimedCommand>) {
        if (cmds.isEmpty()) return
        runCatching {
            commands.execute {
                for (c in cmds) {
                    if (c.delayBeforeMs > 0) Thread.sleep(c.delayBeforeMs)
                    writeCommand(c.command, c.param)
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
                if (running) listener.onError(SourceError.ConnectFailed("Cannot connect to ${config.host}:${config.port}: ${e.message}"))
                return
            }
            output = s.getOutputStream()
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
            sync = chooseSync()
            applyInitialSettings()
            listener.onStarted(currentInfo)
            streamLoop(input, leftover, listener)
        } catch (e: EOFException) {
            if (running) listener.onError(SourceError.StreamEnded("The server closed the connection"))
        } catch (e: IOException) {
            if (running) listener.onError(SourceError.Io(e.message ?: "Connection error"))
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
    private fun applyInitialSettings() {
        val cmds = mutableListOf(TimedCommand(RtlTcpProtocol.SET_SAMPLE_RATE, sampleRate))
        lastOptions[OptionKind.DIRECT_SAMPLING]?.let { cmds += optionCommands(it) }
        lastOptions[OptionKind.OFFSET_TUNING]?.let { cmds += optionCommands(it) }
        lastPpm?.let { cmds += TimedCommand(RtlTcpProtocol.SET_FREQ_CORRECTION, it) }
        lastGain?.let { cmds += gainCommands(it) }
        cmds += TimedCommand(RtlTcpProtocol.SET_FREQ, freqParam(currentCenterHz))
        retune(cmds) {}
        lastOptions.values
            .filter { it.kind != OptionKind.DIRECT_SAMPLING && it.kind != OptionKind.OFFSET_TUNING }
            .forEach { submit(optionCommands(it)) }
    }

    private fun streamLoop(input: DataInputStream, leftover: ByteArray, listener: SourceListener) {
        val buf = ByteArray(64 * 1024)
        System.arraycopy(leftover, 0, buf, 0, leftover.size)
        var have = leftover.size
        val bps = format.bytesPerComplex
        var observedEpoch = 0
        var activeEpoch = 0
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
```

- [ ] **Step 7: Run the tests and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpProtocolTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClientTest`
Expected: `OK (13 tests)`

- [ ] **Step 8: Run the whole suite**

Run: `tools/test-core.sh`
Expected: `OK` with every test from Tasks 1–9 passing.

- [ ] **Step 9: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): source API and rtl_tcp client with tune epochs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 10: rsp_tcp extended mode

**Files:**
- Create: `core/src/main/kotlin/source/rtltcp/RspCapabilities.kt`
- Modify: `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt` (add `rspCapabilities`, replace `handshake`, change the `BiasT` branch of `optionCommands`, add `rspInfo` and `RSP_SAMPLE_RATES`)
- Test: `core/src/test/kotlin/source/rtltcp/RspExtendedTest.kt`

**Interfaces:**
- Consumes: `RtlTcpProtocol` byte helpers, `RtlTcpClient`, `FakeRtlTcpServer(extraHandshake, format, startInTestMode)`, `CollectingListener` (Task 9).
- Produces:
  - `data class RspCapabilities(version, capabilities, hardwareVersion, sampleFormat, antennaInputCount, thirdAntennaName, thirdAntennaFreqLimit, tunerCount, ifgrMin, ifgrMax)` with `model: RspModel`, `hasBiasT`, `hasNotches`, `hasAgc`; companion `SIZE = 45`, `MAGIC = "RSP0"`, `FORMAT_U8 = 1`, `FORMAT_S16 = 2`, `parse(b)`, `encode(c)`.
  - `enum class RspModel(hwVer, displayName, maxLnaStates) { RSP1, RSP1A, RSP2, RSPDUO, RSPDX, RSP1B, RSPDX_R2, UNKNOWN }` with `of(hwVer)`.
  - `RtlTcpClient.rspCapabilities: RspCapabilities?`; `RtlTcpClient.RSP_SAMPLE_RATES`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/source/rtltcp/RspExtendedTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class RspExtendedTest {
    private val caps = RspCapabilities(
        version = 1,
        capabilities = 0x01 or 0x08 or 0x10 or 0x80,
        hardwareVersion = 255,
        sampleFormat = RspCapabilities.FORMAT_S16,
        antennaInputCount = 3,
        thirdAntennaName = "Hi-Z",
        thirdAntennaFreqLimit = 60_000_000,
        tunerCount = 1,
        ifgrMin = 20,
        ifgrMax = 59,
    )

    private fun immediate(port: Int) = RtlTcpClient(RtlTcpConfig("127.0.0.1", port, retuneSync = RetuneSyncMode.IMMEDIATE))

    @Test
    fun parsesTheStructureIncludingItsLittleEndianField() {
        val bytes = RspCapabilities.encode(caps)
        assertEquals(45, bytes.size)
        // 60_000_000 = 0x03938700, sent in host (little-endian) order
        assertEquals(listOf(0x00, 0x87, 0x93, 0x03), (38..41).map { bytes[it].toInt() and 0xFF })
        assertEquals(caps, RspCapabilities.parse(bytes))
        assertEquals(RspModel.RSP1A, caps.model)
    }

    @Test
    fun detectsExtendedModeAndConvertsS16Samples() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            stations = listOf(100_000_000.0 to 0.5),
            noiseRms = 0.0,
        ).start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            val x = l.awaitEpochSamples(epoch = 1, complex = 5_000)
            val info = l.info!!
            assertEquals(SourceKind.RSP_TCP, info.kind)
            assertEquals(SampleFormat.S16LE, info.sampleFormat)
            assertEquals(GainModel.Rsp(10, 20, 59, true), info.gainModel)
            assertEquals(setOf(OptionKind.BIAS_T, OptionKind.RSP_ANTENNA, OptionKind.RSP_NOTCHES), info.options)
            assertEquals(0.5f, x[0], 1e-4f)
            assertEquals(0f, x[1], 1e-4f)
            c.close()
        }
    }

    @Test
    fun standardServerKeepsTheFirstSampleBytes() {
        FakeRtlTcpServer(startInTestMode = true).start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            val first = l.awaitEpochSamples(epoch = 1, complex = 2)
            assertEquals(SourceKind.RTL_TCP, l.info!!.kind)
            assertEquals(-1f, first[0], 1e-6f)
            assertEquals((1 - 127.5f) / 127.5f, first[1], 1e-6f)
            assertEquals((2 - 127.5f) / 127.5f, first[2], 1e-6f)
            assertEquals((3 - 127.5f) / 127.5f, first[3], 1e-6f)
            c.close()
        }
    }

    @Test
    fun extendedCommandsUseRspCodes() {
        FakeRtlTcpServer(extraHandshake = RspCapabilities.encode(caps), format = SampleFormat.S16LE).start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setGain(GainSetting.RspLna(3))
            c.setGain(GainSetting.RspIfgr(45))
            c.setGain(GainSetting.RspAgc(true, -25))
            c.setOption(SourceOption.RspNotches(0x06))
            c.setOption(SourceOption.RspAntenna(2))
            c.setOption(SourceOption.BiasT(true))
            server.awaitCommands(2 + 7)
            assertEquals(
                listOf(0x20 to 3, 0x21 to 45, 0x22 to 1, 0x23 to -25, 0x24 to 6, 0x1f to 2, 0x25 to 1),
                server.commands.drop(2),
            )
            c.close()
        }
    }

    @Test
    fun biasTeeUsesTheStandardCommandOnRtlServers() {
        FakeRtlTcpServer().start().use { server ->
            val l = CollectingListener()
            val c = immediate(server.port)
            c.open(l)
            assertTrue(l.started.await(5, SECONDS))
            c.setOption(SourceOption.BiasT(true))
            server.awaitCommands(3)
            assertEquals(RtlTcpProtocol.SET_BIAS_TEE to 1, server.commands[2])
            c.close()
        }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RspExtendedTest`
Expected: FAIL — `unresolved reference 'RspCapabilities'`, `'RspModel'`.

- [ ] **Step 3: Implement the structure**

`core/src/main/kotlin/source/rtltcp/RspCapabilities.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/** rsp_tcp extended-mode capability structure: 45 packed bytes after the header (§5.3). */
data class RspCapabilities(
    val version: Int,
    val capabilities: Int,
    val hardwareVersion: Int,
    val sampleFormat: Int,
    val antennaInputCount: Int,
    val thirdAntennaName: String,
    val thirdAntennaFreqLimit: Int,
    val tunerCount: Int,
    val ifgrMin: Int,
    val ifgrMax: Int,
) {
    val model: RspModel get() = RspModel.of(hardwareVersion)
    val hasBiasT: Boolean get() = capabilities and 0x01 != 0
    val hasNotches: Boolean get() = capabilities and 0x78 != 0
    val hasAgc: Boolean get() = capabilities and 0x80 != 0

    companion object {
        const val SIZE = 45
        const val MAGIC = "RSP0"
        const val FORMAT_U8 = 1
        const val FORMAT_S16 = 2

        fun parse(b: ByteArray): RspCapabilities {
            require(b.size >= SIZE && String(b, 0, 4, Charsets.US_ASCII) == MAGIC) { "not an RSP0 structure" }
            val nameEnd = (25 until 38).firstOrNull { b[it].toInt() == 0 } ?: 38
            return RspCapabilities(
                version = RtlTcpProtocol.readIntBE(b, 4),
                capabilities = RtlTcpProtocol.readIntBE(b, 8),
                hardwareVersion = RtlTcpProtocol.readIntBE(b, 16),
                sampleFormat = RtlTcpProtocol.readIntBE(b, 20),
                antennaInputCount = b[24].toInt() and 0xFF,
                thirdAntennaName = String(b, 25, nameEnd - 25, Charsets.US_ASCII),
                // rsp_tcp sends this field without byte-order conversion (little-endian hosts).
                thirdAntennaFreqLimit = RtlTcpProtocol.readIntLE(b, 38),
                tunerCount = b[42].toInt() and 0xFF,
                ifgrMin = b[43].toInt() and 0xFF,
                ifgrMax = b[44].toInt() and 0xFF,
            )
        }

        /** Builds the structure exactly as rsp_tcp sends it. */
        fun encode(c: RspCapabilities): ByteArray {
            val b = ByteArray(SIZE)
            MAGIC.toByteArray(Charsets.US_ASCII).copyInto(b)
            RtlTcpProtocol.writeIntBE(b, 4, c.version)
            RtlTcpProtocol.writeIntBE(b, 8, c.capabilities)
            RtlTcpProtocol.writeIntBE(b, 16, c.hardwareVersion)
            RtlTcpProtocol.writeIntBE(b, 20, c.sampleFormat)
            b[24] = c.antennaInputCount.toByte()
            val name = c.thirdAntennaName.toByteArray(Charsets.US_ASCII)
            name.copyInto(b, 25, 0, minOf(12, name.size))
            RtlTcpProtocol.writeIntLE(b, 38, c.thirdAntennaFreqLimit)
            b[42] = c.tunerCount.toByte()
            b[43] = c.ifgrMin.toByte()
            b[44] = c.ifgrMax.toByte()
            return b
        }
    }
}

/** SDRplay models by the API hwVer that rsp_tcp reports; [maxLnaStates] is the largest over all bands. */
enum class RspModel(val hwVer: Int, val displayName: String, val maxLnaStates: Int) {
    RSP1(1, "RSP1", 4),
    RSP1A(255, "RSP1A", 10),
    RSP2(2, "RSP2", 9),
    RSPDUO(3, "RSPduo", 10),
    RSPDX(4, "RSPdx", 28),
    RSP1B(6, "RSP1B", 10),
    RSPDX_R2(7, "RSPdx-R2", 28),
    UNKNOWN(0, "RSP", 10);

    companion object {
        fun of(hwVer: Int): RspModel = entries.firstOrNull { it.hwVer == hwVer } ?: UNKNOWN
    }
}
```

- [ ] **Step 4: Teach the client the extended handshake**

In `RtlTcpClient.kt`, add below the `dongle` property:

```kotlin
    /** The rsp_tcp extended-mode capabilities, when the server sent them. */
    @Volatile
    var rspCapabilities: RspCapabilities? = null
        private set
```

Replace the whole `handshake` function with:

```kotlin
    /** Reads whatever follows the header; sets [format] and [info]; returns sample bytes read early. */
    private fun handshake(input: DataInputStream, d: RtlTcpProtocol.DongleInfo): ByteArray {
        format = SampleFormat.U8
        currentInfo = rtlInfo(d.tunerType.gainsTenthsDb)
        if (!config.detectExtended) return ByteArray(0)
        val first = ByteArray(4)
        input.readFully(first)
        if (String(first, Charsets.US_ASCII) != RspCapabilities.MAGIC) return first
        val struct = ByteArray(RspCapabilities.SIZE)
        first.copyInto(struct)
        input.readFully(struct, 4, RspCapabilities.SIZE - 4)
        val caps = RspCapabilities.parse(struct)
        rspCapabilities = caps
        format = if (caps.sampleFormat == RspCapabilities.FORMAT_S16) SampleFormat.S16LE else SampleFormat.U8
        currentInfo = rspInfo(caps)
        return ByteArray(0)
    }
```

In `optionCommands`, replace the `BiasT` branch with:

```kotlin
        is SourceOption.BiasT -> listOf(
            TimedCommand(
                if (rspCapabilities != null) RtlTcpProtocol.RSP_SET_BIAS_T else RtlTcpProtocol.SET_BIAS_TEE,
                if (option.enabled) 1 else 0,
            ),
        )
```

Add next to `rtlInfo`:

```kotlin
    private fun rspInfo(c: RspCapabilities) = SourceInfo(
        name = "${c.model.displayName} (rsp_tcp ${config.host})",
        kind = SourceKind.RSP_TCP,
        sampleFormat = format,
        canTune = true,
        sampleRates = RSP_SAMPLE_RATES,
        gainModel = GainModel.Rsp(c.model.maxLnaStates, c.ifgrMin, c.ifgrMax, c.hasAgc),
        options = buildSet {
            if (c.hasBiasT) add(OptionKind.BIAS_T)
            if (c.antennaInputCount > 1) add(OptionKind.RSP_ANTENNA)
            if (c.hasNotches) add(OptionKind.RSP_NOTCHES)
        },
    )
```

Add to the companion object:

```kotlin
        val RSP_SAMPLE_RATES = listOf(2_000_000, 3_000_000, 4_000_000, 6_000_000, 8_000_000, 10_000_000)
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RspExtendedTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClientTest`
Expected: `OK (13 tests)`

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): rsp_tcp extended mode with RSP0 capabilities and 16-bit samples

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 11: Exact retune boundaries from test-mode markers

**Files:**
- Create: `core/src/main/kotlin/source/rtltcp/CounterMarkerSync.kt`
- Modify: `core/src/main/kotlin/source/rtltcp/RetuneSync.kt` (add `MARKER`), `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt` (`chooseSync`, new `syncMode`)
- Test: `core/src/test/kotlin/source/rtltcp/CounterMarkerSyncTest.kt`, `core/src/test/kotlin/source/rtltcp/MarkerRetuneTest.kt`

**Interfaces:**
- Consumes: `RetuneSync`, `TimedCommand`, `RtlTcpClient`, `FakeRtlTcpServer(pipelineDelaySamples, stations)`, `CollectingListener`, `Signals.complexToneDb` (earlier tasks).
- Produces:
  - `class CounterMarkerSync(minRun: Int = 64, guardMs: Double = 1.0, timeoutMs: Double = 1_000.0, markerGapMs: Long = 5) : RetuneSync` with `val timedOut: Boolean`.
  - `enum RetuneSyncMode { AUTO, IMMEDIATE, MARKER }`.
  - `RtlTcpClient.syncMode: RetuneSyncMode` (active strategy). AUTO picks MARKER when the server is not rsp_tcp and accepts `SET_TEST_MODE`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/source/rtltcp/CounterMarkerSyncTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

class CounterMarkerSyncTest {
    /** [stale] random bytes, a counter burst of [burst] bytes, then [fresh] bytes that never continue it. */
    private fun stream(stale: Int, burst: Int, fresh: Int, rnd: Random = Random(3)): ByteArray {
        val b = ByteArray(stale + burst + fresh)
        for (i in 0 until stale) b[i] = rnd.nextInt(256).toByte()
        var c = rnd.nextInt(256)
        for (i in stale until stale + burst) {
            b[i] = c.toByte()
            c = (c + 1) and 0xFF
        }
        for (i in stale + burst until b.size) b[i] = (c + 50 + rnd.nextInt(100)).toByte()
        return b
    }

    private fun scanAll(sync: CounterMarkerSync, data: ByteArray, chunk: Int): Int {
        var pos = 0
        while (pos < data.size) {
            val len = minOf(chunk, data.size - pos)
            val r = sync.scan(data, pos, len)
            if (r >= 0) return pos + r
            pos += len
        }
        return -1
    }

    @Test
    fun boundaryIsTheEndOfTheBurstPlusTheGuard() {
        for (chunk in intArrayOf(2, 8, 100, 4_096, 1 shl 20)) {   // sample-aligned chunks, as the client passes
            val sync = CounterMarkerSync(guardMs = 1.0)
            sync.onRetuneRequested(1, bytesPerSample = 2, sampleRate = 1_000_000)   // guard = 1000 samples
            assertEquals("chunk $chunk", 10_000 + 5_000 + 2_000, scanAll(sync, stream(10_000, 5_000, 10_000), chunk))
        }
    }

    @Test
    fun breakOnTheQByteAlignsToTheSampleStart() {
        val sync = CounterMarkerSync(guardMs = 0.0)
        sync.onRetuneRequested(1, 2, 1_000_000)
        val b = ByteArray(1_000) { 0x40 }
        for (i in 0 until 600) b[i] = i.toByte()
        b[600] = 600.toByte()   // first real I byte happens to continue the counter
        b[601] = 0x10           // its Q byte breaks it
        assertEquals(600, sync.scan(b, 0, b.size))
    }

    @Test
    fun waitsForEveryRequestedBurst() {
        val sync = CounterMarkerSync(guardMs = 0.0)
        sync.onRetuneRequested(2, 2, 1_000_000)
        val first = stream(1_000, 3_000, 1_000)
        val data = first + stream(1_000, 3_000, 1_000, Random(9))
        assertEquals(first.size + 4_000, scanAll(sync, data, 512))
    }

    @Test
    fun neverFiresOnRandomOrNoiseLikeData() {
        val sync = CounterMarkerSync(timeoutMs = 1e9)
        sync.onRetuneRequested(1, 2, 2_400_000)
        val rnd = Random(11)
        val uniform = ByteArray(5_000_000) { rnd.nextInt(256).toByte() }
        assertEquals(-1, sync.scan(uniform, 0, uniform.size))
        val gaussian = ByteArray(5_000_000) { (127.5 + 3 * rnd.nextGaussian()).roundToInt().coerceIn(0, 255).toByte() }
        assertEquals(-1, sync.scan(gaussian, 0, gaussian.size))
    }

    @Test
    fun timesOutWhenTestModeIsIgnored() {
        val sync = CounterMarkerSync(timeoutMs = 100.0)
        sync.onRetuneRequested(1, 2, 1_000_000)   // 100 000 samples = 200 000 bytes
        val rnd = Random(5)
        val data = ByteArray(300_000) { rnd.nextInt(256).toByte() }
        assertEquals(200_000, sync.scan(data, 0, data.size))
        assertTrue(sync.timedOut)
    }

    @Test
    fun wrapAddsATestModePulseAfterTheRetuneCommands() {
        assertEquals(
            listOf(TimedCommand(0x01, 5), TimedCommand(0x07, 1), TimedCommand(0x07, 0, 5)),
            CounterMarkerSync().wrap(listOf(TimedCommand(0x01, 5))),
        )
    }
}
```

`core/src/test/kotlin/source/rtltcp/MarkerRetuneTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerRetuneTest {
    private val rate = 1_000_000.0
    private val stationA = 100_000_000.0 + 200_000   // +200 kHz when tuned to 100 MHz
    private val stationB = 105_000_000.0 - 317_000   // −317 kHz when tuned to 105 MHz
    private val stationC = 110_000_000.0 + 123_000   // +123 kHz when tuned to 110 MHz

    private fun server(delay: Int) = FakeRtlTcpServer(
        sampleRate = rate,
        pipelineDelaySamples = delay,
        noiseRms = 0.01,
        stations = listOf(stationA to 0.3, stationB to 0.3, stationC to 0.3),
    ).start()

    private fun client(port: Int) = RtlTcpClient(RtlTcpConfig("127.0.0.1", port, initialSampleRate = rate.toInt()))

    @Test
    fun noGhostsAfterARetuneDespiteALongPipeline() {
        server(delay = 300_000).use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            val before = l.awaitEpochSamples(epoch = 1, complex = 50_000)
            assertEquals(RetuneSyncMode.MARKER, c.syncMode)
            assertTrue(Signals.complexToneDb(before, 200_000.0, rate) > -15)
            val epoch = c.setCenterFrequency(105_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 150_000)
            for (blk in l.blocksOf(epoch)) {
                assertTrue("ghost of A in block ${blk.seq}", Signals.complexToneDb(blk.samples, 200_000.0, rate) < -40)
                assertTrue("B missing in block ${blk.seq}", Signals.complexToneDb(blk.samples, -317_000.0, rate) > -15)
            }
            c.close()
        }
    }

    @Test
    fun backToBackRetunesLandOnTheLastFrequency() {
        server(delay = 200_000).use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            l.awaitEpochSamples(epoch = 1, complex = 10_000)
            c.setCenterFrequency(105_000_000)
            val epoch = c.setCenterFrequency(110_000_000)
            l.awaitEpochSamples(epoch = epoch, complex = 100_000)
            for (blk in l.blocksOf(epoch)) {
                assertTrue(Signals.complexToneDb(blk.samples, 123_000.0, rate) > -15)
                assertTrue(Signals.complexToneDb(blk.samples, -317_000.0, rate) < -40)
                assertTrue(Signals.complexToneDb(blk.samples, 200_000.0, rate) < -40)
            }
            c.close()
        }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.CounterMarkerSyncTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.MarkerRetuneTest`
Expected: FAIL — `unresolved reference 'CounterMarkerSync'`, `'syncMode'`, `'MARKER'`.

- [ ] **Step 3: Implement the marker strategy**

`core/src/main/kotlin/source/rtltcp/CounterMarkerSync.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

/**
 * Exact retune boundaries from RTL2832U test-mode bursts (§7.1 step 2). After a retune's own
 * commands, test mode goes on and, [markerGapMs] later, off. In test mode the chip sends an
 * incrementing byte counter, and commands execute in order, so new-frequency samples begin where
 * the last expected burst ends (plus a [guardMs] guard). A burst is ≥ [minRun] consecutive
 * incrementing bytes.
 */
class CounterMarkerSync(
    private val minRun: Int = 64,
    private val guardMs: Double = 1.0,
    private val timeoutMs: Double = 1_000.0,
    private val markerGapMs: Long = 5,
) : RetuneSync() {
    private var expected = 0
    private var run = 0
    private var last = -1
    private var inBurst = false
    private var guardLeft = 0
    private var guardTotal = 0
    private var scanned = 0L
    private var timeoutBytes = Long.MAX_VALUE
    private var bps = 2

    /** True once a retune finished by timeout instead of a marker: the server ignores test mode. */
    @Volatile
    var timedOut = false
        private set

    override fun wrap(commands: List<TimedCommand>): List<TimedCommand> = commands +
        TimedCommand(RtlTcpProtocol.SET_TEST_MODE, 1) +
        TimedCommand(RtlTcpProtocol.SET_TEST_MODE, 0, markerGapMs)

    override fun onRetuneRequested(count: Int, bytesPerSample: Int, sampleRate: Int) {
        expected += count
        bps = bytesPerSample
        guardTotal = (guardMs / 1000.0 * sampleRate).toInt() * bytesPerSample
        timeoutBytes = (timeoutMs / 1000.0 * sampleRate).toLong() * bytesPerSample
        scanned = 0
        guardLeft = 0
    }

    override fun scan(bytes: ByteArray, off: Int, len: Int): Int {
        var i = 0
        while (i < len) {
            if (guardLeft > 0) {
                val take = minOf(guardLeft, len - i)
                guardLeft -= take
                i += take
                if (guardLeft == 0) return i
                continue
            }
            val v = bytes[off + i].toInt() and 0xFF
            if (last >= 0 && v == ((last + 1) and 0xFF)) {
                if (++run >= minRun) inBurst = true
            } else {
                if (inBurst) {
                    inBurst = false
                    expected--
                    if (expected <= 0) {
                        expected = 0
                        run = 0
                        last = -1
                        val start = i - i % bps   // the breaking byte's sample starts here
                        if (guardTotal == 0) return start
                        guardLeft = guardTotal
                        i = start
                        continue
                    }
                }
                run = 0
            }
            last = v
            i++
            if (++scanned >= timeoutBytes && !inBurst) {
                expected = 0
                run = 0
                last = -1
                timedOut = true
                return (i + bps - 1) / bps * bps
            }
        }
        return -1
    }
}
```

- [ ] **Step 4: Wire it into the client**

In `RetuneSync.kt`, replace the enum with:

```kotlin
enum class RetuneSyncMode { AUTO, IMMEDIATE, MARKER }
```

In `RtlTcpClient.kt`, replace `chooseSync` with:

```kotlin
    private fun chooseSync(): RetuneSync = when (config.retuneSync) {
        RetuneSyncMode.IMMEDIATE -> ImmediateSync()
        RetuneSyncMode.MARKER -> CounterMarkerSync()
        RetuneSyncMode.AUTO ->
            if (rspCapabilities == null && supports(RtlTcpProtocol.SET_TEST_MODE)) CounterMarkerSync() else ImmediateSync()
    }
```

Add below the `rspCapabilities` property:

```kotlin
    /** The retune strategy in use (§7.1). */
    val syncMode: RetuneSyncMode
        get() = when (sync) {
            is CounterMarkerSync -> RetuneSyncMode.MARKER
            else -> RetuneSyncMode.IMMEDIATE
        }
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.CounterMarkerSyncTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.MarkerRetuneTest`
Expected: `OK (8 tests)`

- [ ] **Step 6: Confirm the ghost test really guards the fix**

Temporarily change `MarkerRetuneTest.client` to pass `retuneSync = RetuneSyncMode.IMMEDIATE`, run the same command, and confirm `noGhostsAfterARetuneDespiteALongPipeline` FAILS with "ghost of A". Restore the line and rerun until `OK (8 tests)`.

- [ ] **Step 7: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): exact retune boundaries from RTL2832U test-mode markers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 12: Latency probe, latency sync and automatic fallback

**Files:**
- Create: `core/src/main/kotlin/source/rtltcp/LatencySync.kt`
- Modify: `core/src/main/kotlin/source/rtltcp/RetuneSync.kt` (add `LATENCY`), `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt` (probe, fallback, `recalibrate`)
- Test: `core/src/test/kotlin/source/rtltcp/LatencySyncTest.kt`, `core/src/test/kotlin/source/rtltcp/LatencyRetuneTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 9–11.
- Produces:
  - `class LatencySync(val settleSamples: Long) : RetuneSync`.
  - `class PowerStepDetector(windowSamples: Int = 512, thresholdDb: Double = 10.0)` with `var baseline: Double` and `fun feed(samples: FloatArray, n: Int): Int` (index just past the first window whose mean power is ≥ thresholdDb below baseline, else -1).
  - `enum RetuneSyncMode { AUTO, IMMEDIATE, MARKER, LATENCY }`.
  - `RtlTcpClient`: `val retuneLatencySamples: Long?`, `val probeCount: Int`, `syncMode` now reports `LATENCY`, `override fun recalibrate()`. AUTO uses LATENCY with a probe for rsp_tcp and for servers without test mode, and switches from MARKER to LATENCY after a marker timeout. Unmeasured settle defaults: 120 ms for `ANDROID_DRIVER`, 250 ms otherwise; measured settle = max(1.5 × latency, 10 ms).

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/source/rtltcp/LatencySyncTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencySyncTest {
    @Test
    fun boundaryFallsAfterTheSettleSamples() {
        for (chunk in intArrayOf(2, 1_000, 65_536)) {
            val sync = LatencySync(settleSamples = 10_000)
            sync.onRetuneRequested(1, bytesPerSample = 4, sampleRate = 2_000_000)
            val data = ByteArray(100_000)
            var pos = 0
            var boundary = -1
            while (pos < data.size && boundary < 0) {
                val len = minOf(chunk, data.size - pos)
                val r = sync.scan(data, pos, len)
                if (r >= 0) boundary = pos + r else pos += len
            }
            assertEquals("chunk $chunk", 40_000, boundary)
        }
    }

    @Test
    fun detectorFindsThePowerDrop() {
        val samples = FloatArray(2 * 6_000) { i -> if (i / 2 < 3_000) 1f else 0.1f }
        val d = PowerStepDetector(windowSamples = 512, thresholdDb = 10.0).apply { baseline = 2.0 }
        assertEquals(3_584, d.feed(samples, 6_000))
    }

    @Test
    fun detectorIgnoresSmallChanges() {
        val samples = FloatArray(2 * 4_096) { 0.6f }   // power 0.72 vs baseline 1.0: −1.4 dB
        val d = PowerStepDetector().apply { baseline = 1.0 }
        assertEquals(-1, d.feed(samples, 4_096))
    }
}
```

`core/src/test/kotlin/source/rtltcp/LatencyRetuneTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp

import io.github.radhwanalhajjaj.spectrasdr.core.source.CollectingListener
import io.github.radhwanalhajjaj.spectrasdr.core.source.SampleFormat
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyRetuneTest {
    private val rate = 1_000_000.0
    private val stationA = 100_000_000.0 + 200_000
    private val stationB = 105_000_000.0 - 317_000
    private val caps = RspCapabilities(1, 0x80, 255, RspCapabilities.FORMAT_S16, 1, "", 0, 1, 20, 59)

    private fun awaitProbe(c: RtlTcpClient, count: Int = 1) {
        val deadline = System.currentTimeMillis() + 20_000
        while (c.probeCount < count) {
            check(System.currentTimeMillis() < deadline) { "latency probe did not run" }
            Thread.sleep(10)
        }
    }

    private fun client(port: Int) = RtlTcpClient(RtlTcpConfig("127.0.0.1", port, initialSampleRate = rate.toInt()))

    private fun assertCleanRetune(c: RtlTcpClient, l: CollectingListener) {
        val epoch = c.setCenterFrequency(105_000_000)
        l.awaitEpochSamples(epoch = epoch, complex = 100_000)
        for (blk in l.blocksOf(epoch)) {
            assertTrue("ghost of A in block ${blk.seq}", Signals.complexToneDb(blk.samples, 200_000.0, rate) < -40)
            assertTrue("B missing in block ${blk.seq}", Signals.complexToneDb(blk.samples, -317_000.0, rate) > -15)
        }
    }

    @Test
    fun measuresLatencyOnRspTcpAndRetunesCleanly() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            sampleRate = rate,
            pipelineDelaySamples = 200_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3, stationB to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            awaitProbe(c)
            assertEquals(RetuneSyncMode.LATENCY, c.syncMode)
            val latency = c.retuneLatencySamples!!
            assertTrue("latency $latency", latency in 200_000L..300_000L)
            assertCleanRetune(c, l)
            c.close()
        }
    }

    @Test
    fun fallsBackToLatencyWhenTestModeIsIgnored() {
        FakeRtlTcpServer(
            sampleRate = rate,
            supportsTestMode = false,
            pipelineDelaySamples = 100_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3, stationB to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 200_000)
            val c = client(s.port)
            c.open(l)
            awaitProbe(c)
            assertEquals(RetuneSyncMode.LATENCY, c.syncMode)
            assertTrue(c.retuneLatencySamples!! in 100_000L..200_000L)
            assertCleanRetune(c, l)
            c.close()
        }
    }

    @Test
    fun recalibrateRunsAnotherProbe() {
        FakeRtlTcpServer(
            extraHandshake = RspCapabilities.encode(caps),
            format = SampleFormat.S16LE,
            sampleRate = rate,
            pipelineDelaySamples = 50_000,
            pacedSamplesPerSecond = rate,
            stations = listOf(stationA to 0.3),
        ).start().use { s ->
            val l = CollectingListener(keepPerEpoch = 10_000)
            val c = client(s.port)
            c.open(l)
            awaitProbe(c, 1)
            c.recalibrate()
            awaitProbe(c, 2)
            assertTrue(c.retuneLatencySamples!! in 50_000L..150_000L)
            c.close()
        }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.LatencySyncTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.LatencyRetuneTest`
Expected: FAIL — `unresolved reference 'LatencySync'`, `'PowerStepDetector'`, `'probeCount'`, `'retuneLatencySamples'`.

- [ ] **Step 3: Implement the latency strategy and detector**

`core/src/main/kotlin/source/rtltcp/LatencySync.kt`:

```kotlin
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
```

- [ ] **Step 4: Add the probe and fallback to the client**

In `RetuneSync.kt`, replace the enum with:

```kotlin
enum class RetuneSyncMode { AUTO, IMMEDIATE, MARKER, LATENCY }
```

In `RtlTcpClient.kt`, add these properties below `syncMode`:

```kotlin
    /** Last measured command-to-stream latency in samples (latency probe), if any. */
    @Volatile
    var retuneLatencySamples: Long? = null
        private set

    /** Number of latency probes completed. */
    @Volatile
    var probeCount = 0
        private set

    @Volatile private var probeRequested = false
```

Replace `syncMode` with:

```kotlin
    /** The retune strategy in use (§7.1). */
    val syncMode: RetuneSyncMode
        get() = when (sync) {
            is CounterMarkerSync -> RetuneSyncMode.MARKER
            is LatencySync -> RetuneSyncMode.LATENCY
            else -> RetuneSyncMode.IMMEDIATE
        }
```

Replace `chooseSync` with:

```kotlin
    private fun chooseSync(): RetuneSync = when (config.retuneSync) {
        RetuneSyncMode.IMMEDIATE -> ImmediateSync()
        RetuneSyncMode.MARKER -> CounterMarkerSync()
        RetuneSyncMode.LATENCY -> latencySync(null).also { probeRequested = true }
        RetuneSyncMode.AUTO ->
            if (rspCapabilities == null && supports(RtlTcpProtocol.SET_TEST_MODE)) CounterMarkerSync()
            else latencySync(null).also { probeRequested = true }
    }

    /** Settle from a measured latency (×1.5, at least 10 ms) or the per-kind default. */
    private fun latencySync(measured: Long?): LatencySync {
        val defaultMs = if (config.kind == SourceKind.ANDROID_DRIVER) 120L else 250L
        val settle = measured?.let { maxOf((it * 1.5).toLong(), sampleRate / 100L) } ?: (sampleRate * defaultMs / 1000)
        return LatencySync(settle)
    }

    override fun recalibrate() {
        probeRequested = true
    }
```

In `streamLoop`, insert at the top of the `while (running)` body, before `val n = input.read(...)`:

```kotlin
                if (probeRequested && !discarding && have == 0) {
                    probeRequested = false
                    block?.let { if (it.length > 0) listener.onBlock(it) else it.release() }
                    block = null
                    runProbe(input)
                    continue
                }
```

In `streamLoop`, insert directly after `activeRate = pendingRate` (where a boundary has been found):

```kotlin
                        val marker = sync as? CounterMarkerSync
                        if (marker != null && marker.timedOut) {
                            sync = latencySync(retuneLatencySamples)
                            probeRequested = true
                        }
```

Add these functions to the class:

```kotlin
    /**
     * Measures command-to-stream latency (§7.1 step 3): set a high gain and wait 1 s, measure the
     * baseline power over 50 ms, step to a low gain and count samples until the power falls ≥ 10 dB.
     * Ends with a synchronised retune that restores the user's gain. Samples read here are discarded.
     */
    private fun runProbe(input: DataInputStream) {
        val chunk = 4096
        val bytes = ByteArray(chunk * format.bytesPerComplex)
        val floats = FloatArray(chunk * 2)
        fun readChunk(): Int {
            input.readFully(bytes)
            return SampleConverter.convert(format, bytes, 0, bytes.size, floats, 0, chunk)
        }
        val (high, low) = probeGainCommands()
        if (!sendNow(high)) return
        var skipped = 0L
        while (skipped < sampleRate) skipped += readChunk()
        var acc = 0.0
        var count = 0
        val baselineSamples = maxOf(sampleRate / 20, chunk)
        while (count < baselineSamples) {
            val n = readChunk()
            for (k in 0 until n) acc += floats[2 * k] * floats[2 * k] + floats[2 * k + 1] * floats[2 * k + 1]
            count += n
        }
        val detector = PowerStepDetector().apply { baseline = acc / count }
        if (!sendNow(low)) return
        var since = 0L
        var measured: Long? = null
        while (since < 2L * sampleRate) {
            val n = readChunk()
            val idx = detector.feed(floats, n)
            if (idx >= 0) {
                measured = since + idx
                break
            }
            since += n
        }
        if (measured != null) retuneLatencySamples = measured
        sync = latencySync(measured ?: retuneLatencySamples)
        probeCount++
        retune(restoreGainCommands() + TimedCommand(RtlTcpProtocol.SET_FREQ, freqParam(currentCenterHz))) {}
    }

    /** Writes [cmds] and waits until they are on the wire. */
    private fun sendNow(cmds: List<TimedCommand>): Boolean = runCatching {
        commands.submit { cmds.forEach { writeCommand(it.command, it.param) } }.get(2, TimeUnit.SECONDS)
    }.isSuccess

    /** (high gain, low gain) command lists for the probe. */
    private fun probeGainCommands(): Pair<List<TimedCommand>, List<TimedCommand>> {
        val caps = rspCapabilities
        if (caps != null) {
            return listOf(
                TimedCommand(RtlTcpProtocol.RSP_SET_AGC, 0),
                TimedCommand(RtlTcpProtocol.RSP_SET_IF_GAIN_R, caps.ifgrMin),
            ) to listOf(TimedCommand(RtlTcpProtocol.RSP_SET_IF_GAIN_R, caps.ifgrMax))
        }
        val gains = dongle?.tunerType?.gainsTenthsDb.orEmpty()
        val hi = gains.maxOrNull() ?: 400
        val lo = gains.minOrNull() ?: 0
        return listOf(
            TimedCommand(RtlTcpProtocol.SET_GAIN_MODE, 1),
            TimedCommand(RtlTcpProtocol.SET_GAIN, hi),
        ) to listOf(TimedCommand(RtlTcpProtocol.SET_GAIN, lo))
    }

    private fun restoreGainCommands(): List<TimedCommand> = lastGain?.let { gainCommands(it) }
        ?: if (rspCapabilities != null) listOf(TimedCommand(RtlTcpProtocol.RSP_SET_AGC, 1))
        else listOf(TimedCommand(RtlTcpProtocol.SET_GAIN_MODE, 0))
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.LatencySyncTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.LatencyRetuneTest`
Expected: `OK (6 tests)`

- [ ] **Step 6: Run the whole suite**

Run: `tools/test-core.sh`
Expected: `OK` — every test from Tasks 1–12 passes (the marker tests still pick MARKER under AUTO).

- [ ] **Step 7: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): latency probe and settle-based retune sync with marker fallback

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

### Task 13: Crash-safe WAV files and the IQ file source

**Files:**
- Create: `core/src/main/kotlin/record/WavWriter.kt`, `core/src/main/kotlin/record/WavReader.kt`, `core/src/main/kotlin/record/RecordingNames.kt`
- Create: `core/src/main/kotlin/source/WavIqFileSource.kt`
- Test: `core/src/test/kotlin/record/WavTest.kt`, `core/src/test/kotlin/record/RecordingNamesTest.kt`, `core/src/test/kotlin/source/WavIqFileSourceTest.kt`

**Interfaces:**
- Consumes: `IqSource` API, `SampleConverter`, `BlockPool`, `CollectingListener` (earlier tasks).
- Produces:
  - `class WavWriter(file: File, val sampleRate: Int, val channels: Int, val bitsPerSample: Int, headerIntervalMs: Long = 2_000, clock: () -> Long = System::currentTimeMillis) : AutoCloseable` with `fun write(bytes, off = 0, len = bytes.size)`, `fun updateHeader()`, `val dataBytes: Long`, `close()`.
  - `data class WavInfo(sampleRate, channels, bitsPerSample, dataOffset: Long, dataBytes: Long)`; `object WavReader { fun read(file: File): WavInfo }` (skips unknown chunks; uses the file length when the data size is 0 or too large).
  - `object RecordingNames { fun iq(time: Instant, centerHz: Long): String; fun audio(time: Instant, centerHz: Long, mode: String): String; fun parseCenterHz(fileName: String): Long? }`.
  - `class WavIqFileSource(file: File, realtime: Boolean = true, loop: Boolean = false, centerHzOverride: Long? = null, blockSize: Int = 16_384) : IqSource` (kind `IQ_FILE`, `canTune = false`).

- [ ] **Step 1: Write the failing tests**

`core/src/test/kotlin/record/WavTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WavTest {
    private fun tempFile(name: String = "test.wav"): File =
        Files.createTempDirectory("wav").toFile().resolve(name).also { it.deleteOnExit() }

    @Test
    fun roundTripsEightAndSixteenBitFiles() {
        for (bits in intArrayOf(8, 16)) {
            val f = tempFile()
            val data = ByteArray(10_000) { (it * 31).toByte() }
            WavWriter(f, 96_000, 2, bits).use { it.write(data) }
            val info = WavReader.read(f)
            assertEquals(96_000, info.sampleRate)
            assertEquals(2, info.channels)
            assertEquals(bits, info.bitsPerSample)
            assertEquals(44L, info.dataOffset)
            assertEquals(10_000L, info.dataBytes)
            assertArrayEquals(data, f.readBytes().copyOfRange(44, 44 + 10_000))
        }
    }

    @Test
    fun headerStaysValidWithoutClose() {
        val f = tempFile()
        var now = 0L
        val w = WavWriter(f, 48_000, 1, 16, headerIntervalMs = 2_000, clock = { now })
        w.write(ByteArray(1_000))
        now = 2_500
        w.write(ByteArray(500))     // triggers a header update covering 1 500 bytes
        now = 2_600
        w.write(ByteArray(500))     // buffered, not yet in the header
        val crashed = tempFile("copy.wav")
        f.copyTo(crashed, overwrite = true)   // simulates the process dying here
        assertEquals(1_500L, WavReader.read(crashed).dataBytes)
        w.close()
        assertEquals(2_000L, WavReader.read(f).dataBytes)
    }

    @Test
    fun zeroDataSizeMeansUseTheFileLength() {
        val f = tempFile()
        WavWriter(f, 8_000, 2, 8).use { it.write(ByteArray(1_000)) }
        val bytes = f.readBytes()
        for (i in 40 until 44) bytes[i] = 0
        f.writeBytes(bytes)
        assertEquals(1_000L, WavReader.read(f).dataBytes)
    }

    @Test
    fun skipsUnknownChunks() {
        val f = tempFile()
        WavWriter(f, 8_000, 2, 8).use { it.write(ByteArray(100) { 7 }) }
        val plain = f.readBytes()
        val list = byteArrayOf(
            'L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
            4, 0, 0, 0,
            'I'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), 'O'.code.toByte(),
        )
        f.writeBytes(plain.copyOfRange(0, 12) + list + plain.copyOfRange(12, plain.size))
        val info = WavReader.read(f)
        assertEquals(44L + list.size, info.dataOffset)
        assertEquals(100L, info.dataBytes)
    }
}
```

`core/src/test/kotlin/record/RecordingNamesTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RecordingNamesTest {
    private val t = Instant.parse("2026-11-12T09:05:07Z")

    @Test
    fun formatsIqAndAudioNames() {
        assertEquals("SpectraSDR_20261112_090507Z_145500000Hz_IQ.wav", RecordingNames.iq(t, 145_500_000))
        assertEquals("SpectraSDR_20261112_090507Z_98100000Hz_WFM.wav", RecordingNames.audio(t, 98_100_000, "WFM"))
    }

    @Test
    fun parsesCentreFrequencyFromCommonNamingSchemes() {
        assertEquals(100_100_000L, RecordingNames.parseCenterHz("SDRSharp_20171123_145327Z_100100000Hz_IQ.wav"))
        assertEquals(100_100_000L, RecordingNames.parseCenterHz("baseband_100100000Hz_14-53-27_23-11-2017.wav"))
        assertEquals(145_500_000L, RecordingNames.parseCenterHz(RecordingNames.iq(t, 145_500_000)))
        assertNull(RecordingNames.parseCenterHz("recording.wav"))
    }
}
```

`core/src/test/kotlin/source/WavIqFileSourceTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.record.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit.SECONDS

class WavIqFileSourceTest {
    private fun iqFile(samples: Int, rate: Int = 96_000): File {
        val f = Files.createTempDirectory("iq").toFile().resolve("SpectraSDR_20261112_120000Z_145500000Hz_IQ.wav")
        f.deleteOnExit()
        val bytes = ByteArray(2 * samples)
        for (k in 0 until samples) {
            bytes[2 * k] = k.toByte()
            bytes[2 * k + 1] = (255 - (k and 0xFF)).toByte()
        }
        WavWriter(f, rate, 2, 8).use { it.write(bytes) }
        return f
    }

    @Test
    fun playsEverySampleAndStops() {
        val src = WavIqFileSource(iqFile(10_000), realtime = false)
        assertEquals(145_500_000L, src.centerFrequencyHz)
        assertEquals(SourceKind.IQ_FILE, src.info.kind)
        assertFalse(src.info.canTune)
        val l = CollectingListener()
        src.open(l)
        assertTrue(l.stopped.await(5, SECONDS))
        assertTrue(l.errors.isEmpty())
        assertEquals(10_000L, l.totalComplex.get())
        val x = l.awaitEpochSamples(epoch = 0, complex = 10_000)
        for (k in intArrayOf(0, 1, 300, 9_999)) {
            assertEquals(((k and 0xFF) - 127.5f) / 127.5f, x[2 * k], 1e-6f)
            assertEquals((255 - (k and 0xFF) - 127.5f) / 127.5f, x[2 * k + 1], 1e-6f)
        }
        assertEquals(96_000, l.blocks.first().sampleRate)
    }

    @Test
    fun loopsUntilClosed() {
        val src = WavIqFileSource(iqFile(5_000), realtime = false, loop = true)
        val l = CollectingListener(keepPerEpoch = 1)
        src.open(l)
        val deadline = System.currentTimeMillis() + 5_000
        while (l.totalComplex.get() < 25_000) {
            check(System.currentTimeMillis() < deadline) { "file did not loop" }
            Thread.sleep(5)
        }
        src.close()
        assertTrue(l.stopped.await(5, SECONDS))
    }

    @Test
    fun realtimePlaybackIsPaced() {
        val src = WavIqFileSource(iqFile(48_000, rate = 96_000), realtime = true)
        val l = CollectingListener()
        val start = System.nanoTime()
        src.open(l)
        assertTrue(l.stopped.await(5, SECONDS))
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue("took $seconds s", seconds >= 0.45)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.record.WavTest io.github.radhwanalhajjaj.spectrasdr.core.record.RecordingNamesTest io.github.radhwanalhajjaj.spectrasdr.core.source.WavIqFileSourceTest`
Expected: FAIL — `unresolved reference 'WavWriter'`, `'WavReader'`, `'RecordingNames'`, `'WavIqFileSource'`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/record/WavWriter.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import java.io.File
import java.io.RandomAccessFile

/**
 * PCM WAV writer that keeps the file playable at all times (§9): the RIFF and data sizes are
 * rewritten every [headerIntervalMs] and on [close], so a crash loses at most that much audio.
 */
class WavWriter(
    file: File,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    private val headerIntervalMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteArray(64 * 1024)
    private var buffered = 0
    private var lastHeader = clock()

    var dataBytes = 0L
        private set

    init {
        require(bitsPerSample == 8 || bitsPerSample == 16) { "8 or 16 bits per sample" }
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        var o = off
        var l = len
        while (l > 0) {
            val n = minOf(l, buffer.size - buffered)
            System.arraycopy(bytes, o, buffer, buffered, n)
            buffered += n
            o += n
            l -= n
            if (buffered == buffer.size) flushBuffer()
        }
        dataBytes += len
        if (clock() - lastHeader >= headerIntervalMs) updateHeader()
    }

    /** Writes buffered data and rewrites the header so the file is valid up to this point. */
    fun updateHeader() {
        flushBuffer()
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(pos)
        lastHeader = clock()
    }

    override fun close() {
        updateHeader()
        raf.close()
    }

    private fun flushBuffer() {
        if (buffered > 0) {
            raf.write(buffer, 0, buffered)
            buffered = 0
        }
    }

    private fun header(data: Long): ByteArray {
        val h = ByteArray(44)
        fun text(o: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, o)
        fun u32(o: Int, v: Long) {
            h[o] = v.toByte()
            h[o + 1] = (v shr 8).toByte()
            h[o + 2] = (v shr 16).toByte()
            h[o + 3] = (v shr 24).toByte()
        }
        fun u16(o: Int, v: Int) {
            h[o] = v.toByte()
            h[o + 1] = (v shr 8).toByte()
        }
        val blockAlign = channels * bitsPerSample / 8
        text(0, "RIFF")
        u32(4, minOf(36 + data, 0xFFFFFFFFL))
        text(8, "WAVE")
        text(12, "fmt ")
        u32(16, 16)
        u16(20, 1)
        u16(22, channels)
        u32(24, sampleRate.toLong())
        u32(28, sampleRate.toLong() * blockAlign)
        u16(32, blockAlign)
        u16(34, bitsPerSample)
        text(36, "data")
        u32(40, minOf(data, 0xFFFFFFFFL - 36))
        return h
    }
}
```

`core/src/main/kotlin/record/WavReader.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.record

import java.io.File
import java.io.RandomAccessFile

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
)

/** Reads the format of PCM WAV files, tolerating unfinalised or crash-truncated headers. */
object WavReader {
    fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
        val riff = ByteArray(12)
        raf.readFully(riff)
        require(text(riff, 0) == "RIFF" && text(riff, 8) == "WAVE") { "${file.name} is not a WAV file" }
        var format: IntArray? = null   // [tag, channels, rate, bits]
        val chunk = ByteArray(8)
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(chunk)
            val id = text(chunk, 0)
            val size = u32(chunk, 4)
            when (id) {
                "fmt " -> {
                    val f = ByteArray(size.toInt())
                    raf.readFully(f)
                    format = intArrayOf(u16(f, 0), u16(f, 2), u32(f, 4).toInt(), u16(f, 14))
                    if (size % 2 == 1L) raf.skipBytes(1)
                }
                "data" -> {
                    val f = format ?: error("${file.name}: data chunk before fmt chunk")
                    require(f[0] == 1) { "${file.name}: only PCM WAV is supported" }
                    val start = raf.filePointer
                    val available = raf.length() - start
                    val bytes = if (size == 0L || size > available) available else size
                    return WavInfo(f[2], f[1], f[3], start, bytes)
                }
                else -> raf.seek(raf.filePointer + size + (size and 1L))
            }
        }
        error("${file.name}: no data chunk")
    }

    private fun text(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)

    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24))
}
```

`core/src/main/kotlin/record/RecordingNames.kt`:

```kotlin
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
```

`core/src/main/kotlin/source/WavIqFileSource.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.record.RecordingNames
import io.github.radhwanalhajjaj.spectrasdr.core.record.WavReader
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays a 2-channel IQ WAV (8-bit unsigned or 16-bit signed) as a source (§5.4), paced in real time
 * unless [realtime] is false. The centre frequency comes from the file name or [centerHzOverride].
 */
class WavIqFileSource(
    private val file: File,
    private val realtime: Boolean = true,
    private val loop: Boolean = false,
    centerHzOverride: Long? = null,
    private val blockSize: Int = 16_384,
) : IqSource {
    private val wav = WavReader.read(file)
    private val format = when (wav.bitsPerSample) {
        8 -> SampleFormat.U8
        16 -> SampleFormat.S16LE
        else -> error("${file.name}: unsupported ${wav.bitsPerSample}-bit samples")
    }

    init {
        require(wav.channels == 2) { "${file.name}: IQ files need 2 channels" }
    }

    override val centerFrequencyHz: Long = centerHzOverride ?: RecordingNames.parseCenterHz(file.name) ?: 0L
    override val info = SourceInfo(
        name = file.name,
        kind = SourceKind.IQ_FILE,
        sampleFormat = format,
        canTune = false,
        sampleRates = listOf(wav.sampleRate),
        gainModel = GainModel.None,
        options = emptySet(),
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    private val pool = BlockPool(blockSize, preallocate = 4)

    override fun open(listener: SourceListener) {
        check(!running) { "already open" }
        running = true
        thread = Thread({ play(listener) }, "iq-file").apply {
            isDaemon = true
            start()
        }
    }

    private fun play(listener: SourceListener) {
        try {
            listener.onStarted(info)
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(blockSize * format.bytesPerComplex)
                var seq = 0L
                var emitted = 0L
                val startNs = System.nanoTime()
                do {
                    raf.seek(wav.dataOffset)
                    var remaining = wav.dataBytes - wav.dataBytes % format.bytesPerComplex
                    while (running && remaining > 0) {
                        val want = minOf(bytes.size.toLong(), remaining).toInt()
                        raf.readFully(bytes, 0, want)
                        remaining -= want
                        val b = pool.obtain()
                        b.length = SampleConverter.convert(format, bytes, 0, want, b.samples, 0, b.capacity)
                        b.seq = seq++
                        b.centerHz = centerFrequencyHz
                        b.sampleRate = wav.sampleRate
                        b.tuneEpoch = 0
                        if (realtime) {
                            emitted += b.length
                            val wait = startNs + emitted * 1_000_000_000L / wav.sampleRate - System.nanoTime()
                            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        }
                        listener.onBlock(b)
                    }
                } while (running && loop)
            }
        } catch (e: Exception) {
            if (running) listener.onError(SourceError.Io("${file.name}: ${e.message}"))
        } finally {
            running = false
            listener.onStopped()
        }
    }

    override fun close() {
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    }

    override fun setCenterFrequency(hz: Long): Int = 0

    override fun setSampleRate(sps: Int) {}

    override fun setGain(gain: GainSetting) {}

    override fun setPpm(ppm: Int) {}

    override fun setOption(option: SourceOption) {}
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.record.WavTest io.github.radhwanalhajjaj.spectrasdr.core.record.RecordingNamesTest io.github.radhwanalhajjaj.spectrasdr.core.source.WavIqFileSourceTest`
Expected: `OK (9 tests)`

- [ ] **Step 5: Run the whole suite**

Run: `tools/test-core.sh`
Expected: `OK` — every test from Tasks 1–13 passes.

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -F - <<'EOF'
feat(core): crash-safe WAV writer, tolerant reader, recording names and IQ file source

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
EOF
```

---

## Spec coverage (this plan)

| Spec | Covered by |
|------|-----------|
| §4.1 `source`, `dsp`, `record` packages | Tasks 2–13 (demod, rds, spectrum, sweep, scan, engine: Plans 2, 4, 5) |
| §4.2 `IqSource`, `SourceListener` | Task 9 (`AudioSink`: Plan 2) |
| §4.3 source thread, bounded drop-oldest queue | Tasks 2, 9 (DSP thread: Plan 2) |
| §4.4 zero allocation, LUT conversion, phasor NCO, folded taps, cheap decimation first | Tasks 2, 4, 5 (benchmark, `PerformanceHintManager`: Plans 2–3) |
| §5.2 rtl_tcp stream, commands, supportedTcpCommands, test mode | Tasks 9, 11 |
| §5.3 rsp_tcp extended mode | Task 10 (link test and step-down: Plan 3) |
| §5.4 IQ WAV files | Task 13 |
| §6.1 conversion, DC blocker | Tasks 2, 7 |
| §6.2 FFT sizes, windows, dBFS normalisation | Task 3 (`SpectrumAnalyzer`: Plan 2) |
| §6.3 NCO, multistage decimation | Tasks 4, 5 |
| §6.4 building blocks: de-emphasis, AGC, pilot PLL | Tasks 7, 8 (demodulators: Plan 2) |
| §6.5 polyphase resampler, drift PI controller | Task 6 |
| §7.1 retune sync: seq/epochs, markers, latency probe, NCO-only moves | Tasks 9, 11, 12 (NCO-only moves: Plan 5 scanner) |
| §9 crash-safe WAV, naming | Task 13 (2 GiB split: Plan 5) |
| §12 tests for FFT, filters, NCO, resampler, protocols, retune sync, WAV | Every task |
