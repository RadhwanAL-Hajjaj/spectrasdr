# SpectraSDR Plan 3a — First Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first installable SpectraSDR app (spec milestone 2a): listen to FM (stereo and RDS), NFM, AM, SSB and CW from an RTL-SDR dongle through the SDR driver app, from an rtl_tcp or rsp_tcp server on the network, or from the built-in simulator, with a live spectrum, in the foreground or the background, built and tested on GitHub Actions.

**Architecture:** Everything that can run on the JVM goes into `:core` and is tested locally: the audio ring buffer, the radio session (start, stop, reconnect, settings that outlive a session), display helpers and the driver address. The `:app` module stays thin: an `Application`-wide `Radio` holds one `RadioSession` and exposes its state as flows; an `AudioPlayer` thread feeds an `AudioTrack` from the ring; a foreground `RadioService` follows the radio with a notification, locks and audio focus; Compose screens show and change the state. Android code is compiled, linted and run on an emulator by GitHub Actions (spec D7); `tools/ci-wait.sh` reads the results back.

**Tech Stack:** Kotlin 2.4.0 under Gradle 9.6.1 (wrapper), Android Gradle plugin 9.5.0 with its built-in Kotlin support, Jetpack Compose (BOM 2026.09.00, Material 3), activity-compose 1.10.1, kotlinx-coroutines 1.10.2, JDK 17; JUnit 4.13.2 for `:core` (also run locally by `tools/test-core.sh` with kotlinc 2.1.21); AndroidX Test (runner 1.6.2, rules 1.6.1, ext-junit 1.2.1) and Compose UI test on an emulator (reactivecircus/android-emulator-runner, API 34).

**Spec:** `docs/superpowers/specs/2026-11-12-spectrasdr-design.md` (sections cited as §n). Follow-ups come from `docs/superpowers/plans/2026-11-13-plan-2-completion.md` §6 and `docs/superpowers/plans/2026-11-12-plan-1-completion.md` §5.

## Global Constraints

- License GPL-3.0-or-later; every Kotlin, Java, Kotlin script, XML and workflow file starts with an SPDX line (`// SPDX-License-Identifier: GPL-3.0-or-later`, `<!-- … -->` in XML, `#` in YAML and shell).
- `:core` uses the Kotlin standard library only (spec D6). Its code must compile with both Kotlin 2.1.21 (`tools/test-core.sh`) and Kotlin 2.4.0 (Gradle).
- Package roots: `io.github.radhwanalhajjaj.spectrasdr.core` for `:core` (source directories omit it: `core/src/main/kotlin/audio/AudioRing.kt`), `io.github.radhwanalhajjaj.spectrasdr` for `:app` (full directories).
- Android: minSdk 26, targetSdk 36, compileSdk 37 (decision 5). Permissions are exactly those of §10. No location or storage permission.
- The signal path allocates nothing once it runs (§4.4). `AudioRing.write` runs on the DSP thread and never blocks.
- Numbers shown to the user are formatted with `Locale.ROOT` (`FrequencyText.fixed`), never with the device's locale (decision 7).
- Core tests: `tools/test-core.sh [fully.qualified.TestClass …]`. Android steps are checked on CI: push, then `tools/ci-wait.sh`, which needs the GitHub CLI signed in with read access.
- Work happens on the branch `plan-3a-android-app` (created in Task 1 from `main`). Pushing that branch is part of the plan; `main` changes only through a pull request the user approves.
- Every listing in this plan was built and run before the plan was written: the core listings locally, the Android listings on CI and on the emulator. Enter the code as written. If a step does not behave as written, stop and report it instead of improvising.
- Every commit message ends with a `Co-Authored-By:` line that names the model making the commit, and the session line. The commit steps below show Claude Sonnet 5.5; change the name if another model does the work.
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
  ```

## Decisions this plan takes

1. **Milestone 2 is split.** Plan 3a delivers the first listening APK; Plan 3b adds the GL waterfall, spectrum gestures, diagnostics and benchmark, the performance-hint session, the battery-optimisation prompt, starting on dongle attach and the sample-rate step-down on a slow network. Spec §14 is amended in Task 8.
2. **Session logic lives in `:core`.** `RadioSession` builds sources and receivers, keeps settings between sessions and reconnects, so it is tested on the JVM; the app only adapts it to Android.
3. **Audio path:** `AudioRing` (core) holds stereo float frames with a 150 ms target and twice that capacity, plays silence until it is back at the target, and drops on overflow (the `AudioSink` contract of Plan 2). `AudioPlayer` (app) feeds a float stereo `AudioTrack` from its own urgent-audio thread in 10 ms pieces.
4. **The installable test build is the release build type**, not debuggable (a debuggable app ran the signal path several times slower on the emulator: load 0.4 with spikes above 1 against 0.11–0.24), minification off, signed with a public test key (`debug.keystore`, password `android`) committed to the repository, so each test build installs over the previous one. Instrumented tests run against it (`testBuildType = "release"`). Every push publishes it to the public pre-release "Latest test build" (tag `test-build`), together with the emulator screenshots.
5. **compileSdk 37:** Compose BOM 2026.09.00 (Compose 1.12) requires it; targetSdk stays 36. Spec D9 is amended.
6. **SharedPreferences** for the last settings and source instead of DataStore: a few values, read synchronously at start, no extra library. Spec §10 is amended.
7. **Locale-independent numbers:** all frequencies, levels and rates are formatted with `Locale.ROOT`, so an Arabic-locale phone shows `100.300 000 MHz`, not Arabic-Indic digits.
8. **One radio per process.** `SpectraApp` owns `Radio`; screens and the service read its flows. `RadioService` follows `Radio.serviceWanted`, which stays true from `start()` until the session it started has ended, and finishes only once it is in the foreground: stopping a service started with `startForegroundService()` before it called `startForeground()` ends the app (found by the emulator test).
9. **The spectrum is polled from the display's frame callback** (`Choreographer`), not from a Compose coroutine loop, which would keep Compose from ever being idle and stall its tests.
10. **Driver hand-off (§5.1):** the app asks every installed app that handles `iqsrc://`, the tested driver (`marto.rtl_tcp_andro`) first, on a free local port; a refusal shows the driver's message; no driver leads to the Play Store page. The emulator tests use a stand-in driver written in Java, because it runs in the test package's own process, which has no Kotlin runtime.
11. **Only network sources reconnect** (1, 2, 4, 8, 15 s, giving up after 120 s without a working connection; a connection that lasted 10 s resets the count). A lost dongle is a failure the user restarts, because the driver app must be asked again.
12. **Gains are remembered per control** (tuner, LNA, IF gain reduction, AGC) and put back after each reconnection.
13. **Blocks of about 20 ms:** `min(capacity, max(256, rate / 50))` samples (Plan 2 completion report, first Plan 3 item).
14. **Thread priorities through a hook:** `ThreadHook` is called once on the source and DSP threads; the app raises both to audio priority (§4.4). The performance-hint session follows in Plan 3b.

## Where this plan sits

| Plan | Scope | Spec milestone |
|------|-------|----------------|
| 1, 2 (done) | Core engine | M1 |
| **3a (this)** | Gradle, CI and emulator tests, driver hand-off, network sources, service, audio, receiver screen with spectrum. First installable APK | M2a |
| 3b | GL waterfall, spectrum gestures, diagnostics and benchmark, performance hint, battery prompt, start on attach, rate step-down | M2b |
| 4 | Sweep analyzer | M3 |
| 5 | Scanner and recording | M4 |
| 6 | Polish and release | M5 |

## Follow-ups and where they land

| Follow-up | Where |
|---|---|
| Blocks of about 20 ms and a queue sized by duration (Plan 2 report §6, first Plan 3 item) | Task 2 |
| The Android sink follows the `AudioSink` contract (never blocks, refills with silence) | Tasks 3 and 6 |
| Take the final source kind from `onSourceStarted`; never `stop()` on the main thread | Task 5 (`RadioSession` stops on its own thread) |
| DSP-thread priority hook | Task 2 (`ThreadHook`), Task 6 |
| `SimulatorSource` and `RtlTcpClient` report unexpected exceptions through `onError` | Task 2 |
| `PerformanceTest` skips, rather than passes, on a JVM without allocation counters | Task 2 |
| `RtlTcpClient.close()` guarded for two threads; `open()`/`close()` race at start-up | Task 2 |
| Reconnect creates a new client and carries frequency, rate and gain over | Task 5 |
| Diagnostics counters, error taxonomy, prompt-close tests, performance-hint session | Plan 3b |

## File Structure

| File | Responsibility |
|------|----------------|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew*` | Gradle build |
| `core/build.gradle.kts` | `:core` as a Kotlin/JVM library with JUnit 4 tests |
| `.github/workflows/build.yml` | CI: build, test, lint, publish the test build; emulator tests |
| `tools/ci-wait.sh` | Waits for a commit's jobs and prints failures |
| `core/src/main/kotlin/source/IqBlock.kt` (`BlockSizing`) | How many samples go into one block |
| `core/src/main/kotlin/engine/ReceiverSettings.kt` (`ThreadHook`) | Thread-priority hook |
| `core/src/main/kotlin/audio/AudioRing.kt` | Buffer between the DSP thread and the audio thread |
| `core/src/main/kotlin/display/FrequencyText.kt`, `SpectrumScale.kt` | Readout text and spectrum geometry |
| `core/src/main/kotlin/session/DriverUri.kt`, `SessionTypes.kt`, `RadioSession.kt` | Driver address; sources, states, messages; the session |
| `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/**` | The Android app module |
| `app/src/main/kotlin/…/SpectraApp.kt`, `MainActivity.kt` | Application (owns `Radio`) and the one activity |
| `app/src/main/kotlin/…/radio/Radio.kt`, `AudioPlayer.kt`, `SettingsStore.kt`, `RadioService.kt` | Radio holder, playback, saved settings, foreground service |
| `app/src/main/kotlin/…/ui/*.kt` | Theme, frequency readout, spectrum, source dialog, driver hand-off, receiver screen |
| `app/src/androidTest/**` | Emulator tests, fake rtl_tcp server, stand-in driver |
| `debug.keystore` | Public test signing key |

## How to read the steps

Three kinds of file instruction appear, always followed by a code block:

- **Create `path`:** the file does not exist yet; the block is its whole content.
- **Replace the whole of `path` with:** the file exists; the block is its whole new content.
- **In `path`, replace: … with: …** the first block occurs exactly once in the file; put the second block in its place. Each block holds whole lines.

---

### Task 1: Gradle build and CI for the core

Turns the repository into a Gradle project that builds `:core` and runs its tests on GitHub Actions, so every later task can be checked on CI. The local `tools/test-core.sh` stays: it is faster and needs no network.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `core/build.gradle.kts`, `.github/workflows/build.yml`, `tools/ci-wait.sh`
- Create (downloaded): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`

**Interfaces:**
- Produces:
  - `./gradlew :core:test` builds and tests the core with Kotlin 2.4.0 on JDK 17.
  - Workflow `Build` (`.github/workflows/build.yml`), job `build`, on every push and pull request.
  - `tools/ci-wait.sh [sha]`: waits for the jobs of a commit, prints `CI: <job>: <conclusion>` per job and the failure lines; exit 0 only when all succeeded.

- [ ] **Step 1: Create the branch and fetch the Gradle wrapper**

Run:

```bash
git checkout main && git pull --ff-only
git checkout -b plan-3a-android-app
mkdir -p gradle/wrapper
for f in gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar; do
  curl -sSfL -o "$f" "https://raw.githubusercontent.com/gradle/gradle/v9.6.1/$f"
done
chmod +x gradlew
sha256sum gradle/wrapper/gradle-wrapper.jar
```

Expected: the checksum is `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. (CI checks the jar again against Gradle's published checksums.)

- [ ] **Step 2: Write the files**

Create `settings.gradle.kts`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "spectrasdr"
include(":core")
```


Create `build.gradle.kts`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```


Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```


Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.5.0"
kotlin = "2.4.0"
composeBom = "2026.09.00"
activityCompose = "1.10.1"
coroutines = "1.10.2"
junit = "4.13.2"
androidxTestExt = "1.2.1"
androidxTestRunner = "1.6.2"
androidxTestRules = "1.6.1"

[libraries]
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```


Create `gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```


Create `core/build.gradle.kts`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
```


Create `.github/workflows/build.yml`:

```yaml
# SPDX-License-Identifier: GPL-3.0-or-later
name: Build

on:
  push:
    branches: ["**"]
  pull_request:

permissions:
  contents: write

concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17

      # Checks the wrapper jar against Gradle's published checksums and caches Gradle's files.
      - uses: gradle/actions/setup-gradle@v4

      - name: Build, test and lint
        shell: bash
        run: ./gradlew :core:test --stacktrace 2>&1 | tee build.log

      - name: Point out what failed
        if: failure()
        shell: bash
        run: |
          # The first lines that explain the failure become annotations on the run.
          grep -E "^e: |^w: .*error|error:|FAILED|> Could not|What went wrong|Caused by:|^\* What|Tests? .*failed|There were failing tests" build.log \
            | head -9 | while IFS= read -r line; do echo "::error::${line}"; done
          awk '/What went wrong/{f=1} f{print} /Try:/{exit}' build.log | head -25 | tr '\n' ' ' | cut -c1-900 | sed 's/^/::error title=What went wrong::/'
          echo

      - name: Keep the reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: |
            build.log
            core/build/reports/tests/test
          if-no-files-found: ignore
```


Create `tools/ci-wait.sh`:

```bash
#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Waits for the GitHub Actions jobs of a commit and prints what failed.
#
#   tools/ci-wait.sh            the jobs of HEAD (push it first)
#   tools/ci-wait.sh <sha>      the jobs of another commit
#
# Needs the GitHub CLI (gh), signed in with read access to the repository. Job logs are saved to
# ${CI_LOG_DIR:-/tmp/ci}/<job>.log. Exit code: 0 when every job succeeded, 1 otherwise, 2 when no
# job finished within the waiting time.
set -u
repo=$(git remote get-url origin | sed -E 's#(\.git)?$##; s#^.*[:/]([^/]+/[^/]+)$#\1#')
sha=${1:-$(git rev-parse HEAD)}
out=${CI_LOG_DIR:-/tmp/ci}
mkdir -p "$out"

runs=""
for _ in $(seq 1 "${CI_WAIT_ROUNDS:-70}"); do
  sleep 15
  runs=$(gh api "repos/$repo/commits/$sha/check-runs" \
    --jq '[.check_runs[] | .name + ":" + .status + ":" + (.conclusion // "-") + ":" + (.id|tostring)] | join(" ")' 2>/dev/null | tail -1)
  case "$runs" in
    "" | *:queued:* | *:in_progress:* | *:pending:* | *:waiting:*) ;;
    *) break ;;
  esac
done
case "$runs" in
  "" | *:queued:* | *:in_progress:* | *:pending:* | *:waiting:*)
    echo "CI: not finished: ${runs:-no jobs found for $sha}"
    exit 2
    ;;
esac

status=0
for run in $runs; do
  name=${run%%:*}
  id=${run##*:}
  conclusion=$(echo "$run" | cut -d: -f3)
  gh api "repos/$repo/actions/jobs/$id/logs" > "$out/$name.log" 2>/dev/null
  echo "CI: $name: $conclusion"
  if [ "$conclusion" != "success" ]; then
    status=1
    # Compiler errors, Gradle's summary, failed tests (the emulator job prints them from its result files).
    grep -E "e: file|error:|What went wrong|FAILED|<failure|AssertionError|Exception|SPECTRA" "$out/$name.log" \
      | grep -v -E "sh -c|script:|--stacktrace" | cut -c30-400 | head -n "${CI_FAIL_LINES:-40}"
  else
    grep -E "SPECTRA" "$out/$name.log" | grep -v -E "sh -c|script:|grep" | cut -c30-300 | head -n 10
  fi
done
exit $status
```

- [ ] **Step 3: Check that the core still passes locally**

Run: `tools/test-core.sh`
Expected: `OK (253 tests)`

- [ ] **Step 4: Commit and push**

```bash
chmod +x tools/ci-wait.sh
git add .github/workflows/build.yml build.gradle.kts core/build.gradle.kts gradle.properties gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradlew gradlew.bat settings.gradle.kts tools/ci-wait.sh
git commit -F - <<'MSG'
build: Gradle project for the core and a CI workflow

Gradle 9.6.1 through the wrapper, Kotlin 2.4.0 for :core with JUnit 4,
a version catalog with the Android libraries Plan 3a uses, and a GitHub
Actions workflow that runs the core tests on every push. tools/ci-wait.sh
waits for the jobs of a commit and prints what failed.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push -u origin plan-3a-android-app
```

- [ ] **Step 5: Check CI**

Run: `tools/ci-wait.sh`
Expected: `CI: build: success` (the order of the jobs varies).

---

### Task 2: Engine follow-ups for the app

Four small changes the Android app needs from the engine, each with its test: blocks of about 20 ms at low sample rates (Plan 2 completion report, first Plan 3 item), sources that report a listener's exception instead of ending the process, a client that can be closed from two threads and right after opening, and a hook that lets the app raise the priority of the engine's threads (§4.4).

**Files:**
- Modify: `core/src/main/kotlin/source/IqBlock.kt`, `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, `core/src/main/kotlin/source/SimulatorSource.kt`, `core/src/main/kotlin/source/WavIqFileSource.kt`, `core/src/main/kotlin/engine/ReceiverSettings.kt`, `core/src/main/kotlin/engine/Receiver.kt`
- Test (modify): `core/src/test/kotlin/source/CollectingListener.kt`, `core/src/test/kotlin/source/SimulatorSourceTest.kt`, `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`, `core/src/test/kotlin/engine/ReceiverTest.kt`, `core/src/test/kotlin/engine/PerformanceTest.kt`, `core/src/test/kotlin/sim/SimBandTest.kt`

**Interfaces:**
- Produces:
  - `BlockSizing.samplesPerBlock(sampleRate, capacity) = min(capacity, max(256, sampleRate / 50))` in `source/IqBlock.kt`; `RtlTcpClient`, `SimulatorSource` and `WavIqFileSource` fill blocks to that size, and `Receiver` sizes its queue from it.
  - `RtlTcpClient.close()` runs once even when two threads call it; a `close()` before the reader has connected ends the reader without `onStarted`. A `RuntimeException` on the reader thread (the listener's included) becomes `onError(SourceError.Io("Unexpected error: <message>"))`.
  - `SimulatorSource`: the same, with the message `The simulator stopped: <message>`.
  - `ThreadRole { SOURCE, DSP }` and `fun interface ThreadHook { fun started(role: ThreadRole) }` in `engine/ReceiverSettings.kt`; `Receiver(source, sink, initial, listener, threads: ThreadHook = ThreadHook { })` calls it once from each thread.

- [ ] **Step 1: Write the failing tests**

In `core/src/test/kotlin/source/CollectingListener.kt`, replace:

```kotlin

/** Test listener: copies blocks (up to [keepPerEpoch] samples per tune epoch) and records events. */
class CollectingListener(private val keepPerEpoch: Long = 1_000_000) : SourceListener {
    class Captured(val seq: Long, val epoch: Int, val centerHz: Long, val sampleRate: Int, val samples: FloatArray)

```

with:

```kotlin

/** Test listener: copies blocks (up to [keepPerEpoch] samples per tune epoch) and records events. */
open class CollectingListener(private val keepPerEpoch: Long = 1_000_000) : SourceListener {
    class Captured(val seq: Long, val epoch: Int, val centerHz: Long, val sampleRate: Int, val samples: FloatArray)

```


In `core/src/test/kotlin/source/SimulatorSourceTest.kt`, replace:

```kotlin
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Receiver
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverListener
```

with:

```kotlin
package io.github.radhwanalhajjaj.spectrasdr.core.source

import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Receiver
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverListener
```

In `core/src/test/kotlin/source/SimulatorSourceTest.kt`, replace:

```kotlin
        }
    }
}
```

with:

```kotlin
        }
    }

    @Test
    fun aListenerThatThrowsEndsTheStreamWithAnError() {
        val l = object : CollectingListener() {
            override fun onBlock(block: IqBlock) {
                block.release()
                throw IllegalStateException("listener bug")
            }
        }
        val source = SimulatorSource(emptyList(), speed = unpaced)
        source.open(l)
        assertTrue(l.stopped.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("The simulator stopped: listener bug", l.errors.single().message)
        source.close()
    }

    @Test
    fun blocksLastAboutTwentyMillisecondsAtLowRates() {
        val source = SimulatorSource(emptyList(), initialSampleRate = 240_000, speed = unpaced)
        val l = CollectingListener()
        source.open(l)
        l.awaitEpochSamples(epoch = 0, complex = 24_000)
        source.close()
        assertTrue(l.blocks.all { it.samples.size == 2 * 4_800 })
    }
}
```


In `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`, replace:

```kotlin

    @Test
    fun aListenerThatFailsAfterReleasingItsBlockIsReportedAsItself() {
        val uncaught = AtomicReference<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
```

with:

```kotlin

    @Test
    fun aListenerThatFailsEndsTheStreamWithItsOwnError() {
        val uncaught = AtomicReference<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
```

In `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`, replace:

```kotlin
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
```

with:

```kotlin
        try {
            FakeRtlTcpServer().start().use { server ->
                val l = object : CollectingListener() {
                    override fun onBlock(block: IqBlock) {
                        block.release()
                        throw IllegalStateException("listener failed")
                    }
                }
                val c = client(server.port)
                c.open(l)
                assertTrue(l.stopped.await(5, SECONDS))
                // Reported, not left to end the process; and not hidden by a second release of the block.
                assertEquals("Unexpected error: listener failed", (l.errors.single() as SourceError.Io).message)
                assertEquals(null, uncaught.get())
                c.close()
            }
```

In `core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt`, replace:

```kotlin
        }
    }
}
```

with:

```kotlin
        }
    }

    @Test
    fun aClientClosedRightAfterOpeningEndsQuietly() {
        repeat(20) {
            FakeRtlTcpServer().start().use { server ->
                val l = CollectingListener()
                val c = client(server.port)
                c.open(l)
                c.close()   // can run before the reader thread has made its socket
                assertTrue("the reader ended (round $it)", l.stopped.await(5, SECONDS))
                assertTrue(l.errors.isEmpty())
            }
        }
    }
}
```


In `core/src/test/kotlin/engine/ReceiverTest.kt`, replace:

```kotlin

    @Test
    fun aSourceErrorIsReportedAndEndsTheSession() {
        val source = ScriptedSource()
```

with:

```kotlin

    @Test
    fun theHostIsToldAboutTheSourceAndDspThreadsFromThoseThreads() {
        val seen = java.util.concurrent.ConcurrentHashMap<ThreadRole, String>()
        val source = ScriptedSource()
        val r = Receiver(source, CollectingSink(), ReceiverSettings(100_000_000, 240_000, Mode.NFM), object : ReceiverListener {}) { role ->
            seen[role] = Thread.currentThread().name
        }
        r.start()
        await("both threads") { seen.size == 2 }
        r.stop()
        assertEquals("receiver-dsp", seen[ThreadRole.DSP])
        assertEquals("scripted-source", seen[ThreadRole.SOURCE])
    }

    @Test
    fun aSourceErrorIsReportedAndEndsTheSession() {
        val source = ScriptedSource()
```


In `core/src/test/kotlin/engine/PerformanceTest.kt`, replace:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
```

with:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
```

In `core/src/test/kotlin/engine/PerformanceTest.kt`, replace:

```kotlin
    @Test
    fun theSignalPathAllocatesNothingOnceItRuns() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return   // not measurable on this JVM
        val thread = Thread.currentThread().id
        val rate = 960_000
```

with:

```kotlin
    @Test
    fun theSignalPathAllocatesNothingOnceItRuns() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("this JVM cannot count a thread's allocations", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        val thread = Thread.currentThread().id
        val rate = 960_000
```


In `core/src/test/kotlin/sim/SimBandTest.kt`, replace:

```kotlin
        }
        assertTrue(chain.stereo)
        assertEquals(0xD3B2, chain.rds!!.state.pi)
        assertEquals("SIM FM  ", chain.rds!!.state.ps)
        assertEquals("Simulated text", chain.rds!!.state.rt)
        val audio = sink.audio()
        val frames = sink.frames
```

with:

```kotlin
        }
        assertTrue(chain.stereo)
        val rds = chain.rds!!.state
        assertEquals(0xD3B2, rds.pi)
        assertEquals("SIM FM  ", rds.ps)
        assertEquals("Simulated text", rds.rt)
        val audio = sink.audio()
        val frames = sink.frames
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.SimulatorSourceTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClientTest io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverTest`
Expected: compilation fails with `unresolved reference 'ThreadRole'` (and nothing runs).

- [ ] **Step 3: Write the implementation**

In `core/src/main/kotlin/source/IqBlock.kt`, replace:

```kotlin
}

/** Thread-safe pool of equally sized blocks; allocates a new block when empty. */
class BlockPool(val blockCapacity: Int, preallocate: Int = 0) {
```

with:

```kotlin
}

/**
 * How many samples a source puts into one block (§4.3): a full block, or about 20 ms of samples
 * when that is less, so audio and spectrum frames stay smooth at low sample rates.
 */
object BlockSizing {
    const val BLOCKS_PER_SECOND = 50
    const val MIN_SAMPLES = 256

    fun samplesPerBlock(sampleRate: Int, capacity: Int): Int =
        minOf(capacity, maxOf(MIN_SAMPLES, sampleRate / BLOCKS_PER_SECOND))
}

/** Thread-safe pool of equally sized blocks; allocates a new block when empty. */
class BlockPool(val blockCapacity: Int, preallocate: Int = 0) {
```


In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin

import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockPool
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
```

with:

```kotlin

import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockPool
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockSizing
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin

    private val opened = AtomicBoolean(false)

    /** One client is one connection: open() works once; to reconnect, create a new client. */
```

with:

```kotlin

    private val opened = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)

    /** One client is one connection: open() works once; to reconnect, create a new client. */
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
     */
    override fun close() {
        if (closed) return
        closed = true
        val reader = readerThread?.takeIf { it !== Thread.currentThread() }
```

with:

```kotlin
     */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return   // two threads may close at once (Receiver.stop)
        closed = true
        val reader = readerThread?.takeIf { it !== Thread.currentThread() }
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
                return
            }
            output = s.getOutputStream()
            s.soTimeout = config.connectTimeoutMs
```

with:

```kotlin
                return
            }
            if (closed) return   // close() ran before this thread had made its socket
            output = s.getOutputStream()
            s.soTimeout = config.connectTimeoutMs
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
                applyInitialSettings()
            }
            listener.onStarted(currentInfo)
            s.soTimeout = config.readTimeoutMs
```

with:

```kotlin
                applyInitialSettings()
            }
            if (closed) return
            listener.onStarted(currentInfo)
            s.soTimeout = config.readTimeoutMs
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
        } catch (e: IOException) {
            if (!closed) listener.onError(SourceError.Io(e.message ?: "Connection error"))
        } finally {
            running = false
```

with:

```kotlin
        } catch (e: IOException) {
            if (!closed) listener.onError(SourceError.Io(e.message ?: "Connection error"))
        } catch (e: RuntimeException) {
            // A bug here or in the listener must end the stream with a report, not end the process.
            if (!closed) listener.onError(SourceError.Io("Unexpected error: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            running = false
```

In `core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt`, replace:

```kotlin
                        block = it
                    }
                    val got = SampleConverter.convert(format, buf, pos, whole - pos, b.samples, b.length, b.capacity - b.length)
                    b.length += got
                    pos += got * bps
                    if (b.length == b.capacity) {
                        block = null
                        deliver(listener, b)
```

with:

```kotlin
                        block = it
                    }
                    val fill = BlockSizing.samplesPerBlock(b.sampleRate, b.capacity)
                    val got = SampleConverter.convert(format, buf, pos, whole - pos, b.samples, b.length, fill - b.length)
                    b.length += got
                    pos += got * bps
                    if (b.length >= fill) {
                        block = null
                        deliver(listener, b)
```


In `core/src/main/kotlin/source/SimulatorSource.kt`, replace:

```kotlin
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
```

with:

```kotlin
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
```

In `core/src/main/kotlin/source/SimulatorSource.kt`, replace:

```kotlin
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            running = false
```

with:

```kotlin
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            // A bug here or in the listener must end the stream with a report, not end the process.
            listener.onError(SourceError.Io("The simulator stopped: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            running = false
```


In `core/src/main/kotlin/source/WavIqFileSource.kt`, replace:

```kotlin
            }
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(blockSize * format.bytesPerComplex)
                var seq = 0L
                var emitted = 0L
```

with:

```kotlin
            }
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(BlockSizing.samplesPerBlock(wav.sampleRate, blockSize) * format.bytesPerComplex)
                var seq = 0L
                var emitted = 0L
```


In `core/src/main/kotlin/engine/ReceiverSettings.kt`, replace:

```kotlin
 * then only asks for the stop and returns at once).
 */
interface ReceiverListener {
    /**
```

with:

```kotlin
 * then only asks for the stop and returns at once).
 */
/** The engine's long-running threads, as a [ThreadHook] is told about them. */
enum class ThreadRole { SOURCE, DSP }

/**
 * Called once on each of the engine's long-running threads, from that thread, when it starts
 * work: the host raises the thread's priority there (§4.4). It must not throw or block.
 */
fun interface ThreadHook {
    fun started(role: ThreadRole)
}

interface ReceiverListener {
    /**
```


In `core/src/main/kotlin/engine/Receiver.kt`, replace:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueue
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
```

with:

```kotlin
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockQueue
import io.github.radhwanalhajjaj.spectrasdr.core.source.BlockSizing
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqBlock
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
```

In `core/src/main/kotlin/engine/Receiver.kt`, replace:

```kotlin
    initial: ReceiverSettings,
    private val listener: ReceiverListener,
) {
    /**
```

with:

```kotlin
    initial: ReceiverSettings,
    private val listener: ReceiverListener,
    private val threads: ThreadHook = ThreadHook { },
) {
    /**
```

In `core/src/main/kotlin/engine/Receiver.kt`, replace:

```kotlin
    private val sourceListener = object : SourceListener {
        override fun onStarted(info: SourceInfo) {
            startedInfo = info
        }
```

with:

```kotlin
    private val sourceListener = object : SourceListener {
        override fun onStarted(info: SourceInfo) {
            runCatching { threads.started(ThreadRole.SOURCE) }
            startedInfo = info
        }
```

In `core/src/main/kotlin/engine/Receiver.kt`, replace:

```kotlin
                queuedRate = block.sampleRate
                queuedBlockSize = block.capacity
                queue.capacity = queueCapacity(block.sampleRate, block.capacity)
            }
            queue.offer(block)
```

with:

```kotlin
                queuedRate = block.sampleRate
                queuedBlockSize = block.capacity
                queue.capacity = queueCapacity(block.sampleRate, BlockSizing.samplesPerBlock(block.sampleRate, block.capacity))
            }
            queue.offer(block)
```

In `core/src/main/kotlin/engine/Receiver.kt`, replace:

```kotlin
    private fun run() {
        try {
            apply(settings)   // tunes the source before it is opened, so its first blocks are already right
            if (running) source.open(sourceListener)
```

with:

```kotlin
    private fun run() {
        try {
            runCatching { threads.started(ThreadRole.DSP) }
            apply(settings)   // tunes the source before it is opened, so its first blocks are already right
            if (running) source.open(sourceListener)
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.source.SimulatorSourceTest io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClientTest io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverTest io.github.radhwanalhajjaj.spectrasdr.core.engine.PerformanceTest io.github.radhwanalhajjaj.spectrasdr.core.sim.SimBandTest`
Expected: `OK (59 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (257 tests)`

- [ ] **Step 6: Commit and push**

```bash
git add core/src/main/kotlin/engine/Receiver.kt core/src/main/kotlin/engine/ReceiverSettings.kt core/src/main/kotlin/source/IqBlock.kt core/src/main/kotlin/source/SimulatorSource.kt core/src/main/kotlin/source/WavIqFileSource.kt core/src/main/kotlin/source/rtltcp/RtlTcpClient.kt core/src/test/kotlin/engine/PerformanceTest.kt core/src/test/kotlin/engine/ReceiverTest.kt core/src/test/kotlin/sim/SimBandTest.kt core/src/test/kotlin/source/CollectingListener.kt core/src/test/kotlin/source/SimulatorSourceTest.kt core/src/test/kotlin/source/rtltcp/RtlTcpClientTest.kt
git commit -F - <<'MSG'
feat(core): 20 ms blocks, sturdier sources and a thread hook

Sources deliver a block when it is full or holds about 20 ms, so audio
and spectrum stay smooth at low rates. A listener's exception ends the
stream with an error instead of the process. RtlTcpClient can be closed
from two threads and before it has connected. ThreadHook lets the app
raise the priority of the source and DSP threads.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 7: Check CI**

Run: `tools/ci-wait.sh`
Expected: every job ends with `success` (`CI: build: success`).

---

### Task 3: Audio ring buffer

The buffer between the receiver's DSP thread and the device's audio thread (§6.5, §11; the `AudioSink` contract of Plan 2's final review): it never blocks the writer, gives the reader audio or silence, and refills to its target level after an underrun.

**Files:**
- Create: `core/src/main/kotlin/audio/AudioRing.kt`
- Test (create): `core/src/test/kotlin/audio/AudioRingTest.kt`

**Interfaces:**
- Produces:
  - `class AudioRing(sampleRate: Int = 48_000, targetMillis: Int = 150) : AudioSink` with `targetFrames`, `capacityFrames` (twice the target), `underruns`, `droppedFrames`, `read(dst: FloatArray, frames: Int): Int` (stereo frames; returns how many are audio) and `clear()`. Mono input is written to both channels.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/audio/AudioRingTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class AudioRingTest {
    private fun ramp(from: Int, frames: Int, channels: Int) =
        FloatArray(frames * channels) { (from + it / channels).toFloat() }

    @Test
    fun playsSilenceUntilTheTargetLevelIsReached() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)   // target 100 frames, capacity 200
        val out = FloatArray(2 * 50)
        ring.write(ramp(1, 99, 2), 99, 2)
        assertEquals(0, ring.read(out, 50))
        assertTrue(out.all { it == 0f })
        ring.write(ramp(100, 1, 2), 1, 2)
        assertEquals(50, ring.read(out, 50))
        assertEquals(1f, out[0])
        assertEquals(50f, out[98])
        assertEquals(0L, ring.underruns)
    }

    @Test
    fun monoIsPlayedOnBothChannels() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 10)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), 10, 1)
        val out = FloatArray(2 * 3)
        assertEquals(3, ring.read(out, 3))
        assertArrayEquals(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f), out, 0f)
    }

    @Test
    fun anUnderrunIsPaddedWithSilenceAndRefillsToTheTarget() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)
        ring.write(ramp(1, 100, 2), 100, 2)
        val out = FloatArray(2 * 80)
        assertEquals(80, ring.read(out, 80))
        assertEquals(20, ring.read(out, 80))            // only 20 left: an underrun
        assertEquals(100f, out[2 * 19])
        assertEquals(0f, out[2 * 20])
        assertEquals(1L, ring.underruns)
        ring.write(ramp(1, 99, 2), 99, 2)
        assertEquals(0, ring.read(out, 80))             // still refilling
        assertEquals(1L, ring.underruns)                // silence while refilling is not another underrun
        ring.write(ramp(100, 1, 2), 1, 2)
        assertEquals(80, ring.read(out, 80))
    }

    @Test
    fun aFullBufferDropsWhatDoesNotFitAndNeverBlocks() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)
        ring.write(ramp(1, 250, 2), 250, 2)
        assertEquals(1f, ring.fillLevel(), 0f)
        assertEquals(50L, ring.droppedFrames)
        val out = FloatArray(2 * 200)
        assertEquals(200, ring.read(out, 200))
        assertEquals(200f, out[2 * 199])               // the first 200 frames were kept
    }

    @Test
    fun theFillLevelIsHalfAtTheTarget() {
        val ring = AudioRing(sampleRate = 48_000, targetMillis = 150)
        assertEquals(7_200, ring.targetFrames)
        ring.write(FloatArray(7_200), 7_200, 1)
        assertEquals(0.5f, ring.fillLevel(), 1e-6f)
    }

    @Test
    fun clearStartsOverFromSilence() {
        val ring = AudioRing(sampleRate = 1_000, targetMillis = 100)
        ring.write(ramp(1, 150, 2), 150, 2)
        val out = FloatArray(2 * 10)
        assertEquals(10, ring.read(out, 10))
        ring.clear()
        assertEquals(0f, ring.fillLevel(), 0f)
        assertEquals(0, ring.read(out, 10))
    }

    @Test
    fun oneWriterAndOneReaderLoseNothingAndKeepTheOrder() {
        val ring = AudioRing(sampleRate = 48_000, targetMillis = 20)
        val total = 400_000
        val writer = thread {
            var next = 0
            val buf = FloatArray(2 * 256)
            while (next < total) {
                val room = ((1f - ring.fillLevel()) * ring.capacityFrames).toInt()
                val n = minOf(256, room, total - next)
                if (n == 0) { Thread.yield(); continue }
                for (i in 0 until n) { buf[2 * i] = (next + i).toFloat(); buf[2 * i + 1] = -(next + i).toFloat() }
                ring.write(buf, n, 2)
                next += n
            }
        }
        var expected = 0
        val out = FloatArray(2 * 300)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (expected < total && System.nanoTime() < deadline) {
            val n = ring.read(out, 300)
            for (i in 0 until n) {
                assertEquals((expected + i).toFloat(), out[2 * i], 0f)
                assertEquals(-(expected + i).toFloat(), out[2 * i + 1], 0f)
            }
            expected += n
            if (n == 0 && !writer.isAlive && ring.fillLevel() * ring.capacityFrames < ring.targetFrames) {
                ring.write(FloatArray(2 * ring.targetFrames), ring.targetFrames, 2)   // let the tail out
                break
            }
        }
        writer.join(5_000)
        assertEquals(0L, ring.droppedFrames)
        assertTrue("read $expected of $total frames", expected >= total - ring.capacityFrames)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRingTest`
Expected: compilation fails with `unresolved reference 'AudioRing'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/audio/AudioRing.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.audio

import io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioSink

/**
 * The buffer between the receiver's DSP thread and the device's audio thread (§6.5, §11): an
 * [AudioSink] that never blocks its writer, and a [read] side that always has something to play.
 *
 * It holds stereo frames (mono audio is copied to both channels, so a change of mode needs no
 * change on the playing side). The target level is half the capacity, which is where the
 * receiver's drift compensation holds it. When the reader finds too little, it plays silence until
 * the buffer is back at the target: this happens at the start, after a retune, and after any
 * underrun.
 *
 * One thread may write and one may read; [fillLevel] and the counters may be read from any thread.
 */
class AudioRing(override val sampleRate: Int = 48_000, targetMillis: Int = 150) : AudioSink {
    init {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(targetMillis in 10..2_000) { "target $targetMillis ms must be between 10 and 2000" }
    }

    /** Frames the reader waits for before it plays; the buffer holds twice as many. */
    val targetFrames = (sampleRate.toLong() * targetMillis / 1000).toInt()
    val capacityFrames = 2 * targetFrames

    private val data = FloatArray(2 * capacityFrames)

    // Frames written and read since the start; each is changed by one thread only.
    @Volatile private var written = 0L
    @Volatile private var taken = 0L

    private var refilling = true   // reader's state

    /** Times the reader ran out of audio while playing. */
    @Volatile
    var underruns = 0L
        private set

    /** Frames the writer had to drop because the buffer was full. */
    @Volatile
    var droppedFrames = 0L
        private set

    override fun write(samples: FloatArray, frames: Int, channels: Int) {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        val w = written
        val free = capacityFrames - (w - taken).toInt()
        val n = minOf(frames, free)
        var at = (w % capacityFrames).toInt() * 2
        if (channels == 2) {
            for (i in 0 until 2 * n step 2) {
                data[at] = samples[i]
                data[at + 1] = samples[i + 1]
                at += 2
                if (at == data.size) at = 0
            }
        } else {
            for (i in 0 until n) {
                data[at] = samples[i]
                data[at + 1] = samples[i]
                at += 2
                if (at == data.size) at = 0
            }
        }
        written = w + n
        if (n < frames) droppedFrames += frames - n
    }

    override fun fillLevel(): Float = (written - taken).toFloat() / capacityFrames

    /**
     * Fills [dst] with [frames] stereo frames to play: audio when there is enough, silence while
     * the buffer refills. Returns how many of the frames are audio.
     */
    fun read(dst: FloatArray, frames: Int): Int {
        require(dst.size >= 2 * frames) { "destination too small" }
        val t = taken
        val available = (written - t).toInt()
        if (refilling && available >= targetFrames) refilling = false
        val n = if (refilling) 0 else minOf(frames, available)
        var at = (t % capacityFrames).toInt() * 2
        for (i in 0 until 2 * n step 2) {
            dst[i] = data[at]
            dst[i + 1] = data[at + 1]
            at += 2
            if (at == data.size) at = 0
        }
        dst.fill(0f, 2 * n, 2 * frames)
        taken = t + n
        if (!refilling && n < frames) {
            underruns++
            refilling = true
        }
        return n
    }

    /** Throws away what is buffered and waits for the target level again. Reader's thread only. */
    fun clear() {
        taken = written
        refilling = true
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRingTest`
Expected: `OK (7 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (264 tests)`

- [ ] **Step 6: Commit and push**

```bash
git add core/src/main/kotlin/audio/AudioRing.kt core/src/test/kotlin/audio/AudioRingTest.kt
git commit -F - <<'MSG'
feat(core): audio ring buffer between the DSP and audio threads

A non-blocking AudioSink for one writer and one reader. The reader gets
silence until the buffer is back at its target level, at the start and
after an underrun; the writer drops what does not fit.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 7: Check CI**

Run: `tools/ci-wait.sh`
Expected: every job ends with `success` (`CI: build: success`).

---

### Task 4: Display helpers and the driver address

Pure functions the screens use, kept in `:core` so they are tested locally: the frequency readout's digits, typed megahertz, locale-independent numbers, the spectrum's frequency and level scale, and the `iqsrc://` address that starts the SDR driver app (§5.1).

**Files:**
- Create: `core/src/main/kotlin/display/FrequencyText.kt`, `core/src/main/kotlin/display/SpectrumScale.kt`, `core/src/main/kotlin/session/DriverUri.kt`
- Test (create): `core/src/test/kotlin/display/DisplayTest.kt`

**Interfaces:**
- Produces:
  - `object FrequencyText`: `digits(hz): IntArray` (10 digits), `megahertz(hz)` ("100.300 000 MHz"), `short(hz)` ("100.3 MHz"), `fixed(value, decimals)` (`Locale.ROOT`), `parseMegahertz(text): Long?`, `turnDigit(hz, power, steps, min, max)`, `snap(hz, stepHz)`, `MAX_HZ`.
  - `class SpectrumScale(centerHz, sampleRate, topDb = 0f, bottomDb = -120f)` with `xOf(hz)`, `xOfBin(bin, bins)`, `hzAt(x)`, `yOf(db)`; `SpectrumScale.columns(db, bins, out)` keeps the strongest bin per column.
  - `object DriverUri` with `build(port, sampleRate, frequencyHz, ppm = 0)` → `iqsrc://-a 127.0.0.1 -p <port> -s <rate> -f <hz> -P <ppm>`, `DRIVER_PACKAGE`, and the result extras' names.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/display/DisplayTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.display

import io.github.radhwanalhajjaj.spectrasdr.core.session.DriverUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTest {
    @Test
    fun frequenciesAreShownInMegahertz() {
        assertEquals("100.300 000 MHz", FrequencyText.megahertz(100_300_000))
        assertEquals("0.531 000 MHz", FrequencyText.megahertz(531_000))
        assertEquals("1296.000 005 MHz", FrequencyText.megahertz(1_296_000_005))
        assertEquals("100.3 MHz", FrequencyText.short(100_300_000))
        assertEquals("145.5125 MHz", FrequencyText.short(145_512_500))
        assertEquals("100.0 MHz", FrequencyText.short(100_000_000))
        assertArrayEquals(intArrayOf(0, 1, 0, 0, 3, 0, 0, 0, 0, 0), FrequencyText.digits(100_300_000))
    }

    @Test
    fun typedFrequenciesAreRead() {
        assertEquals(100_300_000L, FrequencyText.parseMegahertz("100.3"))
        assertEquals(145_512_500L, FrequencyText.parseMegahertz(" 145,5125 "))
        assertEquals(7_100_000L, FrequencyText.parseMegahertz("7.1"))
        assertEquals(433_920_000L, FrequencyText.parseMegahertz("433.92"))
        assertEquals(1L, FrequencyText.parseMegahertz("0.0000014"))
        assertNull(FrequencyText.parseMegahertz(""))
        assertNull(FrequencyText.parseMegahertz("abc"))
        assertNull(FrequencyText.parseMegahertz("1.2.3"))
        assertNull(FrequencyText.parseMegahertz("-5"))
        assertNull(FrequencyText.parseMegahertz("10000"))
        assertNull(FrequencyText.parseMegahertz("99999999999999999999999"))
    }

    @Test
    fun aDigitTurnsWithCarryAndStopsAtTheLimits() {
        assertEquals(100_400_000L, FrequencyText.turnDigit(100_300_000, power = 5, steps = 1))
        assertEquals(101_000_000L, FrequencyText.turnDigit(100_900_000, power = 5, steps = 1))
        assertEquals(99_900_000L, FrequencyText.turnDigit(100_000_000, power = 5, steps = -1))
        assertEquals(0L, FrequencyText.turnDigit(300_000, power = 6, steps = -1))
        assertEquals(108_000_000L, FrequencyText.turnDigit(107_500_000, power = 6, steps = 1, min = 87_500_000, max = 108_000_000))
        assertEquals(100_312_500L, FrequencyText.snap(100_310_000, 12_500))
        assertEquals(100_300_000L, FrequencyText.snap(100_306_000, 12_500))
    }

    @Test
    fun theSpectrumScaleMapsFrequenciesAndLevels() {
        val s = SpectrumScale(centerHz = 100_000_000, sampleRate = 2_400_000, topDb = 0f, bottomDb = -120f)
        assertEquals(0.5f, s.xOf(100_000_000.0), 1e-6f)
        assertEquals(0f, s.xOf(98_800_000.0), 1e-6f)
        assertEquals(0.75f, s.xOf(100_600_000.0), 1e-6f)
        assertEquals(100_600_000L, s.hzAt(0.75f))
        assertEquals(0.5f, s.yOf(-60f), 1e-6f)
        assertEquals(1f, s.yOf(-200f), 0f)
        assertEquals(0f, s.yOf(10f), 0f)
        assertEquals(0.5f, s.xOfBin(2_048, 4_096), 2e-4f)
    }

    @Test
    fun columnsKeepTheStrongestBin() {
        val db = FloatArray(8) { -100f }
        db[5] = -20f
        val out = FloatArray(4)
        SpectrumScale.columns(db, 8, out)
        assertArrayEquals(floatArrayOf(-100f, -100f, -20f, -100f), out, 0f)
        val same = FloatArray(8)
        SpectrumScale.columns(db, 8, same)
        assertArrayEquals(db, same, 0f)
    }

    @Test
    fun theDriverAddressFollowsTheIqsrcConvention() {
        assertEquals(
            "iqsrc://-a 127.0.0.1 -p 14423 -s 2400000 -f 100300000 -P 0",
            DriverUri.build(port = 14423, sampleRate = 2_400_000, frequencyHz = 100_300_000),
        )
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.display.DisplayTest`
Expected: compilation fails with `unresolved reference 'FrequencyText'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/display/FrequencyText.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.display

import java.math.BigDecimal
import java.util.Locale

/** Frequencies as people read and type them. */
object FrequencyText {
    /** The highest frequency the readout can show: 9,999.999 999 MHz. */
    const val MAX_HZ = 9_999_999_999L

    /** Ten digits, most significant first, for a readout like 0100.300 000 (in MHz). */
    fun digits(hz: Long): IntArray {
        var v = hz.coerceIn(0, MAX_HZ)
        val d = IntArray(10)
        for (i in 9 downTo 0) {
            d[i] = (v % 10).toInt()
            v /= 10
        }
        return d
    }

    /** "100.300 000 MHz": megahertz with the kilohertz and hertz grouped. */
    fun megahertz(hz: Long): String {
        val v = hz.coerceIn(0, MAX_HZ)
        val mhz = v / 1_000_000
        val khz = v / 1_000 % 1_000
        val rest = v % 1_000
        return String.format(Locale.ROOT, "%d.%03d %03d MHz", mhz, khz, rest)
    }

    /** [value] with [decimals] places, always with Western digits and a point, whatever the device's language. */
    fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    /** A short form for labels and the notification: "100.3 MHz", "145.5125 MHz", "7.1 MHz". */
    fun short(hz: Long): String {
        val text = BigDecimal(hz.coerceAtLeast(0)).movePointLeft(6).stripTrailingZeros().toPlainString()
        return (if ('.' in text) text else "$text.0") + " MHz"
    }

    /**
     * Reads what a user typed as megahertz ("100.3", "145,5125", " 7.1 "); null when it is not a
     * number or outside the readout's range.
     */
    fun parseMegahertz(text: String): Long? {
        val cleaned = text.trim().replace(',', '.').replace(" ", "")
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it == '.' } || cleaned.count { it == '.' } > 1) return null
        val hz = runCatching { BigDecimal(cleaned).movePointRight(6).setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger() }.getOrNull() ?: return null
        return if (hz.bitLength() <= 62 && hz.toLong() in 0..MAX_HZ) hz.toLong() else null
    }

    /**
     * The frequency after turning the digit worth 10^[power] Hz by [steps] (negative turns down),
     * kept inside [min]..[max].
     */
    fun turnDigit(hz: Long, power: Int, steps: Int, min: Long = 0, max: Long = MAX_HZ): Long {
        require(power in 0..9) { "power must be 0–9" }
        var unit = 1L
        repeat(power) { unit *= 10 }
        return (hz + steps * unit).coerceIn(min, max)
    }

    /** [hz] moved to the nearest multiple of [stepHz]. */
    fun snap(hz: Long, stepHz: Int): Long {
        require(stepHz > 0) { "step must be positive" }
        return (hz + stepHz / 2) / stepHz * stepHz
    }
}
```


Create `core/src/main/kotlin/display/SpectrumScale.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.display

/**
 * Maps a spectrum frame onto a drawing area: bins and frequencies to a horizontal fraction
 * (0 = left edge, 1 = right edge), levels to a vertical fraction (0 = top, 1 = bottom).
 *
 * The frame spans [sampleRate] around [centerHz]; bin 0 is the lowest frequency.
 */
class SpectrumScale(val centerHz: Long, val sampleRate: Int, val topDb: Float = 0f, val bottomDb: Float = -120f) {
    init {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(topDb > bottomDb) { "the top level must be above the bottom level" }
    }

    val lowHz: Double get() = centerHz - sampleRate / 2.0

    /** Horizontal position of [hz]; outside 0–1 when the frequency is outside the frame. */
    fun xOf(hz: Double): Float = ((hz - lowHz) / sampleRate).toFloat()

    /** Horizontal position of the centre of [bin] out of [bins]. */
    fun xOfBin(bin: Int, bins: Int): Float = (bin + 0.5f) / bins

    /** The frequency at horizontal position [x]. */
    fun hzAt(x: Float): Long = Math.round(lowHz + x.toDouble() * sampleRate)

    /** Vertical position of [db], limited to the drawing area. */
    fun yOf(db: Float): Float = ((topDb - db) / (topDb - bottomDb)).coerceIn(0f, 1f)

    companion object {
        /**
         * Reduces [bins] levels to [out].size columns by taking the strongest bin in each column,
         * so narrow signals stay visible on a screen narrower than the FFT.
         */
        fun columns(db: FloatArray, bins: Int, out: FloatArray) {
            val n = out.size
            require(n in 1..bins) { "columns must be 1..bins" }
            for (c in 0 until n) {
                val from = (c.toLong() * bins / n).toInt()
                val to = maxOf(from + 1, ((c + 1).toLong() * bins / n).toInt())
                var m = db[from]
                for (i in from + 1 until to) if (db[i] > m) m = db[i]
                out[c] = m
            }
        }
    }
}
```


Create `core/src/main/kotlin/session/DriverUri.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

/** The address that asks an Android SDR driver app to start its local rtl_tcp server (§5.1). */
object DriverUri {
    const val SCHEME = "iqsrc"

    /** The driver app this project is tested with (SDR driver by SignalWare). */
    const val DRIVER_PACKAGE = "marto.rtl_tcp_andro"

    const val EXTRA_SUPPORTED_COMMANDS = "supportedTcpCommands"
    const val EXTRA_ERROR_MESSAGE = "detailed_exception_message"
    const val EXTRA_ERROR_CODE = "detailed_exception_code"

    fun build(port: Int, sampleRate: Int, frequencyHz: Long, ppm: Int = 0): String {
        require(port in 1..65535) { "port $port is outside 1..65535" }
        require(sampleRate > 0) { "sample rate must be positive" }
        require(frequencyHz >= 0) { "frequency must not be negative" }
        return "$SCHEME://-a 127.0.0.1 -p $port -s $sampleRate -f $frequencyHz -P $ppm"
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.display.DisplayTest`
Expected: `OK (6 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (270 tests)`

- [ ] **Step 6: Commit and push**

```bash
git add core/src/main/kotlin/display/FrequencyText.kt core/src/main/kotlin/display/SpectrumScale.kt core/src/main/kotlin/session/DriverUri.kt core/src/test/kotlin/display/DisplayTest.kt
git commit -F - <<'MSG'
feat(core): frequency text, spectrum scale and the driver address

Readout digits, typed megahertz and numbers that never use the device's
locale; the mapping of a spectrum frame onto the screen; the iqsrc://
address that asks an SDR driver app to start its rtl_tcp server.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 7: Check CI**

Run: `tools/ci-wait.sh`
Expected: every job ends with `success` (`CI: build: success`).

---

### Task 5: Radio session

Listening sessions as the app sees them (§10, §11): choose a source, start, change settings, stop; settings that outlive a session; reconnection of a lost network source with back-off, restoring frequency, rate, gain and options; plain-language errors. All of it runs on the JVM, so it is tested here and the Android layer stays thin.

**Files:**
- Create: `core/src/main/kotlin/session/SessionTypes.kt`, `core/src/main/kotlin/session/RadioSession.kt`
- Test (create): `core/src/test/kotlin/session/RadioSessionTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SourceSpec { Simulator; Network(host, port = 1234); Driver(port, supportedCommands: Set<Int>? = null) }`, each with `label`.
  - `sealed interface SessionState { Idle; Connecting(spec, attempt); Running(spec, info); Waiting(spec, attempt, seconds, reason); Failed(spec, message) }`.
  - `interface SessionListener { onState, onStatus, onRds, onMessage }` (all default to nothing); `fun interface SourceFactory { create(spec, settings): IqSource }` with `SourceFactory.Default`; `object SessionMessages { describe(error, spec); canRetry(error, spec) }`.
  - `class RadioSession(sink, initialSettings, listener, sources = SourceFactory.Default, backoffSeconds = listOf(1, 2, 4, 8, 15), giveUpSeconds = 120, stableSeconds = 10, nanoTime = System::nanoTime, threads = ThreadHook { }) : AutoCloseable` with `state`, `settings`, `fftSize`, `start(spec)`, `stop()`, `update(change)`, `setGain`, `setPpm`, `setOption`, `spectrum(dst)`, `close()`. Every method returns at once; work happens on its thread `radio-session`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/session/RadioSessionTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.CollectingSink
import io.github.radhwanalhajjaj.spectrasdr.core.testutil.ScriptedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class RadioSessionTest {
    /** A scripted source that also remembers the gain it was given. */
    private class Recording(val inner: ScriptedSource) : IqSource by inner {
        val gains = CopyOnWriteArrayList<GainSetting>()
        override fun setGain(gain: GainSetting) { gains += gain }
    }

    private class Events : SessionListener {
        val states = CopyOnWriteArrayList<SessionState>()
        val messages = CopyOnWriteArrayList<String>()
        @Volatile var status = ReceiverStatus()
        override fun onState(state: SessionState) { states += state }
        override fun onStatus(status: ReceiverStatus) { this.status = status }
        override fun onMessage(text: String) { messages += text }
    }

    private val made = CopyOnWriteArrayList<Recording>()
    private val events = Events()
    private val settings = ReceiverSettings(100_000_000, 240_000, Mode.NFM)
    private val network = SourceSpec.Network("radio.example", 1234)
    private var session: RadioSession? = null

    private fun session(
        backoff: List<Int> = listOf(0),
        giveUpSeconds: Int = 120,
        clock: () -> Long = System::nanoTime,
        factory: SourceFactory = SourceFactory { _, _ -> Recording(ScriptedSource()).also { made += it } },
    ) = RadioSession(CollectingSink(), settings, events, factory, backoff, giveUpSeconds, stableSeconds = 10, nanoTime = clock).also { session = it }

    @After
    fun closeSession() {
        session?.close()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what; states: ${events.states}")
            Thread.sleep(2)
        }
    }

    private fun awaitRunning(count: Int = 1) =
        await("running $count time(s)") { events.states.count { it is SessionState.Running } >= count && events.status.tuned }

    @Test
    fun startsListeningAndStopsAgain() {
        val s = session()
        assertEquals(SessionState.Idle, s.state)
        s.start(SourceSpec.Simulator)
        awaitRunning()
        assertTrue(events.states[0] is SessionState.Connecting)
        assertEquals(SourceSpec.Simulator, (s.state as SessionState.Running).spec)
        s.stop()
        await("idle") { s.state == SessionState.Idle }
        assertEquals("close", made.single().inner.calls.last())
    }

    @Test
    fun settingsChangedWhileListeningAreKeptForTheNextSession() {
        val s = session()
        s.start(SourceSpec.Simulator)
        awaitRunning()
        s.update { it.copy(frequencyHz = 145_500_000) }
        await("the retune") { "freq" in made[0].inner.calls.last() || made[0].inner.calls.any { it.startsWith("freq 1455") || it.startsWith("freq 1454") } }
        s.stop()
        await("idle") { s.state == SessionState.Idle }
        assertEquals(145_500_000, s.settings.frequencyHz)
        s.update { it.copy(volume = 0.25f) }                    // while idle
        s.start(SourceSpec.Simulator)
        awaitRunning(2)
        assertEquals(145_500_000, s.settings.frequencyHz)
        assertEquals(0.25f, s.settings.volume)
        assertTrue("the new source was tuned near the kept frequency: ${made[1].inner.calls}",
            made[1].inner.calls.any { it.startsWith("freq 1454") || it.startsWith("freq 1455") })
    }

    @Test
    fun anInvalidSettingsChangeReachesTheCallerAndChangesNothing() {
        val s = session()
        try {
            s.update { it.copy(volume = 2f) }
            fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1f, s.settings.volume)
    }

    @Test
    fun aLostNetworkSourceIsReconnectedWithTheSameSettingsAndGain() {
        val s = session(backoff = listOf(0))
        s.setGain(GainSetting.TunerTenthsDb(100))
        s.setGain(GainSetting.TunerTenthsDb(297))          // replaces the one before
        s.setGain(GainSetting.RspLna(3))                   // a separate control: kept as well
        s.start(network)
        awaitRunning()
        s.update { it.copy(frequencyHz = 145_500_000) }
        made[0].inner.fail(SourceError.StreamEnded("The server closed the connection"))
        awaitRunning(2)
        val waiting = events.states.filterIsInstance<SessionState.Waiting>().single()
        assertEquals(1, waiting.attempt)
        assertEquals("The connection to radio.example:1234 was lost.", waiting.reason)
        assertEquals(2, made.size)
        assertEquals(setOf<GainSetting>(GainSetting.TunerTenthsDb(297), GainSetting.RspLna(3)), made[1].gains.toSet())
        assertEquals(2, made[1].gains.size)
        assertEquals(145_500_000, s.settings.frequencyHz)
    }

    @Test
    fun reconnectingGivesUpAfterTheLimit() {
        val now = AtomicLong(0)
        val s = session(backoff = listOf(0), giveUpSeconds = 120, clock = { now.get() }) { _, _ ->
            Recording(ScriptedSource()).also { made += it; it.inner.fail(SourceError.ConnectFailed("Cannot connect")) }
        }
        s.start(network)
        await("some attempts") { made.size >= 3 }
        now.set(121_000_000_000L)
        await("giving up") { s.state is SessionState.Failed }
        val failed = s.state as SessionState.Failed
        assertTrue(failed.message, failed.message.endsWith("Gave up after trying for 120 seconds."))
        val count = made.size
        Thread.sleep(100)
        assertEquals("no further attempts", count, made.size)
    }

    @Test
    fun aSourceThatIsNotAnRtlTcpServerFailsAtOnce() {
        val s = session { _, _ ->
            Recording(ScriptedSource()).also { made += it; it.inner.fail(SourceError.ProtocolError("Not an rtl_tcp server (unexpected header)")) }
        }
        s.start(network)
        await("failure") { s.state is SessionState.Failed }
        assertEquals(1, made.size)
        assertEquals(
            "radio.example:1234 is not an rtl_tcp or rsp_tcp server. (Not an rtl_tcp server (unexpected header))",
            (s.state as SessionState.Failed).message,
        )
    }

    @Test
    fun theDongleIsNotReconnectedAutomatically() {
        val s = session()
        s.start(SourceSpec.Driver(port = 14423))
        awaitRunning()
        made[0].inner.fail(SourceError.StreamEnded("The server closed the connection"))
        await("failure") { s.state is SessionState.Failed }
        assertEquals("The dongle stopped sending samples. It may have been unplugged.", (s.state as SessionState.Failed).message)
        assertEquals(1, made.size)
    }

    @Test
    fun stoppingWhileWaitingCancelsTheReconnection() {
        val s = session(backoff = listOf(30)) { _, _ ->
            Recording(ScriptedSource()).also { made += it; it.inner.fail(SourceError.ConnectFailed("Cannot connect")) }
        }
        s.start(network)
        await("waiting") { s.state is SessionState.Waiting }
        assertEquals(30, (s.state as SessionState.Waiting).seconds)
        s.stop()
        await("idle") { s.state == SessionState.Idle }
        assertEquals(1, made.size)
    }

    @Test
    fun startingAnotherSourceEndsTheFirst() {
        val s = session()
        s.start(SourceSpec.Simulator)
        awaitRunning()
        s.start(network)
        await("the second source") { (s.state as? SessionState.Running)?.spec == network }
        assertEquals("close", made[0].inner.calls.last())
        assertEquals(2, made.size)
    }

    @Test
    fun aSourceThatCannotBeBuiltIsReportedAsAFailure() {
        val s = session { _, _ -> throw IllegalArgumentException("port 0 is outside 1..65535") }
        s.start(network)
        await("failure") { s.state is SessionState.Failed }
        assertEquals("Cannot use radio.example:1234: port 0 is outside 1..65535", (s.state as SessionState.Failed).message)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.session.RadioSessionTest`
Expected: compilation fails with `unresolved reference 'SessionListener'`.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/session/SessionTypes.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SimulatorSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpClient
import io.github.radhwanalhajjaj.spectrasdr.core.source.rtltcp.RtlTcpConfig

/** Where the samples come from, as the user chooses it. */
sealed interface SourceSpec {
    /** Short name for lists and the notification. */
    val label: String

    /** The built-in synthetic band; needs no hardware. */
    data object Simulator : SourceSpec {
        override val label get() = "Simulator"
    }

    /** An rtl_tcp or rsp_tcp server on the network; which one is found out when connecting. */
    data class Network(val host: String, val port: Int = 1234) : SourceSpec {
        init {
            require(host.isNotBlank()) { "host must not be blank" }
            require(port in 1..65535) { "port $port is outside 1..65535" }
        }

        override val label get() = "$host:$port"
    }

    /**
     * The Android SDR driver app, already started and listening on this device (§5.1).
     * [supportedCommands] is what the driver reported; null means every command.
     */
    data class Driver(val port: Int, val supportedCommands: Set<Int>? = null) : SourceSpec {
        init {
            require(port in 1..65535) { "port $port is outside 1..65535" }
        }

        override val label get() = "USB dongle"
    }
}

/** What a [RadioSession] is doing. */
sealed interface SessionState {
    /** Nothing is running. */
    data object Idle : SessionState

    /** Opening the source; [attempt] is 0 for the first try and counts reconnections after that. */
    data class Connecting(val spec: SourceSpec, val attempt: Int) : SessionState

    /** Samples are arriving. */
    data class Running(val spec: SourceSpec, val info: SourceInfo) : SessionState

    /** The network source was lost; the next try starts in [seconds]. */
    data class Waiting(val spec: SourceSpec, val attempt: Int, val seconds: Int, val reason: String) : SessionState

    /** The session ended with an error the user has to act on; [message] is in plain language. */
    data class Failed(val spec: SourceSpec, val message: String) : SessionState
}

/**
 * What a session reports. [onState] and [onMessage] come from the session's own thread or the
 * receiver's DSP thread, [onStatus] and [onRds] from the DSP thread: return quickly from all of them.
 */
interface SessionListener {
    fun onState(state: SessionState) {}
    fun onStatus(status: ReceiverStatus) {}
    fun onRds(state: RdsState) {}

    /** A notice for the user, in plain language (a refused frequency, a slow link). */
    fun onMessage(text: String) {}
}

/** Builds the source for a [SourceSpec]; replaced in tests. */
fun interface SourceFactory {
    fun create(spec: SourceSpec, settings: ReceiverSettings): IqSource

    companion object {
        val Default = SourceFactory { spec, settings ->
            when (spec) {
                SourceSpec.Simulator -> SimulatorSource()
                is SourceSpec.Network -> RtlTcpClient(
                    RtlTcpConfig(
                        host = spec.host,
                        port = spec.port,
                        name = spec.label,
                        initialFrequencyHz = settings.frequencyHz.coerceIn(0, 0xFFFF_FFFFL),
                        initialSampleRate = settings.sampleRate,
                    ),
                )
                is SourceSpec.Driver -> RtlTcpClient(
                    RtlTcpConfig(
                        host = "127.0.0.1",
                        port = spec.port,
                        kind = SourceKind.ANDROID_DRIVER,
                        name = spec.label,
                        initialFrequencyHz = settings.frequencyHz.coerceIn(0, 0xFFFF_FFFFL),
                        initialSampleRate = settings.sampleRate,
                        supportedCommands = spec.supportedCommands,
                        detectExtended = false,
                        sendExitOnClose = true,
                    ),
                )
            }
        }
    }
}

/** Plain-language text for the errors a source reports (§11). */
object SessionMessages {
    fun describe(error: SourceError, spec: SourceSpec): String = when (error) {
        is SourceError.ConnectFailed -> when (spec) {
            is SourceSpec.Driver -> "The SDR driver did not answer. Start the dongle again. (${error.message})"
            else -> "Could not connect to ${spec.label}. Check that the server is running and that this device can reach it. (${error.message})"
        }
        is SourceError.StreamEnded -> when (spec) {
            is SourceSpec.Driver -> "The dongle stopped sending samples. It may have been unplugged."
            is SourceSpec.Network -> "The connection to ${spec.label} was lost."
            SourceSpec.Simulator -> "The simulator stopped."
        }
        is SourceError.ProtocolError -> "${spec.label} is not an rtl_tcp or rsp_tcp server. (${error.message})"
        is SourceError.DriverRejected -> error.message
        is SourceError.DriverNotInstalled -> error.message
        is SourceError.Io -> "The connection to ${spec.label} failed. (${error.message})"
    }

    /** Whether trying again by itself can help. */
    fun canRetry(error: SourceError, spec: SourceSpec): Boolean =
        spec is SourceSpec.Network && (error is SourceError.ConnectFailed || error is SourceError.StreamEnded || error is SourceError.Io)
}
```


Create `core/src/main/kotlin/session/RadioSession.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.session

import io.github.radhwanalhajjaj.spectrasdr.core.engine.AudioSink
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Receiver
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverListener
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ThreadHook
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.source.IqSource
import io.github.radhwanalhajjaj.spectrasdr.core.source.OptionKind
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceError
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceInfo
import io.github.radhwanalhajjaj.spectrasdr.core.source.SourceOption
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Listening sessions as the app sees them: pick a source, start, change settings, stop. It builds
 * the source and the [Receiver], keeps the user's settings from one session to the next, and
 * reconnects a lost network source with a growing delay, restoring frequency, rate and gain (§11).
 *
 * Every method may be called from any thread and returns at once; opening, stopping and
 * reconnecting happen on the session's own thread, because stopping a receiver can take seconds.
 */
class RadioSession(
    private val sink: AudioSink,
    initialSettings: ReceiverSettings,
    private val listener: SessionListener,
    private val sources: SourceFactory = SourceFactory.Default,
    /** Seconds to wait before each reconnection; the last value repeats. */
    private val backoffSeconds: List<Int> = listOf(1, 2, 4, 8, 15),
    /** Reconnecting stops after this long without a working connection. */
    private val giveUpSeconds: Int = 120,
    /** A connection that lasted this long counts as working again. */
    private val stableSeconds: Int = 10,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Told about each receiver's source and DSP threads, to raise their priority. */
    private val threads: ThreadHook = ThreadHook { },
) : AutoCloseable {
    init {
        require(backoffSeconds.isNotEmpty() && backoffSeconds.all { it >= 0 }) { "back-off needs at least one delay, none negative" }
    }

    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "radio-session").apply { isDaemon = true } }
    private val settingsLock = Any()

    @Volatile private var wanted = initialSettings
    @Volatile private var receiver: Receiver? = null
    @Volatile private var source: IqSource? = null
    @Volatile private var generation = 0

    // What the user set on the source; put back after every (re)connection. Gains are kept one
    // per control: the tuner's gain, and for an RSP its LNA state, IF gain reduction and AGC.
    private val gains = ConcurrentHashMap<String, GainSetting>()
    @Volatile private var ppm: Int? = null
    private val options = ConcurrentHashMap<OptionKind, SourceOption>()

    // Session thread only.
    private var spec: SourceSpec? = null
    private var attempt = 0
    private var failingSinceNs = 0L
    private var failing = false
    private var runningSinceNs = 0L
    private var retry: ScheduledFuture<*>? = null

    /** What the session is doing now. */
    @Volatile
    var state: SessionState = SessionState.Idle
        private set

    /** The settings in force while running, and the ones the next session starts with. */
    val settings: ReceiverSettings get() = receiver?.settings ?: wanted

    /** FFT size of the spectrum frames [spectrum] delivers now. */
    val fftSize: Int get() = receiver?.fftSize ?: wanted.fftSize

    /** Starts listening to [spec], ending whatever ran before. */
    fun start(spec: SourceSpec) {
        executor.execute {
            stopNow()
            this.spec = spec
            attempt = 0
            failing = false
            connect()
        }
    }

    /** Stops listening. */
    fun stop() {
        executor.execute {
            stopNow()
            spec = null
            publish(SessionState.Idle)
        }
    }

    /**
     * Changes the settings: of the running receiver, and of the sessions that follow. [change]
     * must be quick; an exception it throws (an invalid combination) reaches the caller and
     * changes nothing.
     */
    fun update(change: (ReceiverSettings) -> ReceiverSettings) {
        synchronized(settingsLock) {
            val r = receiver
            if (r != null) {
                r.update(change)
                wanted = r.settings
            } else {
                wanted = change(wanted)
            }
        }
    }

    fun setGain(gain: GainSetting) {
        gains[controlOf(gain)] = gain
        runCatching { source?.setGain(gain) }
    }

    fun setPpm(ppm: Int) {
        this.ppm = ppm
        runCatching { source?.setPpm(ppm) }
    }

    fun setOption(option: SourceOption) {
        options[option.kind] = option
        runCatching { source?.setOption(option) }
    }

    /** Copies the newest spectrum frame into [dst] if there is one it has not seen; any thread. */
    fun spectrum(dst: SpectrumFrame): Boolean = receiver?.spectrum(dst) ?: false

    /** Ends the session for good. */
    override fun close() {
        executor.execute { stopNow() }
        executor.shutdown()
    }

    // ---- session thread ----

    private fun connect() {
        val spec = spec ?: return
        val gen = ++generation
        publish(SessionState.Connecting(spec, attempt))
        val src = try {
            sources.create(spec, wanted)
        } catch (e: RuntimeException) {
            publish(SessionState.Failed(spec, "Cannot use ${spec.label}: ${e.message}"))
            return
        }
        val r = Receiver(src, sink, wanted, Events(gen, spec, src), threads)
        synchronized(settingsLock) {
            source = src
            receiver = r
        }
        r.start()
    }

    /** Stops the receiver, if any, and forgets it; later events from it are ignored. */
    private fun stopNow() {
        retry?.cancel(false)
        retry = null
        generation++
        val r = receiver ?: return
        r.stop()
        forget(r)
    }

    private fun forget(r: Receiver) {
        synchronized(settingsLock) {
            if (receiver === r) {
                wanted = r.settings
                receiver = null
                source = null
            }
        }
    }

    private fun started(spec: SourceSpec, src: IqSource, info: SourceInfo) {
        runningSinceNs = nanoTime()
        for (g in gains.values) runCatching { src.setGain(g) }
        ppm?.let { p -> runCatching { src.setPpm(p) } }
        for (o in options.values) if (o.kind in info.options) runCatching { src.setOption(o) }
        publish(SessionState.Running(spec, info))
    }

    private fun ended(spec: SourceSpec, r: Receiver, error: SourceError?) {
        forget(r)
        if (error == null) {
            publish(SessionState.Idle)
            return
        }
        val reason = SessionMessages.describe(error, spec)
        if (!SessionMessages.canRetry(error, spec)) {
            publish(SessionState.Failed(spec, reason))
            return
        }
        val now = nanoTime()
        val wasStable = state is SessionState.Running && now - runningSinceNs >= stableSeconds * 1_000_000_000L
        if (!failing || wasStable) {
            failing = true
            failingSinceNs = now
            attempt = 0
        }
        if (now - failingSinceNs >= giveUpSeconds * 1_000_000_000L) {
            publish(SessionState.Failed(spec, "$reason Gave up after trying for $giveUpSeconds seconds."))
            return
        }
        val seconds = backoffSeconds[minOf(attempt, backoffSeconds.lastIndex)]
        attempt++
        publish(SessionState.Waiting(spec, attempt, seconds, reason))
        retry = executor.schedule({ retry = null; connect() }, seconds.toLong(), TimeUnit.SECONDS)
    }

    private fun controlOf(gain: GainSetting): String = when (gain) {
        is GainSetting.Auto, is GainSetting.TunerTenthsDb, is GainSetting.TunerIndex -> "tuner"
        is GainSetting.RspLna -> "lna"
        is GainSetting.RspIfgr -> "ifgr"
        is GainSetting.RspAgc -> "agc"
    }

    private fun publish(next: SessionState) {
        state = next
        listener.onState(next)
    }

    /** One receiver's events; anything from a receiver that is no longer the current one is dropped. */
    private inner class Events(private val gen: Int, private val spec: SourceSpec, private val src: IqSource) : ReceiverListener {
        @Volatile private var error: SourceError? = null

        private val current get() = gen == generation

        override fun onSourceStarted(info: SourceInfo) {
            executor.execute { if (current) started(spec, src, info) }
        }

        override fun onStatus(status: ReceiverStatus) {
            if (current) listener.onStatus(status)
        }

        override fun onRds(state: RdsState) {
            if (current) listener.onRds(state)
        }

        override fun onWarning(message: String) {
            if (current) listener.onMessage(message)
        }

        override fun onSourceError(error: SourceError) {
            this.error = error
        }

        override fun onStopped() {
            val r = receiver
            runCatching { executor.execute { if (current && r != null) ended(spec, r, error) } }
        }
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `tools/test-core.sh io.github.radhwanalhajjaj.spectrasdr.core.session.RadioSessionTest`
Expected: `OK (10 tests)`

- [ ] **Step 5: Run everything**

Run: `tools/test-core.sh`
Expected: `OK (280 tests)`

- [ ] **Step 6: Commit and push**

```bash
git add core/src/main/kotlin/session/RadioSession.kt core/src/main/kotlin/session/SessionTypes.kt core/src/test/kotlin/session/RadioSessionTest.kt
git commit -F - <<'MSG'
feat(core): radio session with reconnection

Builds the source and the receiver for a chosen source, keeps the
settings from one session to the next, and reconnects a lost network
source with back-off, putting frequency, rate, gain and options back.
Errors come out in plain language.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 7: Check CI**

Run: `tools/ci-wait.sh`
Expected: every job ends with `success` (`CI: build: success`).

---

### Task 6: The Android app

The first installable app (milestone 2a): the `:app` module with the radio holder, the audio player, the foreground service, the driver hand-off and the receiver screen. Android code cannot be built in the workspace (D7), so this task is checked on CI: the `build` job must compile, lint and publish the APK.

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/drawable/ic_radio.xml`, `app/src/main/res/drawable/ic_launcher.xml`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/SpectraApp.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/MainActivity.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/Radio.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/AudioPlayer.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/SettingsStore.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/RadioService.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/Theme.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/FrequencyReadout.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/SpectrumView.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/SourceDialog.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/DriverStarter.kt`, `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/ReceiverScreen.kt`
- Modify: `settings.gradle.kts`, `.github/workflows/build.yml`
- Create (generated): `debug.keystore`

**Interfaces:**
- Produces:
  - Package `io.github.radhwanalhajjaj.spectrasdr`; `SpectraApp.radio` and `Context.radio`.
  - `Radio` (one per process): flows `state`, `status`, `rds`, `settings`, `gain`, `rspGain`, `serviceWanted`, `messages`; `start(spec)`, `stop()`, `update(change)`, `setMode`, `setGain`, `setRspGain`, `setSampleRate`, `setPaused`, `spectrum(dst)`; `ring: AudioRing`, `player: AudioPlayer` (`framesPlayed`).
  - Test tags used by Task 7: `listen`, `stop`, `source`, `source-simulator`, `source-server`, `source-dongle`, `server-host`, `server-port`, `source-start`, `type-frequency`, `frequency-field`, `frequency-ok`, `mode-<MODE>`, `stereo`, `rds-name`, `rds-text`, `level`, `state`, `spectrum`, `frequency`, `digit<power>`, `auto-squelch`, `auto-gain`, `rate-<sps>`.
  - CI publishes `app-release.apk` as `spectrasdr-test.apk` on the pre-release `test-build`.

- [ ] **Step 1: Generate the test signing key**

Run:

```bash
keytool -genkeypair -keystore debug.keystore -storetype PKCS12 -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=SpectraSDR Debug, O=SpectraSDR, C=US"
```

Expected: `debug.keystore` exists (about 2.7 KB). It is public on purpose (decision 4): never use it for a real release.

- [ ] **Step 2: Write the files**

In `settings.gradle.kts`, replace:

```kotlin

rootProject.name = "spectrasdr"
include(":core")
```

with:

```kotlin

rootProject.name = "spectrasdr"
include(":core", ":app")
```


Create `app/build.gradle.kts`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.radhwanalhajjaj.spectrasdr"
    compileSdk = 37   // Compose 1.12 requires API 37 to compile; targetSdk stays 36

    defaultConfig {
        applicationId = "io.github.radhwanalhajjaj.spectrasdr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed, public key for test builds, so that each one installs over the previous one.
        create("test") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
        // The build people install and the emulator tests run: not debuggable, because a debuggable
        // app runs its signal processing several times slower.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }
    testBuildType = "release"

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
```


Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- SDR driver apps start their local server through an iqsrc:// address (Android 11+ must declare it). -->
    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="iqsrc" />
        </intent>
    </queries>

    <application
        android:name=".SpectraApp"
        android:allowBackup="false"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".radio.RadioService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>

</manifest>
```


Create `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<resources>
    <string name="app_name">SpectraSDR</string>
    <string name="channel_listening">Listening</string>
    <string name="action_stop">Stop</string>
    <string name="state_starting">Starting…</string>
    <string name="state_connecting">Connecting to %1$s…</string>
    <string name="state_waiting">Connection lost. Trying again in %1$d s.</string>
</resources>
```


Create `app/src/main/res/drawable/ic_radio.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- A small spectrum: three bars over a base line. Original artwork for this project. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M3,19h18v2H3z M5,11h3v7H5z M10.5,4h3v14h-3z M16,8h3v10h-3z" />
</vector>
```


Create `app/src/main/res/drawable/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Launcher icon: a spectrum trace with one strong signal on a dark tile. Original artwork for this project. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FF10151C"
        android:pathData="M0,0h108v108H0z" />
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FF4DD0E1"
        android:strokeWidth="4"
        android:strokeLineJoin="round"
        android:strokeLineCap="round"
        android:pathData="M24,74 L34,70 L40,73 L46,66 L50,71 L54,30 L58,71 L64,67 L70,73 L76,69 L84,74" />
    <path
        android:fillColor="#FF37474F"
        android:pathData="M24,80h60v3H24z" />
</vector>
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/SpectraApp.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.app.Application
import android.content.Context
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio

class SpectraApp : Application() {
    /** The one radio of the process: the screens and the service both use it. */
    val radio: Radio by lazy { Radio(this) }
}

val Context.radio: Radio get() = (applicationContext as SpectraApp).radio
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/MainActivity.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import io.github.radhwanalhajjaj.spectrasdr.ui.ReceiverScreen
import io.github.radhwanalhajjaj.spectrasdr.ui.SpectraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark bars with light icons: the app is dark whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SpectraTheme {
                ReceiverScreen(radio)
            }
        }
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/Radio.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.content.Context
import android.os.Process
import io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRing
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.session.RadioSession
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionListener
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's radio: one [RadioSession] with its audio output, and its state as flows for the
 * screens. It lives as long as the process; [RadioService] keeps the process alive while it plays.
 */
class Radio(private val context: Context) : SessionListener {
    private val store = SettingsStore(context)

    val ring = AudioRing()
    val player = AudioPlayer(ring)
    private val session = RadioSession(
        ring, store.loadSettings(), this,
        // The source and DSP threads feed the audio: they run at audio priority (§4.4).
        threads = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _status = MutableStateFlow(ReceiverStatus())
    private val _rds = MutableStateFlow(RdsState())
    private val _settings = MutableStateFlow(session.settings)
    private val _gain = MutableStateFlow<GainSetting>(GainSetting.Auto)
    private val _rspGain = MutableStateFlow(RspGain())
    private val _serviceWanted = MutableStateFlow(false)
    private val serviceLock = Any()
    private var startsPending = 0   // start() calls the session has not begun yet; guarded by serviceLock
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val state: StateFlow<SessionState> get() = _state
    val status: StateFlow<ReceiverStatus> get() = _status
    val rds: StateFlow<RdsState> get() = _rds
    val settings: StateFlow<ReceiverSettings> get() = _settings
    /** The tuner gain of an RTL-SDR: automatic or one of its steps. */
    val gain: StateFlow<GainSetting> get() = _gain

    /** The gain controls of an SDRplay receiver. */
    val rspGain: StateFlow<RspGain> get() = _rspGain
    val messages: SharedFlow<String> get() = _messages

    /**
     * Whether [RadioService] should be running: from [start] until the session it started has ended.
     * The service follows this instead of the session's state, because a session that is still being
     * stopped reports "idle" after the next one was already asked for.
     */
    val serviceWanted: StateFlow<Boolean> get() = _serviceWanted

    /** The source used last, to offer it again. */
    var lastSpec: SourceSpec = store.loadSpec()
        private set

    val fftSize: Int get() = session.fftSize

    fun start(spec: SourceSpec) {
        lastSpec = spec
        store.saveSpec(spec)
        update { it.copy(sampleRate = store.loadRate(spec)) }
        synchronized(serviceLock) {
            startsPending++
            _serviceWanted.value = true
        }
        RadioService.start(context)
        player.start()
        session.start(spec)
    }

    fun stop() {
        session.stop()
    }

    /** Changes the receiver's settings; an invalid change is ignored and reported as a message. */
    fun update(change: (ReceiverSettings) -> ReceiverSettings) {
        try {
            session.update(change)
        } catch (e: IllegalArgumentException) {
            _messages.tryEmit(e.message ?: "That setting is not possible")
            return
        }
        publishSettings()
    }

    fun setMode(mode: Mode) = update { it.withMode(mode) }

    fun setGain(gain: GainSetting) {
        _gain.value = gain
        session.setGain(gain)
    }

    fun setRspGain(gain: RspGain) {
        val before = _rspGain.value
        _rspGain.value = gain
        if (gain.agc != before.agc) session.setGain(GainSetting.RspAgc(gain.agc))
        if (gain.lnaState != before.lnaState) session.setGain(GainSetting.RspLna(gain.lnaState))
        if (gain.ifGainReductionDb != before.ifGainReductionDb) session.setGain(GainSetting.RspIfgr(gain.ifGainReductionDb))
    }

    /** Changes the sample rate and remembers it for this kind of source. */
    fun setSampleRate(rate: Int) {
        update { it.copy(sampleRate = rate) }
        store.saveRate(lastSpec, rate)
    }

    fun setPaused(paused: Boolean) = player.setPaused(paused)

    fun spectrum(dst: SpectrumFrame): Boolean = session.spectrum(dst)

    override fun onState(state: SessionState) {
        _state.value = state
        val ended = state is SessionState.Idle || state is SessionState.Failed
        synchronized(serviceLock) {
            // Each start() shows up once as a first connection attempt.
            if (state is SessionState.Connecting && state.attempt == 0 && startsPending > 0) startsPending--
            if (ended && startsPending == 0) _serviceWanted.value = false
        }
        if (ended) {
            if (!_serviceWanted.value) player.stop()
            _status.value = ReceiverStatus()
            _rds.value = RdsState()
        }
    }

    override fun onStatus(status: ReceiverStatus) {
        _status.value = status
        publishSettings()   // the receiver changes them itself when a source refuses a request
    }

    override fun onRds(state: RdsState) {
        _rds.value = state
    }

    override fun onMessage(text: String) {
        _messages.tryEmit(text)
    }

    private fun publishSettings() {
        val now = session.settings
        if (now != _settings.value) {
            _settings.value = now
            store.saveSettings(now)
        }
    }

    companion object {
        const val DEFAULT_RATE = 2_400_000

        /** The simulator makes its band in real time; a modest rate keeps it light on slow devices. */
        const val SIMULATOR_RATE = 960_000
    }
}

/** SDRplay gain: automatic gain control, the LNA state (0 = most gain) and the IF gain reduction in dB. */
data class RspGain(val agc: Boolean = true, val lnaState: Int = 0, val ifGainReductionDb: Int = 40)
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/AudioPlayer.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.radhwanalhajjaj.spectrasdr.core.audio.AudioRing

/**
 * Plays what the receiver puts into the [AudioRing]: an AudioTrack fed by its own thread in 10 ms
 * pieces. The ring decides between audio and silence, so the track never starves.
 */
class AudioPlayer(private val ring: AudioRing) {
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var paused = false

    /** Frames handed to the device since [start]; for diagnostics and tests. */
    @Volatile
    var framesPlayed = 0L
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        paused = false
        framesPlayed = 0
        thread = Thread({ play() }, "audio-player").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    /** Silences the output without ending the session (another app took the audio focus). */
    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    private fun play() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = ring.sampleRate
        val piece = rate / 100
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, 4 * piece * BYTES_PER_FRAME))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = FloatArray(2 * piece)
        try {
            ring.clear()
            track.play()
            var wasPaused = false
            while (running) {
                if (paused) {
                    if (!wasPaused) {
                        track.pause()
                        track.flush()
                        wasPaused = true
                    }
                    Thread.sleep(20)
                    continue
                }
                if (wasPaused) {
                    ring.clear()
                    track.play()
                    wasPaused = false
                }
                ring.read(buffer, piece)
                val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
                framesPlayed += written / 2
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = 2 * 4   // stereo float
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/SettingsStore.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.content.Context
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec

/** Remembers the last frequency, mode, levels and source between launches. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    fun loadSettings(): ReceiverSettings {
        val fallback = ReceiverSettings(frequencyHz = 100_300_000, sampleRate = Radio.DEFAULT_RATE)
        return runCatching {
            val mode = Mode.valueOf(prefs.getString("mode", Mode.WFM.name)!!)
            ReceiverSettings(
                frequencyHz = prefs.getLong("frequencyHz", fallback.frequencyHz),
                sampleRate = prefs.getInt("sampleRate", fallback.sampleRate),
                mode = mode,
                bandwidthHz = prefs.getInt("bandwidthHz", mode.defaultBandwidthHz),
                squelchDb = prefs.getFloat("squelchDb", Float.NEGATIVE_INFINITY).toDouble(),
                autoSquelch = prefs.getBoolean("autoSquelch", false),
                volume = prefs.getFloat("volume", 1f),
            )
        }.getOrDefault(fallback)
    }

    fun saveSettings(s: ReceiverSettings) {
        prefs.edit()
            .putLong("frequencyHz", s.frequencyHz)
            .putInt("sampleRate", s.sampleRate)
            .putString("mode", s.mode.name)
            .putInt("bandwidthHz", s.bandwidthHz)
            .putFloat("squelchDb", s.squelchDb.toFloat())
            .putBoolean("autoSquelch", s.autoSquelch)
            .putFloat("volume", s.volume)
            .apply()
    }

    /** The sample rate used last with this kind of source. */
    fun loadRate(spec: SourceSpec): Int =
        if (spec is SourceSpec.Simulator) prefs.getInt("rate.simulator", Radio.SIMULATOR_RATE) else prefs.getInt("rate.radio", Radio.DEFAULT_RATE)

    fun saveRate(spec: SourceSpec, rate: Int) {
        prefs.edit().putInt(if (spec is SourceSpec.Simulator) "rate.simulator" else "rate.radio", rate).apply()
    }

    fun loadSpec(): SourceSpec = when (prefs.getString("source", "simulator")) {
        "network" -> runCatching {
            SourceSpec.Network(prefs.getString("host", "")!!, prefs.getInt("port", 1234))
        }.getOrDefault(SourceSpec.Simulator)
        "driver" -> SourceSpec.Driver(1234)
        else -> SourceSpec.Simulator
    }

    fun saveSpec(spec: SourceSpec) {
        val e = prefs.edit()
        when (spec) {
            SourceSpec.Simulator -> e.putString("source", "simulator")
            is SourceSpec.Network -> e.putString("source", "network").putString("host", spec.host).putInt("port", spec.port)
            is SourceSpec.Driver -> e.putString("source", "driver")
        }
        e.apply()
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/RadioService.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.radhwanalhajjaj.spectrasdr.MainActivity
import io.github.radhwanalhajjaj.spectrasdr.R
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.radio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the radio playing while the app is in the background (§10): a foreground service of the
 * media-playback type with a notification, a wake lock, a Wi-Fi lock for network sources, and
 * audio focus. The radio itself lives in [Radio]; this service only follows its state.
 */
class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var inForeground = false   // main thread only

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> radio.setPaused(false)
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> radio.setPaused(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            combine(radio.state, radio.settings, radio.rds) { state, settings, rds ->
                Triple(state, "${FrequencyText.short(settings.frequencyHz)} ${settings.mode.name}", rds.ps.trim())
            }.distinctUntilChanged().collect { (state, tuning, station) ->
                if (inForeground && state !is SessionState.Idle && state !is SessionState.Failed) {
                    holdLocks(state)
                    manager.notify(NOTIFICATION, notification(tuning, describe(state, station)))
                }
            }
        }
        // Ends itself, and only after it has become a foreground service: stopping one that was asked
        // for with startForegroundService() before it called startForeground() ends the whole app.
        scope.launch {
            radio.serviceWanted.collect { wanted -> if (!wanted && inForeground) finish() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            radio.stop()
            return START_NOT_STICKY
        }
        val first = notification(getString(R.string.app_name), getString(R.string.state_starting))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION, first)
        }
        inForeground = true
        if (radio.serviceWanted.value) requestFocus() else finish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        abandonFocus()
        super.onDestroy()
    }

    private fun finish() {
        inForeground = false
        releaseLocks()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun describe(state: SessionState, station: String): String = when (state) {
        is SessionState.Connecting -> getString(R.string.state_connecting, state.spec.label)
        is SessionState.Running -> station.ifEmpty { state.info.name }
        is SessionState.Waiting -> getString(R.string.state_waiting, state.seconds)
        is SessionState.Failed -> state.message
        SessionState.Idle -> ""
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    private fun holdLocks(state: SessionState) {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spectrasdr:listening")
                .apply { setReferenceCounted(false); acquire() }
        }
        val network = when (state) {
            is SessionState.Connecting -> state.spec is SourceSpec.Network
            is SessionState.Running -> state.spec is SourceSpec.Network
            is SessionState.Waiting -> true
            else -> false
        }
        if (network && wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "spectrasdr:stream")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        getSystemService(AudioManager::class.java).requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val NOTIFICATION = 1
        const val ACTION_STOP = "io.github.radhwanalhajjaj.spectrasdr.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RadioService::class.java))
        }
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/Theme.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colours of the instrument parts that are not Material components. */
object Instrument {
    val trace = Color(0xFF4DD0E1)
    val peak = Color(0xFF6D8A91)
    val grid = Color(0xFF263238)
    val channel = Color(0x334DD0E1)
    val channelCentre = Color(0xFFFFB74D)
    val plot = Color(0xFF0B0F14)
    val dim = Color(0xFF546E7A)
    val good = Color(0xFF81C784)
}

private val colors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF452B00),
    background = Color(0xFF10151C),
    surface = Color(0xFF10151C),
    surfaceVariant = Color(0xFF1C242E),
)

/** Dark-first (§10): a radio is used at night and next to a bright waterfall. */
@Composable
fun SpectraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/FrequencyReadout.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText

/**
 * The frequency in megahertz, one digit at a time: tap the upper half of a digit to turn it up,
 * the lower half to turn it down, or drag it up and down like a thumbwheel. The label at the end
 * opens a field for typing a frequency.
 */
@Composable
fun FrequencyReadout(frequencyHz: Long, onChange: (Long) -> Unit, onType: () -> Unit, modifier: Modifier = Modifier) {
    val digits = FrequencyText.digits(frequencyHz)
    val current by rememberUpdatedState(frequencyHz)
    val change by rememberUpdatedState(onChange)
    val firstShown = digits.indexOfFirst { it != 0 }.let { if (it < 0 || it > 3) 3 else it }
    Row(
        modifier = modifier.semantics { contentDescription = FrequencyText.megahertz(frequencyHz) }.testTag("frequency"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until 10) {
            val power = 9 - i
            val large = i < 7
            Text(
                text = digits[i].toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = if (large) 40.sp else 26.sp,
                color = if (i < firstShown) Instrument.dim else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .testTag("digit$power")
                    .pointerInput(power) {
                        detectTapGestures { at ->
                            change(FrequencyText.turnDigit(current, power, if (at.y < size.height / 2) 1 else -1))
                        }
                    }
                    .pointerInput(power) {
                        var travelled = 0f
                        val notch = 18.dp.toPx()
                        detectVerticalDragGestures(onDragStart = { travelled = 0f }) { _, dy ->
                            travelled += dy
                            while (travelled <= -notch) {
                                change(FrequencyText.turnDigit(current, power, 1))
                                travelled += notch
                            }
                            while (travelled >= notch) {
                                change(FrequencyText.turnDigit(current, power, -1))
                                travelled -= notch
                            }
                        }
                    },
            )
            if (i == 3) Text(".", fontFamily = FontFamily.Monospace, fontSize = 40.sp)
            if (i == 6) Text(" ", fontSize = 14.sp)
        }
        Text(
            "MHz ✎",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 6.dp, bottom = 6.dp)
                .clickable(onClickLabel = "Type a frequency", onClick = onType)
                .testTag("type-frequency"),
        )
    }
}

/** Asks for a frequency in megahertz; [onDone] gets it in hertz. */
@Composable
fun FrequencyDialog(current: Long, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    var text by remember { mutableStateOf(FrequencyText.short(current).removeSuffix(" MHz")) }
    val hz = FrequencyText.parseMegahertz(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frequency") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("MHz") },
                singleLine = true,
                isError = hz == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.testTag("frequency-field"),
            )
        },
        confirmButton = {
            TextButton(enabled = hz != null, onClick = { hz?.let(onDone) }, modifier = Modifier.testTag("frequency-ok")) { Text("Tune") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/SpectrumView.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText
import io.github.radhwanalhajjaj.spectrasdr.core.display.SpectrumScale
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.spectrum.SpectrumFrame
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio

/** Holds the newest spectrum frame and the columns it is drawn from; drawing thread only. */
private class SpectrumData {
    var frame = SpectrumFrame(4_096)
    var levels = FloatArray(0)
    var peaks = FloatArray(0)
    var valid = false

    fun poll(radio: Radio): Boolean {
        val size = radio.fftSize
        if (frame.size != size) {
            frame = SpectrumFrame(size)
            valid = false
        }
        if (!radio.spectrum(frame)) return false
        valid = true
        return true
    }

    fun columns(width: Int) {
        val n = minOf(width, frame.size).coerceAtLeast(1)
        if (levels.size != n) {
            levels = FloatArray(n)
            peaks = FloatArray(n)
        }
        SpectrumScale.columns(frame.levelsDb, frame.size, levels)
        SpectrumScale.columns(frame.peakDb, frame.size, peaks)
    }
}

/**
 * The spectrum of the whole stream with the tuned channel marked. Tap to tune there (to the
 * nearest [stepHz]).
 */
@Composable
fun SpectrumView(
    radio: Radio,
    settings: ReceiverSettings,
    stepHz: Int,
    onTune: (Long) -> Unit,
    modifier: Modifier = Modifier,
    topDb: Float = -10f,
    bottomDb: Float = -110f,
) {
    val data = remember { SpectrumData() }
    var drawn by remember { mutableLongStateOf(0L) }
    val tune by rememberUpdatedState(onTune)
    val step by rememberUpdatedState(stepHz)
    val text = rememberTextMeasurer()
    val label = TextStyle(color = Instrument.dim, fontSize = 10.sp)

    // Polled from the display's frame callback, not from a Compose coroutine: an endless frame
    // loop would keep Compose from ever being idle (and its tests from ever proceeding).
    DisposableEffect(radio) {
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (data.poll(radio)) drawn++
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }

    Canvas(
        modifier = modifier
            .background(Instrument.plot)
            .testTag("spectrum")
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    if (data.valid) {
                        val scale = SpectrumScale(data.frame.centerHz, data.frame.sampleRate)
                        tune(FrequencyText.snap(scale.hzAt(at.x / size.width), step))
                    }
                }
            },
    ) {
        drawn   // read, so that a new frame redraws
        val w = size.width
        val h = size.height
        for (i in 1 until 5) {
            val y = h * i / 5
            drawLine(Instrument.grid, Offset(0f, y), Offset(w, y), 1f)
            drawText(text, "${(topDb + (bottomDb - topDb) * i / 5).toInt()}", Offset(4f, y + 2f), label)
        }
        if (!data.valid || data.frame.sampleRate <= 0) return@Canvas
        val scale = SpectrumScale(data.frame.centerHz, data.frame.sampleRate, topDb, bottomDb)

        for (i in 1 until 4) {
            val x = w * i / 4
            drawLine(Instrument.grid, Offset(x, 0f), Offset(x, h), 1f)
            drawText(text, FrequencyText.short(scale.hzAt(i / 4f)).removeSuffix(" MHz"), Offset(x + 4f, h - 14.dp.toPx()), label)
        }

        val left = scale.xOf(settings.frequencyHz - settings.bandwidthHz / 2.0) * w
        val right = scale.xOf(settings.frequencyHz + settings.bandwidthHz / 2.0) * w
        drawRect(Instrument.channel, Offset(left, 0f), Size((right - left).coerceAtLeast(2f), h))
        val centre = scale.xOf(settings.frequencyHz.toDouble()) * w
        drawLine(Instrument.channelCentre, Offset(centre, 0f), Offset(centre, h), 1.5f)

        data.columns(w.toInt())
        val n = data.levels.size
        val peaks = Path()
        val trace = Path()
        for (c in 0 until n) {
            val x = (c + 0.5f) * w / n
            val yPeak = scale.yOf(data.peaks[c]) * h
            val y = scale.yOf(data.levels[c]) * h
            if (c == 0) {
                peaks.moveTo(x, yPeak)
                trace.moveTo(x, y)
            } else {
                peaks.lineTo(x, yPeak)
                trace.lineTo(x, y)
            }
        }
        drawPath(peaks, Instrument.peak, style = Stroke(1f))
        drawPath(trace, Instrument.trace, style = Stroke(1.5.dp.toPx()))
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/SourceDialog.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec

/** What the user picked in the source dialog. */
sealed interface SourceChoice {
    data object Simulator : SourceChoice
    data object Dongle : SourceChoice
    data class Server(val host: String, val port: Int) : SourceChoice
}

private enum class Kind { SIMULATOR, DONGLE, SERVER }

@Composable
fun SourceDialog(last: SourceSpec, onDismiss: () -> Unit, onStart: (SourceChoice) -> Unit) {
    var kind by remember {
        mutableStateOf(
            when (last) {
                SourceSpec.Simulator -> Kind.SIMULATOR
                is SourceSpec.Driver -> Kind.DONGLE
                is SourceSpec.Network -> Kind.SERVER
            },
        )
    }
    var host by remember { mutableStateOf((last as? SourceSpec.Network)?.host ?: "") }
    var port by remember { mutableStateOf(((last as? SourceSpec.Network)?.port ?: 1234).toString()) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val ready = kind != Kind.SERVER || (host.isNotBlank() && portNumber != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source") },
        text = {
            Column {
                Option(kind == Kind.DONGLE, "USB dongle", "RTL-SDR through the SDR driver app", "source-dongle") { kind = Kind.DONGLE }
                Option(kind == Kind.SERVER, "Network server", "rtl_tcp or rsp_tcp on your network", "source-server") { kind = Kind.SERVER }
                if (kind == Kind.SERVER) {
                    Row(modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            label = { Text("Address") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(2f).testTag("server-host"),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            isError = portNumber == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("server-port"),
                        )
                    }
                }
                Option(kind == Kind.SIMULATOR, "Simulator", "A made-up band, no hardware needed", "source-simulator") { kind = Kind.SIMULATOR }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onStart(
                        when (kind) {
                            Kind.SIMULATOR -> SourceChoice.Simulator
                            Kind.DONGLE -> SourceChoice.Dongle
                            Kind.SERVER -> SourceChoice.Server(host, portNumber ?: 1234)
                        },
                    )
                },
                modifier = Modifier.testTag("source-start"),
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Option(selected: Boolean, title: String, detail: String, tag: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/DriverStarter.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.radhwanalhajjaj.spectrasdr.core.session.DriverUri
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio
import java.net.ServerSocket

/** What came of asking the driver apps for the dongle. */
sealed interface DriverOutcome {
    data class Started(val spec: SourceSpec.Driver) : DriverOutcome
    data class Refused(val message: String) : DriverOutcome
    data object NotInstalled : DriverOutcome
}

/** The hand-off to an SDR driver app (§5.1). */
object DriverHandOff {
    /** A port nothing listens on; 1234 if none can be found. */
    fun freePort(): Int = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(1234)

    /** Every installed app that answers an iqsrc address, the tested driver first. */
    fun handlers(context: Context, address: String): List<ComponentName> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address))
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { if (it.packageName == DriverUri.DRIVER_PACKAGE) 0 else 1 }
    }

    fun intent(address: String, handler: ComponentName): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(address)).setComponent(handler)

    /** The driver's Play Store page. */
    fun storeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${DriverUri.DRIVER_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun started(port: Int, data: Intent?): SourceSpec.Driver =
        SourceSpec.Driver(port, data?.getIntArrayExtra(DriverUri.EXTRA_SUPPORTED_COMMANDS)?.toSet())

    fun refusal(data: Intent?): String =
        data?.getStringExtra(DriverUri.EXTRA_ERROR_MESSAGE)?.takeIf { it.isNotBlank() }
            ?: "The SDR driver could not start the dongle. Check that it is plugged in and that the driver app has permission to use it."
}

private class Attempt(val port: Int, val address: String, val handlers: List<ComponentName>, val index: Int)

/**
 * Returns a function that asks the installed driver apps, one after the other, to start the
 * dongle, and reports the outcome.
 */
@Composable
fun rememberDriverStarter(radio: Radio, onOutcome: (DriverOutcome) -> Unit): () -> Unit {
    val context = LocalContext.current
    val outcome by rememberUpdatedState(onOutcome)
    var attempt by remember { mutableStateOf<Attempt?>(null) }
    lateinit var launch: (Attempt) -> Unit

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val a = attempt ?: return@rememberLauncherForActivityResult
        when {
            result.resultCode == Activity.RESULT_OK -> {
                attempt = null
                outcome(DriverOutcome.Started(DriverHandOff.started(a.port, result.data)))
            }
            a.index + 1 < a.handlers.size -> launch(Attempt(a.port, a.address, a.handlers, a.index + 1))
            else -> {
                attempt = null
                outcome(DriverOutcome.Refused(DriverHandOff.refusal(result.data)))
            }
        }
    }
    launch = { a ->
        attempt = a
        try {
            launcher.launch(DriverHandOff.intent(a.address, a.handlers[a.index]))
        } catch (_: ActivityNotFoundException) {
            attempt = null
            outcome(DriverOutcome.NotInstalled)
        }
    }

    return {
        val port = DriverHandOff.freePort()
        val settings = radio.settings.value
        val address = DriverUri.build(port, Radio.DEFAULT_RATE, settings.frequencyHz.coerceIn(0, 0xFFFF_FFFFL))
        val handlers = DriverHandOff.handlers(context, address)
        if (handlers.isEmpty()) outcome(DriverOutcome.NotInstalled) else launch(Attempt(port, address, handlers, 0))
    }
}
```


Create `app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/ReceiverScreen.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.radhwanalhajjaj.spectrasdr.core.display.FrequencyText
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverSettings
import io.github.radhwanalhajjaj.spectrasdr.core.engine.ReceiverStatus
import io.github.radhwanalhajjaj.spectrasdr.core.rds.RdsState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainModel
import io.github.radhwanalhajjaj.spectrasdr.core.source.GainSetting
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio
import io.github.radhwanalhajjaj.spectrasdr.radio.RspGain
import kotlin.math.roundToInt

/** Tuning step when tapping the spectrum, per mode. */
private fun stepOf(mode: Mode): Int = when (mode) {
    Mode.WFM -> 100_000
    Mode.NFM -> 12_500
    Mode.AM -> 5_000
    else -> 1_000
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(radio: Radio) {
    val state by radio.state.collectAsState()
    val status by radio.status.collectAsState()
    val rds by radio.rds.collectAsState()
    val settings by radio.settings.collectAsState()
    val gain by radio.gain.collectAsState()
    val rspGain by radio.rspGain.collectAsState()
    var typing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var choosing by remember { mutableStateOf(false) }
    var driverMissing by remember { mutableStateOf(false) }
    var driverRefusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(radio) { radio.messages.collect { snackbar.showSnackbar(it) } }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val begin: (SourceSpec) -> Unit = { spec ->
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        radio.start(spec)
    }

    val startDriver = rememberDriverStarter(radio) { outcome ->
        when (outcome) {
            is DriverOutcome.Started -> begin(outcome.spec)
            is DriverOutcome.Refused -> driverRefusal = outcome.message
            DriverOutcome.NotInstalled -> driverMissing = true
        }
    }

    val active = state !is SessionState.Idle && state !is SessionState.Failed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpectraSDR") },
                actions = {
                    TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("source")) {
                        Text(sourceLabel(state, radio.lastSpec), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            StateLine(state, status)
            FrequencyReadout(
                frequencyHz = settings.frequencyHz,
                onChange = { hz -> radio.update { it.copy(frequencyHz = hz) } },
                onType = { typing = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            ModeRow(settings, onMode = radio::setMode, onBandwidth = { bw -> radio.update { it.copy(bandwidthHz = bw) } })
            SpectrumView(
                radio = radio,
                settings = settings,
                stepHz = stepOf(settings.mode),
                onTune = { hz -> radio.update { it.copy(frequencyHz = hz) } },
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(vertical = 6.dp),
            )
            Meter(status)
            RdsStrip(rds, status)
            Levels(settings, state, gain, rspGain, radio)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (active) {
                    OutlinedButton(onClick = radio::stop, modifier = Modifier.testTag("stop")) { Text("Stop") }
                } else {
                    Button(onClick = { choosing = true }, modifier = Modifier.testTag("listen")) { Text("Listen") }
                }
            }
        }
    }

    if (typing) {
        FrequencyDialog(
            current = settings.frequencyHz,
            onDismiss = { typing = false },
            onDone = { hz ->
                typing = false
                radio.update { it.copy(frequencyHz = hz) }
            },
        )
    }
    if (choosing) {
        SourceDialog(
            last = radio.lastSpec,
            onDismiss = { choosing = false },
            onStart = { choice ->
                choosing = false
                when (choice) {
                    SourceChoice.Simulator -> begin(SourceSpec.Simulator)
                    is SourceChoice.Server -> begin(SourceSpec.Network(choice.host, choice.port))
                    SourceChoice.Dongle -> startDriver()
                }
            },
        )
    }
    if (driverMissing) {
        AlertDialog(
            onDismissRequest = { driverMissing = false },
            title = { Text("SDR driver needed") },
            text = { Text("To use a USB dongle, install the free \"SDR driver\" app. It talks to the dongle; SpectraSDR does the rest.") },
            confirmButton = {
                TextButton(onClick = {
                    driverMissing = false
                    try {
                        context.startActivity(DriverHandOff.storeIntent())
                    } catch (_: ActivityNotFoundException) {
                        driverRefusal = "No app store was found on this device. Search for \"SDR driver\" by SignalWare."
                    }
                }) { Text("Open the store") }
            },
            dismissButton = { TextButton(onClick = { driverMissing = false }) { Text("Not now") } },
        )
    }
    driverRefusal?.let { message ->
        AlertDialog(
            onDismissRequest = { driverRefusal = null },
            title = { Text("The dongle did not start") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { driverRefusal = null }) { Text("OK") } },
        )
    }
}

private fun sourceLabel(state: SessionState, last: SourceSpec): String = when (state) {
    is SessionState.Connecting -> state.spec.label
    is SessionState.Running -> state.spec.label
    is SessionState.Waiting -> state.spec.label
    is SessionState.Failed -> state.spec.label
    SessionState.Idle -> "Source: ${last.label}"
}

@Composable
private fun StateLine(state: SessionState, status: ReceiverStatus) {
    val text = when (state) {
        SessionState.Idle -> "Stopped"
        is SessionState.Connecting -> if (state.attempt == 0) "Connecting to ${state.spec.label}…" else "Reconnecting to ${state.spec.label}…"
        is SessionState.Running -> {
            val rate = "${FrequencyText.fixed(status.sampleRate / 1e6, 2)} MS/s"
            val load = "load ${(status.load * 100).roundToInt()} %"
            val lost = if (status.overruns > 0) " · ${status.overruns} blocks lost" else ""
            "${state.info.name} · $rate · $load$lost"
        }
        is SessionState.Waiting -> "${state.reason} Trying again in ${state.seconds} s."
        is SessionState.Failed -> state.message
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is SessionState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("state"),
    )
}

@Composable
private fun ModeRow(settings: ReceiverSettings, onMode: (Mode) -> Unit, onBandwidth: (Int) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in Mode.entries) {
            FilterChip(
                selected = settings.mode == mode,
                onClick = { onMode(mode) },
                label = { Text(mode.name) },
                modifier = Modifier.testTag("mode-${mode.name}"),
            )
        }
    }
    if (settings.mode.bandwidthsHz.size > 1) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (bw in settings.mode.bandwidthsHz) {
                FilterChip(
                    selected = settings.bandwidthHz == bw,
                    onClick = { onBandwidth(bw) },
                    label = { Text(bandwidthLabel(bw)) },
                )
            }
        }
    }
}

private fun bandwidthLabel(hz: Int): String =
    if (hz >= 1_000) "${(hz / 1_000.0).toString().removeSuffix(".0")} kHz" else "$hz Hz"

/** Signal level of the tuned channel in dBFS, with the squelch state. */
@Composable
private fun Meter(status: ReceiverStatus) {
    val db = status.channelPowerDb
    val fraction = if (db.isFinite()) ((db + 100) / 100).coerceIn(0.0, 1.0).toFloat() else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.weight(1f).height(8.dp).background(Instrument.grid)) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(8.dp).background(if (status.squelchOpen) Instrument.good else Instrument.dim))
        }
        Text(
            if (db.isFinite()) "${FrequencyText.fixed(db, 0)} dBFS" else "— dBFS",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(84.dp).padding(start = 8.dp).testTag("level"),
        )
    }
}

@Composable
private fun RdsStrip(rds: RdsState, status: ReceiverStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            rds.ps.trim().ifEmpty { "········" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleLarge,
            color = if (rds.ps.isBlank()) Instrument.dim else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("rds-name"),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(rds.rt.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("rds-text"))
            if (rds.pi >= 0) Text("PI " + rds.pi.toString(16).uppercase().padStart(4, '0'),style = MaterialTheme.typography.labelSmall, color = Instrument.dim)
        }
        if (status.stereo) {
            Text("STEREO", style = MaterialTheme.typography.labelMedium, color = Instrument.good, modifier = Modifier.testTag("stereo"))
        }
    }
}

@Composable
private fun Levels(settings: ReceiverSettings, state: SessionState, gain: GainSetting, rspGain: RspGain, radio: Radio) {
    LabelledSlider("Volume", "${(settings.volume * 100).roundToInt()} %", settings.volume, 0f..1f) { v ->
        radio.update { it.copy(volume = v) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auto squelch", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = settings.autoSquelch, onCheckedChange = { on -> radio.update { it.copy(autoSquelch = on) } }, modifier = Modifier.testTag("auto-squelch"))
    }
    if (!settings.autoSquelch) {
        val level = if (settings.squelchDb.isFinite()) settings.squelchDb.toFloat() else -101f
        LabelledSlider("Squelch", if (level <= -101f) "open" else "${level.roundToInt()} dBFS", level, -101f..0f) { v ->
            radio.update { it.copy(squelchDb = if (v <= -100.5f) Double.NEGATIVE_INFINITY else v.roundToInt().toDouble()) }
        }
    }
    val model = (state as? SessionState.Running)?.info?.gainModel
    if (model is GainModel.TunerSteps && model.tenthsDb.isNotEmpty()) {
        val auto = gain is GainSetting.Auto
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic gain", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = auto,
                onCheckedChange = { on -> radio.setGain(if (on) GainSetting.Auto else GainSetting.TunerIndex(model.tenthsDb.size / 2)) },
                modifier = Modifier.testTag("auto-gain"),
            )
        }
        if (!auto) {
            val index = ((gain as? GainSetting.TunerIndex)?.index ?: (model.tenthsDb.size / 2)).coerceIn(0, model.tenthsDb.lastIndex)
            LabelledSlider(
                "Gain", "${FrequencyText.fixed(model.tenthsDb[index] / 10.0, 1)} dB", index.toFloat(), 0f..model.tenthsDb.lastIndex.toFloat(),
                steps = (model.tenthsDb.size - 2).coerceAtLeast(0),
            ) { v -> radio.setGain(GainSetting.TunerIndex(v.roundToInt())) }
        }
    }
    if (model is GainModel.Rsp) {
        if (model.agc) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic gain control", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = rspGain.agc, onCheckedChange = { on -> radio.setRspGain(rspGain.copy(agc = on)) })
            }
        }
        if (model.lnaStates > 1) {
            LabelledSlider(
                "LNA state", "${rspGain.lnaState}", rspGain.lnaState.toFloat(), 0f..(model.lnaStates - 1).toFloat(),
                steps = (model.lnaStates - 2).coerceAtLeast(0),
            ) { v -> radio.setRspGain(rspGain.copy(lnaState = v.roundToInt())) }
        }
        if (!rspGain.agc || !model.agc) {
            val reduction = rspGain.ifGainReductionDb.coerceIn(model.ifgrMin, model.ifgrMax)
            LabelledSlider("IF gain reduction", "$reduction dB", reduction.toFloat(), model.ifgrMin.toFloat()..model.ifgrMax.toFloat()) { v ->
                radio.setRspGain(rspGain.copy(ifGainReductionDb = v.roundToInt()))
            }
        }
    }
    val rates = (state as? SessionState.Running)?.info?.sampleRates.orEmpty()
    if (rates.size > 1) {
        Text("Sample rate", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rate in rates) {
                FilterChip(
                    selected = settings.sampleRate == rate,
                    onClick = { radio.setSampleRate(rate) },
                    label = { Text("${FrequencyText.fixed(rate / 1e6, 2).trimEnd('0').trimEnd('.')} MS/s") },
                    modifier = Modifier.testTag("rate-$rate"),
                )
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Slider(value = position, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
```


In `.github/workflows/build.yml`, replace:

```yaml
      - name: Build, test and lint
        shell: bash
        run: ./gradlew :core:test --stacktrace 2>&1 | tee build.log

      - name: Point out what failed
```

with:

```yaml
      - name: Build, test and lint
        shell: bash
        run: ./gradlew :core:test :app:assembleRelease :app:lintRelease --stacktrace 2>&1 | tee build.log

      - name: Point out what failed
```

In `.github/workflows/build.yml`, replace:

```yaml
          path: |
            build.log
            core/build/reports/tests/test
          if-no-files-found: ignore
```

with:

```yaml
          path: |
            build.log
            app/build/reports/lint-results-release.html
            core/build/reports/tests/test
          if-no-files-found: ignore

      - name: Keep the APK
        uses: actions/upload-artifact@v4
        with:
          name: spectrasdr-apk
          path: app/build/outputs/apk/release/app-release.apk

      - name: Publish the test build
        if: github.event_name == 'push'
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          cp app/build/outputs/apk/release/app-release.apk spectrasdr-test.apk
          notes="Automatic test build from branch \`${{ github.ref_name }}\`, commit ${{ github.sha }}, $(date -u '+%Y-%m-%d %H:%M UTC'). Not a release: it can be incomplete or broken. Signed with the project's public test key."
          if gh release view test-build >/dev/null 2>&1; then
            gh release upload test-build spectrasdr-test.apk --clobber
            gh release edit test-build --notes "$notes"
          else
            gh release create test-build spectrasdr-test.apk --prerelease --title "Latest test build" --notes "$notes" --target "${{ github.sha }}"
          fi

```

- [ ] **Step 3: Check that the core still passes locally**

Run: `tools/test-core.sh`
Expected: `OK (280 tests)`

- [ ] **Step 4: Commit and push**

```bash
git add .github/workflows/build.yml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/MainActivity.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/SpectraApp.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/AudioPlayer.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/Radio.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/RadioService.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/radio/SettingsStore.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/DriverStarter.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/FrequencyReadout.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/ReceiverScreen.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/SourceDialog.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/SpectrumView.kt app/src/main/kotlin/io/github/radhwanalhajjaj/spectrasdr/ui/Theme.kt app/src/main/res/drawable/ic_launcher.xml app/src/main/res/drawable/ic_radio.xml app/src/main/res/values/strings.xml debug.keystore settings.gradle.kts
git commit -F - <<'MSG'
feat(app): the first receiver app

Compose receiver screen with frequency readout, modes, spectrum, level,
RDS, volume, squelch, gain and sample rate; a source dialog for the USB
dongle (through the SDR driver app), a network server or the simulator;
AudioTrack playback from the audio ring; a foreground media service with
notification, wake and Wi-Fi locks and audio focus. CI builds and lints
the release build type, signed with a public test key, and publishes it
as the latest test build.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 5: Check CI**

Run: `tools/ci-wait.sh`
Expected: `CI: build: success` (the order of the jobs varies).

- [ ] **Step 6: Look at the test build**

Run: `gh release view test-build --json assets --jq '.assets[].name'`
Expected: the list contains `spectrasdr-test.apk`.

---

### Task 7: Emulator tests

The app end to end on an Android emulator with no radio hardware (§12): the simulator with RDS and stereo, a fake rtl_tcp server with reconnection, a stand-in driver app answering `iqsrc://`, and quick start/stop cycles of the service. Checked on CI by the new `emulator` job.

**Files:**
- Create: `app/src/androidTest/AndroidManifest.xml`, `app/src/androidTest/java/io/github/radhwanalhajjaj/spectrasdr/FakeRtlTcp.java`, `app/src/androidTest/java/io/github/radhwanalhajjaj/spectrasdr/FakeDriverActivity.java`, `app/src/androidTest/kotlin/io/github/radhwanalhajjaj/spectrasdr/ListeningTest.kt`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- Produces:
  - Job `emulator` (API 34, x86_64, Pixel 6) runs `./gradlew :app:connectedAndroidTest` against the release build type, prints failures and `SPECTRA` log lines, and uploads screenshots `screen-*.png` to the `test-build` pre-release.

- [ ] **Step 1: Write the files**

Create `app/src/androidTest/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- The stand-in driver app serves samples from the test package's own process. -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application>
        <!-- Stands in for an SDR driver app: answers iqsrc:// like one (§5.1). -->
        <activity
            android:name="io.github.radhwanalhajjaj.spectrasdr.FakeDriverActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="iqsrc" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```


Create `app/src/androidTest/java/io/github/radhwanalhajjaj/spectrasdr/FakeRtlTcp.java`:

```java
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small rtl_tcp server for the emulator tests: the 12-byte greeting, then unsigned 8-bit IQ of
 * one carrier in noise at the pace of a real dongle. It notes the commands it gets and obeys none.
 *
 * Written in Java on purpose: the stand-in driver app runs in the test package's own process,
 * where the Kotlin standard library is not available.
 */
public final class FakeRtlTcp implements Closeable {
    private final ServerSocket server;
    private final int sampleRate;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final List<Integer> commands = new CopyOnWriteArrayList<>();
    private volatile int connections;

    public FakeRtlTcp() throws IOException {
        this(0, 2_400_000);
    }

    public FakeRtlTcp(int port, int sampleRate) throws IOException {
        this.sampleRate = sampleRate;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        Thread accept = new Thread(this::accept, "fake-rtl_tcp");
        accept.setDaemon(true);
        accept.start();
    }

    public int getPort() {
        return server.getLocalPort();
    }

    /** Command codes received, in order. */
    public List<Integer> getCommands() {
        return commands;
    }

    public int getConnections() {
        return connections;
    }

    /** Cuts the connections that are open, as a server restart or a network loss would. */
    public void dropClients() {
        for (Socket c : clients) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
        clients.clear();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
        dropClients();
    }

    private void accept() {
        while (!server.isClosed()) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            connections++;
            clients.add(socket);
            Thread stream = new Thread(() -> {
                try {
                    stream(socket);
                } catch (IOException | InterruptedException ignored) {
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "fake-rtl_tcp-stream");
            stream.setDaemon(true);
            stream.start();
        }
    }

    private void stream(Socket socket) throws IOException, InterruptedException {
        socket.setTcpNoDelay(true);
        OutputStream out = socket.getOutputStream();
        out.write(new byte[] {'R', 'T', 'L', '0', 0, 0, 0, 5, 0, 0, 0, 29});
        Thread reader = new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (true) {
                    int code = in.readUnsignedByte();
                    in.readInt();
                    commands.add(code);
                }
            } catch (IOException ignored) {
            }
        }, "fake-rtl_tcp-commands");
        reader.setDaemon(true);
        reader.start();

        int samples = sampleRate / 100;   // 10 ms; the carrier at fs/8 fits a whole number of turns
        byte[] block = new byte[2 * samples];
        Random random = new Random(7);
        for (int n = 0; n < samples; n++) {
            double phase = 2 * Math.PI * n / 8;
            double i = 0.3 * Math.cos(phase) + 0.02 * random.nextGaussian();
            double q = 0.3 * Math.sin(phase) + 0.02 * random.nextGaussian();
            block[2 * n] = (byte) Math.max(0, Math.min(255, (int) (127.5 + 127.5 * i)));
            block[2 * n + 1] = (byte) Math.max(0, Math.min(255, (int) (127.5 + 127.5 * q)));
        }
        long start = System.nanoTime();
        long sent = 0;
        while (!socket.isClosed()) {
            out.write(block);
            sent += samples;
            long wait = start + sent * 1_000_000_000L / sampleRate - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
    }
}
```


Create `app/src/androidTest/java/io/github/radhwanalhajjaj/spectrasdr/FakeDriverActivity.java`:

```java
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.io.IOException;

/**
 * Stands in for an SDR driver app in the emulator tests (§5.1): reads the port from the iqsrc
 * address, starts a small rtl_tcp server there, and answers OK with the commands it supports.
 *
 * Java, not Kotlin: this activity runs in the test package's own process (see FakeRtlTcp).
 */
public final class FakeDriverActivity extends Activity {
    /** Kept here so that the server outlives the activity. */
    private static volatile FakeRtlTcp server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String address = String.valueOf(getIntent().getData());
        String[] arguments = address.replace("iqsrc://", "").split(" ");
        int port = -1;
        for (int i = 0; i + 1 < arguments.length; i++) {
            if (arguments[i].equals("-p")) {
                try {
                    port = Integer.parseInt(arguments[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        try {
            if (port < 0) throw new IOException("No port in " + address);
            if (server != null) server.close();
            server = new FakeRtlTcp(port, 2_400_000);
            setResult(RESULT_OK, new Intent().putExtra("supportedTcpCommands", new int[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x0d, 0x7e}));
        } catch (IOException e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("detailed_exception_message", String.valueOf(e.getMessage())));
        }
        finish();
    }
}
```


Create `app/src/androidTest/kotlin/io/github/radhwanalhajjaj/spectrasdr/ListeningTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.radhwanalhajjaj.spectrasdr.core.engine.Mode
import io.github.radhwanalhajjaj.spectrasdr.core.session.SessionState
import io.github.radhwanalhajjaj.spectrasdr.core.session.SourceSpec
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app end to end on an emulator, with no radio hardware (§12). */
@RunWith(AndroidJUnit4::class)
class ListeningTest {
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var radio: Radio

    @Before
    fun startFromFm() {
        radio = rule.activity.radio
        radio.update { it.withMode(Mode.WFM).copy(frequencyHz = 100_300_000) }
    }

    @After
    fun stopListening() {
        radio.stop()
        rule.waitUntil(20_000) { radio.state.value == SessionState.Idle }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val done = automation.executeShellCommand("screencap -p /sdcard/Download/screen-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(done).use { it.readBytes() }
    }

    private fun tunedAndRunning() = radio.state.value is SessionState.Running && radio.status.value.tuned

    /** The DSP load as the middle of twenty readings, so one slow moment does not decide it. */
    private fun typicalLoad(): Double {
        val readings = DoubleArray(20) {
            Thread.sleep(150)
            radio.status.value.load
        }
        readings.sort()
        return readings[readings.size / 2]
    }

    @Test
    fun theSimulatorPlaysAStationAndShowsItsName() {
        screenshot("1-stopped")
        rule.onNodeWithTag("listen").performScrollTo().performClick()
        rule.onNodeWithTag("source-simulator").performClick()
        rule.onNodeWithTag("source-start").performClick()

        rule.waitUntil(120_000) { rule.onAllNodesWithText("DEMO FM").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("stereo").assertIsDisplayed()
        rule.waitUntil(30_000) { radio.player.framesPlayed > 2 * 48_000 }
        val load = typicalLoad()
        val status = radio.status.value
        Log.w("SPECTRA", "simulator: load=$load overruns=${status.overruns} underruns=${radio.ring.underruns} dropped=${radio.ring.droppedFrames}")
        screenshot("2-fm")
        assertTrue("the signal processing keeps up: typical load $load", load < 0.8)

        rule.onNodeWithTag("type-frequency").performClick()
        rule.onNodeWithTag("frequency-field").performTextReplacement("145.5")
        rule.onNodeWithTag("frequency-ok").performClick()
        rule.onNodeWithTag("mode-NFM").performClick()
        rule.waitUntil(20_000) { radio.settings.value.mode == Mode.NFM && radio.settings.value.frequencyHz == 145_500_000L && radio.status.value.tuned }
        screenshot("3-nfm")

        rule.onNodeWithTag("stop").performScrollTo().performClick()
        rule.waitUntil(20_000) { radio.state.value == SessionState.Idle }
    }

    @Test
    fun aNetworkServerIsUsedAndReconnectedAfterALoss() {
        FakeRtlTcp().use { server ->
            rule.onNodeWithTag("listen").performScrollTo().performClick()
            rule.onNodeWithTag("source-server").performClick()
            rule.onNodeWithTag("server-host").performTextReplacement("127.0.0.1")
            rule.onNodeWithTag("server-port").performTextReplacement(server.port.toString())
            rule.onNodeWithTag("source-start").performClick()

            rule.waitUntil(90_000) { tunedAndRunning() }
            assertEquals(SourceSpec.Network("127.0.0.1", server.port), (radio.state.value as SessionState.Running).spec)
            Log.w("SPECTRA", "network: load=${typicalLoad()} overruns=${radio.status.value.overruns} commands=${server.commands}")
            screenshot("4-network")

            server.dropClients()
            rule.waitUntil(30_000) { radio.state.value !is SessionState.Running }
            rule.waitUntil(90_000) { server.connections >= 2 && tunedAndRunning() }
        }
    }

    @Test
    fun quickStartsAndStopsLeaveTheAppRunning() {
        // Stopping the foreground service before it has become one ends the whole app.
        repeat(6) {
            rule.runOnUiThread { radio.start(SourceSpec.Simulator) }
            if (it % 2 == 0) Thread.sleep(40L * it)
            rule.runOnUiThread { radio.stop() }
        }
        rule.runOnUiThread { radio.start(SourceSpec.Simulator) }
        rule.waitUntil(60_000) { tunedAndRunning() }
        rule.runOnUiThread { radio.stop() }
        rule.waitUntil(20_000) { radio.state.value == SessionState.Idle && !radio.serviceWanted.value }
    }

    @Test
    fun theDongleIsStartedThroughADriverApp() {
        rule.onNodeWithTag("listen").performScrollTo().performClick()
        rule.onNodeWithTag("source-dongle").performClick()
        rule.onNodeWithTag("source-start").performClick()

        rule.waitUntil(90_000) { tunedAndRunning() }
        val spec = (radio.state.value as SessionState.Running).spec
        assertTrue("the source is the dongle: $spec", spec is SourceSpec.Driver)
        screenshot("5-dongle")
    }
}
```


Replace the whole of `.github/workflows/build.yml` with:

```yaml
# SPDX-License-Identifier: GPL-3.0-or-later
name: Build

on:
  push:
    branches: ["**"]
  pull_request:

permissions:
  contents: write

concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17

      # Checks the wrapper jar against Gradle's published checksums and caches Gradle's files.
      - uses: gradle/actions/setup-gradle@v4

      - name: Build, test and lint
        shell: bash
        run: ./gradlew :core:test :app:assembleRelease :app:lintRelease --stacktrace 2>&1 | tee build.log

      - name: Point out what failed
        if: failure()
        shell: bash
        run: |
          # The first lines that explain the failure become annotations on the run.
          grep -E "^e: |^w: .*error|error:|FAILED|> Could not|What went wrong|Caused by:|^\* What|Tests? .*failed|There were failing tests" build.log \
            | head -9 | while IFS= read -r line; do echo "::error::${line}"; done
          awk '/What went wrong/{f=1} f{print} /Try:/{exit}' build.log | head -25 | tr '\n' ' ' | cut -c1-900 | sed 's/^/::error title=What went wrong::/'
          echo

      - name: Keep the reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: |
            build.log
            app/build/reports/lint-results-release.html
            core/build/reports/tests/test
          if-no-files-found: ignore

      - name: Keep the APK
        uses: actions/upload-artifact@v4
        with:
          name: spectrasdr-apk
          path: app/build/outputs/apk/release/app-release.apk

      - name: Publish the test build
        if: github.event_name == 'push'
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          cp app/build/outputs/apk/release/app-release.apk spectrasdr-test.apk
          notes="Automatic test build from branch \`${{ github.ref_name }}\`, commit ${{ github.sha }}, $(date -u '+%Y-%m-%d %H:%M UTC'). Not a release: it can be incomplete or broken. Signed with the project's public test key."
          if gh release view test-build >/dev/null 2>&1; then
            gh release upload test-build spectrasdr-test.apk --clobber
            gh release edit test-build --notes "$notes"
          else
            gh release create test-build spectrasdr-test.apk --prerelease --title "Latest test build" --notes "$notes" --target "${{ github.sha }}"
          fi

  emulator:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17

      # Checks the wrapper jar against Gradle's published checksums and caches Gradle's files.
      - uses: gradle/actions/setup-gradle@v4

      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - name: Run the tests on an emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          profile: pixel_6
          disable-animations: true
          script: |
            adb logcat -c
            ./gradlew :app:connectedAndroidTest --stacktrace; code=$?; adb pull /sdcard/Download shots; adb logcat -d -s SPECTRA:W AndroidRuntime:E | tail -n 60; grep -h -A 14 "<failure" app/build/outputs/androidTest-results/connected/*/*.xml | cut -c1-400 | head -n 90; exit $code

      - name: Publish the screenshots
        if: always() && github.event_name == 'push'
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          ls -la shots/ || exit 0
          gh release upload test-build shots/*.png --clobber
```

- [ ] **Step 2: Check that the core still passes locally**

Run: `tools/test-core.sh`
Expected: `OK (280 tests)`

- [ ] **Step 3: Commit and push**

```bash
git add .github/workflows/build.yml app/src/androidTest/AndroidManifest.xml app/src/androidTest/java/io/github/radhwanalhajjaj/spectrasdr/FakeDriverActivity.java app/src/androidTest/java/io/github/radhwanalhajjaj/spectrasdr/FakeRtlTcp.java app/src/androidTest/kotlin/io/github/radhwanalhajjaj/spectrasdr/ListeningTest.kt
git commit -F - <<'MSG'
test(app): emulator tests with the simulator, a fake server and a stand-in driver

Four instrumented tests on an emulator: the simulator plays a stereo
station and shows its RDS name; a fake rtl_tcp server is used and
reconnected after a lost connection; the dongle is started through a
stand-in driver app; quick start/stop cycles leave the app running.
The stand-in driver is written in Java because it runs in the test
package's own process, which has no Kotlin runtime.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 4: Check CI**

Run: `tools/ci-wait.sh`
Expected: `CI: build: success` and `CI: emulator: success`, and the emulator log shows two `SPECTRA` lines with `load` below 0.8 (the order of the jobs varies).

---

### Task 8: Spec and README

Writes this plan's decisions into the spec (D9, §4.1, §4.3, §10, §12, §14) and updates the README.

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, `README.md`

**Interfaces:**
- Produces:
  - Spec and README describe the app as built.

- [ ] **Step 1: Write the files**

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
- **Date:** 2026-11-12
- **Status:** Design approved in chat; this written spec awaits review
- **Revised:** 2026-11-14 — §4.1, §4.2, §4.3, §6.3, §6.4, §7.1 and §11 aligned with the engine as built (Plans 1 and 2)
- **License:** GPL-3.0-or-later
- **Working name:** SpectraSDR (renamable before first release)
```

with:

```markdown
- **Date:** 2026-11-12
- **Status:** Design approved in chat; this written spec awaits review
- **Revised:** 2026-11-14 — §4.1, §4.2, §4.3, §6.3, §6.4, §7.1 and §11 aligned with the engine as built (Plans 1 and 2); 2026-11-17 — D9, §4.1, §4.3, §10, §12 and §14 aligned with the first Android app (Plan 3a)
- **License:** GPL-3.0-or-later
- **Working name:** SpectraSDR (renamable before first release)
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
| D7 | Android code is compiled and linted by **GitHub Actions** | The workspace cannot reach Google's Maven repository |
| D8 | Jetpack Compose + Material 3; OpenGL ES 3.0 for the waterfall | Native Android look; waterfall needs per-row GPU updates |
| D9 | minSdk 26, targetSdk/compileSdk 36 | OpenRSP needs Android 8.0+; current Play target-API rules |
| D10 | applicationId `io.github.radhwanalhajjaj.spectrasdr` | GitHub-namespace convention for open-source apps |

```

with:

```markdown
| D7 | Android code is compiled and linted by **GitHub Actions** | The workspace cannot reach Google's Maven repository |
| D8 | Jetpack Compose + Material 3; OpenGL ES 3.0 for the waterfall | Native Android look; waterfall needs per-row GPU updates |
| D9 | minSdk 26, targetSdk 36, compileSdk 37 | OpenRSP needs Android 8.0+; current Play target-API rules; Compose 1.12 needs API 37 to compile |
| D10 | applicationId `io.github.radhwanalhajjaj.spectrasdr` | GitHub-namespace convention for open-source apps |

```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
| `scan` | `Channel`, `ScanList`, `Scanner` state machine, `ActivityLog` |
| `record` | `WavWriter` (crash-safe), `IqRecorder`, `AudioRecorder`, file naming and parsing |
| `engine` | `Receiver` orchestrator, `ReceiverChain` (channelizer → demodulator → squelch → audio output), `TuningPolicy`, `AudioSink` interface, listener interfaces |

**`:app`** — Android: Compose UI, `RadioService` (foreground, `mediaPlayback` type), `DriverLauncher` (`iqsrc` hand-off), `AndroidAudioSink` (AudioTrack), `WaterfallView` (GLSurfaceView), persistence (Room + DataStore), recordings browser (FileProvider sharing).

**`:openrsp`** (phase 2) — NDK/C++ JNI bridge to OpenRSP, exposing `OpenRspSource : IqSource`.
```

with:

```markdown
| `scan` | `Channel`, `ScanList`, `Scanner` state machine, `ActivityLog` |
| `record` | `WavWriter` (crash-safe), `IqRecorder`, `AudioRecorder`, file naming and parsing |
| `engine` | `Receiver` orchestrator, `ReceiverChain` (channelizer → demodulator → squelch → audio output), `TuningPolicy`, `AudioSink` interface, listener interfaces, `ThreadHook` (lets the app raise the source and DSP threads' priority) |
| `audio` | `AudioRing`: the non-blocking buffer between the DSP thread and the device's audio thread; plays silence until it is back at its target level |
| `session` | `RadioSession` (start, stop, settings that outlive a session, reconnection with back-off), `SourceSpec`, `SessionState`, plain-language error texts, `DriverUri` (the `iqsrc` address) |
| `display` | `FrequencyText` (readout digits, typed megahertz, locale-independent numbers), `SpectrumScale` (frequency and level mapping, column reduction) |

**`:app`** — Android: Compose UI, `Radio` (the process-wide holder of the `RadioSession`, with its state as flows), `RadioService` (foreground, `mediaPlayback` type), `DriverStarter` (`iqsrc` hand-off), `AudioPlayer` (AudioTrack fed from `AudioRing`), `WaterfallView` (GLSurfaceView), persistence (Room + SharedPreferences), recordings browser (FileProvider sharing).

**`:openrsp`** (phase 2) — NDK/C++ JNI bridge to OpenRSP, exposing `OpenRspSource : IqSource`.
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
### 4.3 Threads and data flow

1. **Source thread** (one per source): blocking reads → conversion to interleaved float IQ in pooled `IqBlock`s (16,384 complex samples each, tagged with a monotonically increasing `seq`, the `tuneEpoch` (§7.1), `centerHz`, `sampleRate`) → bounded queue holding about 0.5 s. When full, the oldest block is dropped and `overruns` increments. From Plan 3 on, a source delivers a block when it is full or holds about 20 ms of samples (sample rate / 50), whichever comes first, and the queue is sized by duration, so audio and spectrum frames stay smooth at low sample rates (a full block is 68 ms at 240 kS/s).
2. **DSP thread:** for every block, the spectrum analyzer and the active demodulator chain run; audio goes to the `AudioSink`; the newest `SpectrumFrame` is published to a latest-value slot.
3. **UI/GL thread:** reads the latest frame at vsync; pushes waterfall rows to the GPU.
```

with:

```markdown
### 4.3 Threads and data flow

1. **Source thread** (one per source): blocking reads → conversion to interleaved float IQ in pooled `IqBlock`s (up to 16,384 complex samples each, tagged with a monotonically increasing `seq`, the `tuneEpoch` (§7.1), `centerHz`, `sampleRate`) → bounded queue holding about 0.5 s. When full, the oldest block is dropped and `overruns` increments. A source delivers a block when it is full or holds about 20 ms of samples (sample rate / 50, at least 256), whichever comes first, and the queue is sized by duration, so audio and spectrum frames stay smooth at low sample rates (a full block would be 68 ms at 240 kS/s).
2. **DSP thread:** for every block, the spectrum analyzer and the active demodulator chain run; audio goes to the `AudioSink`; the newest `SpectrumFrame` is published to a latest-value slot.
3. **UI/GL thread:** reads the latest frame at vsync; pushes waterfall rows to the GPU.
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
- **Background survival:** on first background use, if the app is battery-optimised, a one-time prompt explains why and opens the system battery settings so the user can set the app to Unrestricted (Samsung's sleeping-apps lists can otherwise stop long listening sessions).
- **Diagnostics:** Settings → Diagnostics shows the benchmark (§4.4), overrun and drop counters, the measured retune latency and recent errors, with a "Share report" button.
- **Persistence:** Room for channels, lists, servers, saved sweeps and the activity log; DataStore for settings and last state.
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS (runtime on Android 13+). No location or storage permissions.
- **Look:** dark-first Material 3; portrait and landscape layouts; works on tablets.
```

with:

```markdown
- **Background survival:** on first background use, if the app is battery-optimised, a one-time prompt explains why and opens the system battery settings so the user can set the app to Unrestricted (Samsung's sleeping-apps lists can otherwise stop long listening sessions).
- **Diagnostics:** Settings → Diagnostics shows the benchmark (§4.4), overrun and drop counters, the measured retune latency and recent errors, with a "Share report" button.
- **Persistence:** Room for channels, lists, servers, saved sweeps and the activity log; SharedPreferences for settings and last state (read synchronously at start, no extra library).
- **Numbers on screen:** frequencies, levels and rates are formatted with `Locale.ROOT`, so they show Western digits and a decimal point whatever the device's language.
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS (runtime on Android 13+). No location or storage permissions.
- **Look:** dark-first Material 3; portrait and landscape layouts; works on tablets.
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown
| Throughput | WFM stereo + RDS chain at 2.4 MS/s runs ≥ 3× real time on the JVM |

**CI (GitHub Actions):** `./gradlew :core:test :app:assembleDebug :app:lintDebug` on every push, debug APK uploaded as an artifact; tags `v*` build a release APK.

**Emulator tests (GitHub Actions, Android emulator with KVM):** instrumented tests start the app against an in-test fake rtl_tcp server and the `SimulatorSource`, then check that the receiver tunes, plays audio, shows the RDS name, runs a sweep and completes a scan; screenshots of each screen are saved as build artifacts. Whether Claude can read build logs and artifacts directly is confirmed when the repository is attached; otherwise the user forwards them.

**Simulator source:** `SimulatorSource` reuses the test signal generators to produce a band with FM stations (stereo + RDS), NFM bursts, AM, SSB and CW signals, so the whole app runs in the emulator and on any phone without hardware.
```

with:

```markdown
| Throughput | WFM stereo + RDS chain at 2.4 MS/s runs ≥ 3× real time on the JVM |

**CI (GitHub Actions):** `./gradlew :core:test :app:assembleRelease :app:lintRelease` on every push. The installable test build is the release build type (not debuggable, because a debuggable app runs its signal processing several times slower) signed with a public test key committed to the repository, so each test build installs over the previous one. Every push publishes it to the pre-release "Latest test build" (tag `test-build`); tags `v*` will build a properly signed release (Plan 7).

**Emulator tests (GitHub Actions, Android emulator with KVM):** instrumented tests start the app against an in-test fake rtl_tcp server, a stand-in driver app that answers `iqsrc://`, and the `SimulatorSource`, then check that the receiver tunes, plays audio, shows the RDS name, reconnects after a lost connection (later plans: runs a sweep and completes a scan). The tests run against the release build type. Screenshots are attached to the "Latest test build" pre-release, and failures are printed in the job log, where `tools/ci-wait.sh` reads them.

**Simulator source:** `SimulatorSource` reuses the test signal generators to produce a band with FM stations (stereo + RDS), NFM bursts, AM, SSB and CW signals, so the whole app runs in the emulator and on any phone without hardware.
```

In `docs/superpowers/specs/2026-11-12-spectrasdr-design.md`, replace:

```markdown

1. **Core engine** — sources (rtl_tcp/rsp_tcp client, IQ files, simulator), `RetuneSync`, DSP blocks, all demodulators, stereo, RDS, spectrum analyzer, `Receiver`; full unit tests and throughput test pass in the workspace.
2. **Android receiver** — Gradle project, CI and emulator tests, driver hand-off, network sources, `RadioService`, audio sink, receiver screen with spectrum and GL waterfall, diagnostics and benchmark. First installable APK.
3. **Sweep analyzer** — engine, screen, saved sweeps, CSV export.
4. **Scanner and recording** — channel lists, memory scan and range search, activity log, audio/IQ recording, recordings browser, IQ playback.
```

with:

```markdown

1. **Core engine** — sources (rtl_tcp/rsp_tcp client, IQ files, simulator), `RetuneSync`, DSP blocks, all demodulators, stereo, RDS, spectrum analyzer, `Receiver`; full unit tests and throughput test pass in the workspace.
2. **Android receiver**, in two plans:
   - **2a (Plan 3a)** — Gradle project, CI and emulator tests, driver hand-off, network sources with reconnection, `RadioService`, audio output, receiver screen with spectrum, frequency readout, modes, levels, gains and RDS. First installable APK.
   - **2b (Plan 3b)** — GL waterfall, spectrum gestures (pinch, drag, double-tap), diagnostics and benchmark, performance-hint session, battery-optimisation prompt, start on dongle attach, sample-rate step-down on a slow network.
3. **Sweep analyzer** — engine, screen, saved sweeps, CSV export.
4. **Scanner and recording** — channel lists, memory scan and range search, activity log, audio/IQ recording, recordings browser, IQ playback.
```


Replace the whole of `README.md` with:

```markdown
# SpectraSDR

Open-source Android SDR receiver: spectrum and waterfall, wideband sweep analyzer, and listening
(WFM stereo + RDS, NFM, AM, SSB, CW) for RTL-SDR and SDRplay receivers.

Status: the core engine is complete and tested. The first Android app plays FM (stereo and RDS),
NFM, AM, SSB and CW from a USB dongle through the SDR driver app, from an rtl_tcp or rsp_tcp server
on the network, or from the built-in simulator. The waterfall, sweep, scanner and recording follow.

## Test builds

Every push builds an APK and publishes it on the
[Latest test build](https://github.com/RadhwanAL-Hajjaj/spectrasdr/releases/tag/test-build) page.
It is not a release: it can be incomplete. It is signed with a public test key, so each test build
installs over the previous one (and over nothing else).

To use an RTL-SDR dongle, install the free "SDR driver" app (by SignalWare); SpectraSDR starts it
when you pick "USB dongle".

## Layout

- `core/` — pure-Kotlin signal engine with no Android dependencies
- `docs/superpowers/specs/` — design spec
- `docs/superpowers/plans/` — implementation plans
- `app/` — the Android app (Compose UI, background service, audio output, driver hand-off)
- `tools/test-core.sh` — runs the core unit tests with kotlinc and JUnit 4, without Gradle
- `tools/ci-wait.sh` — waits for the GitHub Actions jobs of a commit and prints what failed

## Building

`./gradlew :core:test :app:assembleRelease` builds the app (JDK 17, Android SDK with API 37).
The emulator tests run with `./gradlew :app:connectedAndroidTest`.

## License

GPL-3.0-or-later. See `LICENSE`.
```

- [ ] **Step 2: Commit and push**

```bash
git add README.md docs/superpowers/specs/2026-11-12-spectrasdr-design.md
git commit -F - <<'MSG'
docs: spec and README for the first Android app

compileSdk 37, the audio, session and display packages, 20 ms blocks,
SharedPreferences, locale-independent numbers, the release-type test
build and its pre-release, the emulator tests, and milestone 2 split
into Plans 3a and 3b.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017jRyoY9MwxPayaRcUGt5SG
MSG
git push origin plan-3a-android-app
```

- [ ] **Step 3: Check CI**

Run: `tools/ci-wait.sh`
Expected: `CI: build: success` and `CI: emulator: success` (the order of the jobs varies).

---

## Spec coverage (this plan)

| Spec | Where |
|------|-------|
| D7 (GitHub Actions builds Android), D9 (SDK levels) | Tasks 1, 6; D9 amended in Task 8 |
| §4.3 source thread, block size; §4.4 thread priorities | Task 2 |
| §5.1 driver hand-off | Tasks 4 (address), 6 (`DriverStarter`), 7 (stand-in driver) |
| §5.3 network sources | Tasks 5, 6, 7 |
| §6.5 audio output, §11 underruns | Tasks 3, 6 |
| §10 RadioService, permissions, persistence, look | Task 6; §10 amended in Task 8 |
| §11 reconnection with back-off, plain-language errors | Task 5 |
| §12 CI, emulator tests, simulator source | Tasks 1, 6, 7; §12 amended in Task 8 |
| §14 milestone 2 | This plan (2a); Plan 3b (2b) |

Not in this plan (Plan 3b): GL waterfall (§10), spectrum pinch/drag/double-tap, Diagnostics and benchmark (§4.4, §10), performance-hint session, battery-optimisation prompt, starting on dongle attach, the 2-second throughput test and rate step-down for network links (§5.3).

## Checks on the phone after this plan

Install `spectrasdr-test.apk` from the "Latest test build" page on the S24 Ultra, then:

- Simulator: FM station with STEREO and its RDS name; NFM, AM, SSB and CW stations audible.
- RTL-SDR V4 through the SDR driver app: the driver asks for USB permission, the receiver starts, FM broadcast with RDS plays; unplugging shows "The dongle stopped sending samples".
- rtl_tcp or rsp_tcp on the network: connects, plays, reconnects after the server restarts.
- Background: screen off for 10 minutes keeps playing; the notification's Stop ends the session; a phone call pauses the audio and it comes back afterwards.
- Watch the state line: `load` well below 100 % and no "blocks lost" at 2.4 MS/s.
