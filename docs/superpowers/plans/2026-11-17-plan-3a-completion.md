# Plan 3a (first Android app) — completion report

Date: 2026-11-17 · Branch: `plan-3a-android-app` (from `main` 61bc7e3) · Plan: `2026-11-17-plan-3a-android-app.md` · Spec: `../specs/2026-11-12-spectrasdr-design.md`

## 1. Status

- All 8 tasks are done. Each was built by one agent and reviewed by another; every Important finding was fixed and re-reviewed. A whole-branch review then found no critical defect and four important ones, which one fix wave closed (re-reviewed clean).
- 19 commits after the plan commit. Core: 287 tests pass locally (Kotlin 2.1.21) and on CI (Kotlin 2.4.0 under Gradle). CI builds and lints the release-type APK and runs four emulator tests, all green on the last commit (e5bcdac).
- The installable APK of that commit is on the public pre-release "Latest test build" (`spectrasdr-test.apk`).
- Measured on the CI emulator (API 34): DSP load 0.16–0.24 for FM stereo with RDS (simulator, 960 kS/s) and for a 2.4 MS/s rtl_tcp stream; no blocks lost.

## 2. What exists now

- **Build and CI:** Gradle 9.6.1 wrapper, version catalog, `:core` (Kotlin/JVM) and `:app` (AGP 9.5.0, Compose, minSdk 26, targetSdk 36, compileSdk 37). Workflow `Build`: job `build` (core tests, release APK, lint, publish the test build) and job `emulator` (instrumented tests, screenshots). `tools/ci-wait.sh` reads results back.
- **Core additions:** `BlockSizing` (about 20 ms blocks), `ThreadHook`, sturdier sources, `audio/AudioRing`, `display/FrequencyText` and `SpectrumScale`, `session/RadioSession` (start, stop, settings kept between sessions, reconnection with back-off, plain-language errors), `SourceSpec`, `SessionState`, `DriverUri`.
- **App:** `Radio` (one per process, state as flows), `AudioPlayer` (AudioTrack from the ring), `RadioService` (foreground media service, notification with Stop, wake and Wi-Fi locks, audio focus), `SettingsStore`, the driver hand-off, and the receiver screen: frequency readout (tap, drag or type), modes and bandwidths, spectrum with tap-to-tune, level meter, RDS name and text, stereo indicator, volume, squelch, gain (RTL and SDRplay), sample rate, and why listening stopped.
- **Emulator tests:** simulator with RDS and stereo; a fake rtl_tcp server with reconnection; the dongle through a stand-in driver app; quick start/stop cycles.

## 3. Decisions taken during execution

Each with its reason and what it costs if wrong. The plan's own 14 decisions are at its top.

- **Branch created with the plan commit first**, so Task 1 skipped its branch lines. Cost: none.
- **CI failed once in Gradle configuration** (the Kotlin plugin could not be resolved) on 8dc16ee; identical build files passed before and after, so it was treated as a repository outage. Cost if wrong: a recurring flake to pin to a mirror.
- **AudioRing loops (Task 3):** the plan's `until … step 2` loops allocated two objects per call on the audio thread; they now loop over frames, and a test proves zero allocation. The concurrency test's writer is a daemon and the test checks it finished.
- **Spectrum columns (Task 4):** `SpectrumScale.columns` throws if there are more columns than bins, but its only caller limits the count; left for Plan 3b to merge with `SpectrumAnalyzer.reduce` when zoom arrives.
- **Radio session (Task 5):** a settings change made while a receiver was being built was lost; the receiver is now built and published under the settings lock. Control errors (gain, ppm, options) are reported as messages, and kept gains are restored only when they fit the new source. A failure while building the receiver ends in `Failed` and closes the source. `close()` can be called twice and lets the thread end. The give-up test can no longer flake.
- **App service and player (Task 6):** the service now applies the current state as soon as it is in the foreground (before, a steady session could run without its wake and Wi-Fi locks and with a "Starting…" notification); a permanent audio-focus loss stops the session with a reason instead of leaving it silent; the countdown is locale-free; AudioTrack failures are reported instead of ending the process; a player thread can never be orphaned; the Wi-Fi lock follows the source.
- **Final fix wave:** a failed AudioTrack write is retried once with a new track, then reported; the driver's answer survives rotation or recreation; the screen keeps the reason for a stop; starting the service from the background (which Android forbids) is reported instead of crashing; focus is requested again after a permanent loss; spectrum taps need a valid frame.
- **Spec drift fixed by the controller:** §4.3 and §10 now describe `AudioRing`/`AudioPlayer`, focus handling and the service as built.
- **Final review model:** the most capable model was not available (usage credits), so the final review and its re-review ran on Opus.

## 4. Where the code differs from the plan document

Review fixes changed these files after their listings: `core/src/main/kotlin/audio/AudioRing.kt`, `session/RadioSession.kt`, `session/SessionTypes.kt` (KDoc), tests `AudioRingTest`, `RadioSessionTest`, `testutil/ScriptedSource`; app `radio/Radio.kt`, `AudioPlayer.kt`, `RadioService.kt`, `ui/DriverStarter.kt`, `ui/ReceiverScreen.kt`, `ui/SpectrumView.kt`, `res/values/strings.xml`; the spec and README. Test counts are therefore higher than the plan's (287, not 280). The Task 6 step `gh release view` needs GitHub's GraphQL API; `gh api repos/{owner}/{repo}/releases/tags/test-build` is the working form. The code and this report are the record.

## 5. Follow-ups

### Plan 3b (start with one "lifecycle hardening" task)

- Audio focus and player: honour the `requestAudioFocus` result; do not clear a transient pause on a source switch; carry the run token into the error callback; make "never two players" absolute; pause or stop on `ACTION_AUDIO_BECOMING_NOISY` (Bluetooth earbuds disconnected).
- Service: `stopSelf(startId)` in the Stop branch when nothing is wanted (two quick Stops, or Stop to a service that is not running); player start/stop under the service lock.
- Start on dongle attach must go through a visible activity: Android refuses to start the service from a broadcast.
- UI cost: stop the frame callback while the activity is stopped, reuse Paths, lifecycle-aware collection, debounce preference writes and notification updates.
- Notification permission asked once; a later driver handler that is missing is reported as refused, not "not installed".
- Session: a mistyped server address retries for two minutes (give up sooner on a first connection, word the waiting text by cause); handshake errors labelled as protocol errors; one sample-rate key per source kind; gain filtering by value range and for live controls; exceptions inside session tasks end in `Failed`.
- Typing: accept the Arabic decimal separator (U+066B) and comma (U+060C); clamp `snap` and `hzAt`.
- Display: merge `SpectrumScale.columns` with `SpectrumAnalyzer.reduce`; fix the half-bin offset of `xOfBin`.
- Tests: assert the fake server's commands and audio after reconnect; guard the screenshot upload; screenshot on failure; `ci-wait.sh` should show `gh` errors and wait for the expected job names.
- Wi-Fi with the screen off: measure drops over 10 minutes on the phone (low-latency mode may not hold with the screen off).
- SDRplay gain controls show defaults, not the receiver's real state.

### Plan 6 (polish and release)

- A private signing key from CI secrets for `v*` tags, and a check that refuses to sign a tagged build with the public test key.
- Per-job workflow permissions, actions pinned to commit SHAs, publish the test build only after the emulator tests pass (and only from `main` or the active plan branch); move the `test-build` tag with each build; `distributionSha256Sum` for the wrapper.
- Accessibility pass for the frequency readout.

## 6. Checks on the phone

- Simulator: FM with STEREO and its RDS name; NFM, AM, SSB and CW audible.
- RTL-SDR V4 through the SDR driver app: USB permission prompt, then FM with RDS. Rotate the phone during the driver's prompt: listening must still start. Unplug: "The dongle stopped sending samples".
- rtl_tcp or rsp_tcp on Wi-Fi: connects, plays, reconnects after the server restarts. An hour with the screen off: note "blocks lost" afterwards.
- Background: the notification's Stop ends the session; a call pauses and audio returns; playing music in another app stops SpectraSDR and the screen says why.
- The state line: load well below 100 % at 2.4 MS/s.
