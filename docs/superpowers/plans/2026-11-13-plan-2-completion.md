# Plan 2 (receiver engine) — completion report

Date: 2026-11-15 · Branch: `plan-2-receiver-engine` (stacked on `plan-1-core-foundations`) · Plan: `2026-11-13-plan-2-receiver-engine.md` · Spec: `../specs/2026-11-12-spectrasdr-design.md`

This report records what Plan 2 delivered, how it was checked, every decision taken during execution
that the plan or spec did not already settle, where the code now differs from the plan's listings,
and the follow-ups that later plans must pick up.

## 1. Status

- All 15 tasks are implemented, plus one unplanned unit (Task 10a). Each was built test-first by one
  agent and reviewed by a separate agent for spec compliance and code quality.
- The plan's code came from a tested prototype and was replayed on a clean clone before execution.
  Reviews still found defects in it: in Tasks 8, 12, 14 and 15 the plan's own listing was at fault.
  Each was fixed and re-reviewed.
- A flaky Plan 1 test turned out to be a real Plan 1 defect (the exit command to the Android driver
  could be lost on close). It was fixed as Task 10a.
- A whole-branch review then found no critical defect and six important gaps in the API that the
  Android app will use. Five were closed in one fix wave and re-reviewed; the sixth was settled as a
  decision in the spec and is built first in Plan 3 (§4 and §6 below).
- Result: 30 commits on top of the plan, 58 source files (about 5,600 lines),
  51 test files (about 5,100 lines), 253 tests passing, no compiler warnings in the main code, the
  GPL-3.0-or-later header on every Kotlin file, standard library only in `:core`.
- Measured on the build machine (2 cores): broadcast FM with stereo, RDS and a 4,096-point spectrum
  at 2.4 MS/s runs 12 to 14 times faster than real time (the spec asks for 3); the signal path
  allocates nothing per block once it runs, in every mode.

## 2. What exists now

Plan 1's parts are listed in its own report. Plan 2 added (under `core/src/main/kotlin`):

| Area | Files | Purpose |
|---|---|---|
| DSP | `dsp/Biquad`, `Select`; hardened `Agc`, `DcBlocker`, `Deemphasis`, `Pll`, `FractionalResampler`, `DecimatorChain` | Butterworth sections, quickselect for percentiles; NaN-safe, denormal-safe, allocation-free primitives |
| Sources | `source/BlockQueue`, `IqSource` (tune epoch on rate changes), `SimulatorSource`; `rtltcp/RtlTcpClient` (close fix) | Adjustable queue, safer block hand-over, a paced synthetic band for use without hardware |
| Signal generators | `sim/Oscillator`, `SineTable`, `FmModulator`, `RdsEncoder`, `RdsBaseband`, `MpxGenerator`, `SimBand` | FM, NFM, AM, SSB and CW stations with stereo and RDS, shared by the simulator and the tests |
| RDS | `rds/RdsCode`, `RdsDecoder`, `RdsDemodulator` | Block code with burst correction, block sync, station name, radio text and clock time, 57 kHz demodulator |
| Demodulators | `demod/Demodulator`, `FmDiscriminator`, `StereoDecoder`, `WfmDemod`, `NfmDemod`, `AmDemod`, `PassbandDemod` (SSB, CW), `Squelch` | The listening modes of spec §6.4 |
| Spectrum | `spectrum/SpectrumAnalyzer` | FFT frames at a set rate, averaging, peak hold, noise-floor estimate, latest-frame slot for the UI |
| Engine | `engine/AudioSink`, `Mode`, `Channelizer`, `AudioOutput`, `ReceiverChain`, `TuningPolicy`, `ReceiverSettings` (+ `ReceiverStatus`, `ReceiverListener`), `Receiver` | Channel filter and decimation, 48 kHz audio with drift control, the listening chain, tuning rules, and the receiver with its DSP thread |

The public surface Plan 3 builds on: `Receiver` (`start`, `stop`, `update`, `settings`, `status`,
`spectrum`), `ReceiverSettings`, `ReceiverListener` (`onSourceStarted`, `onStatus`, `onRds`,
`onWarning`, `onSourceError`, `onStopped`), `AudioSink`, `IqSource` with `RtlTcpClient`,
`WavIqFileSource` and `SimulatorSource`.

## 3. How it was verified

- Every task: a failing test first, then the code, then the task's tests and the whole suite. A
  second agent reviewed each task against its brief and the spec; findings rated Important were fixed
  before the next task started and re-reviewed.
- End-to-end tests run real signals through the real chain: a synthetic FM station with stereo and
  RDS is modulated, channelised, demodulated and decoded (station name, radio text, clock); narrow
  modes are checked for tone frequency, level, and rejection of the other sideband and of neighbours; RDS is decoded under
  noise; the receiver is driven through retunes, refused requests, stale blocks, source failure and
  stop, with a scripted source that makes block counts exact.
- `PerformanceTest` measures throughput and per-block allocation in every mode and proves that the
  measured path produced audio (and, for FM, stereo and RDS).
- The whole branch was reviewed once more by the most capable model: lifecycle and concurrency,
  the signal path, the tests, and a triage of every deferred minor finding.
- Not verified here: anything on Android or with real hardware (§7), and the Gradle build (Google's
  Maven host is unreachable from the build machine; CI arrives with Plan 3).

## 4. Decisions made during execution

The plan's own 14 design decisions are listed at its top and are now written into the spec
(§4.1–4.3, §6.3, §6.4, §7.1, §11). The decisions below were taken while executing. Each has its
reason and what it costs if it proves wrong.

### Process

- Implementers could apply the plan's listings with a helper script instead of retyping them; they
  still ran every failing and passing step and read the diff. Reason: the listings were verified by
  replay, and exactness mattered. Cost if wrong: none seen; review caught what the listings got wrong.
- Each commit names the model that made it in its `Co-Authored-By` line. Cost if wrong: trailers can
  be rewritten before merge.
- The plan document was committed only after the user approved it.

### Task 8 — NFM high-pass

- The 300 Hz high-pass in `NfmDemod` is 8th order (the plan had 4th). With the de-emphasis make-up
  gain, a 4th-order filter let sub-audible squelch tones above about 200 Hz through at −4.5 dB; they
  are now at −17.8 dB at 203.5 Hz, and speech from 350 Hz up is unchanged within 0.4 dB. The
  reviewer's alternative (moving the make-up gain) was rejected: the chain is linear, so the order of
  gain and filter changes nothing. Cost if wrong: one constant.

### Task 10a — exit command lost on close (Plan 1 defect)

- `RtlTcpClient.close()` wrote the exit command and closed the socket while samples were still
  arriving; the resulting reset could make the driver discard the unread command, so the Android
  driver app would keep the dongle. Now the client sends the command, shuts down its output and keeps
  reading for up to 1 s until the driver closes; blocks that arrive meanwhile are released, not
  delivered. An interrupt during that wait no longer skips the cleanup. Fixed at once rather than at
  the end because every later task runs the full suite and the flaky test cost a rerun each time.
  Cost if wrong: `close()` can take up to 1 s longer against a server that never closes.

### Task 12 — a frequency the source refuses

- A refused frequency is not taken. The receiver warns once, puts `settings.frequencyHz` back to the
  frequency in force, keeps receiving undisturbed, and does not ask the source for the same tuner
  frequency again on unrelated updates; asking for it again later is a new request and is refused
  again with a warning. The plan's code clamped the channel to the stream edge and warned on every
  later update. Cost if wrong: the UI's frequency display jumps back after a refused entry, which is
  the intended signal.

### Task 14 — the allocation test

- The test now asserts, per mode, that the measured windows produced at least 90 % of the expected
  audio, and for FM that stereo and the RDS station name were present. Before, a mode that produced
  nothing would have passed. Cost if wrong: none.

### Task 15 — spec wording

- §7.1 step 1 and §4.3 now describe tune epochs (the built mechanism) instead of discarding by
  sequence number, including the 5 s give-up. Three one-line corrections went in with it: the exact
  FM decimation rule (§6.3), "noise bandwidth" in the automatic squelch rule (§6.4), and
  `centerFrequencyHz` and `recalibrate()` in the `IqSource` listing (§4.2).

### Final review

The whole-branch review found no critical defect and judged the branch ready with fixes. Its six
important findings all concern the API the Android app builds on.

- **Fixed in one wave, then re-reviewed:**
  - The receiver takes what depends on the source's kind (usable fraction, DC removal default) from
    `onStarted`, not from the placeholder a network source shows before its handshake, and reports
    `ReceiverListener.onSourceStarted(info)`. Before, an SDRplay server found by auto-detection was
    treated as an RTL for the whole session. Cost if wrong: one more callback for the app to handle.
  - `Receiver.stop(timeoutMs = 5000)` returns a Boolean and, when the DSP thread does not end in
    time (stuck in the audio sink or a callback), closes the source itself. Before, it returned
    silently with the dongle still held. Cost if wrong: a source can be closed from two threads; all
    sources tolerate a second close.
  - A sample rate the source refuses is put back like a refused frequency, and the stream is the
    truth: when confirmed blocks carry another rate than the settings (a refused first request, a
    fixed-rate source), the settings follow them and the tuner is planned with the real rate. A
    request the source ignores no longer counts as a retune. Before, the tuner could be placed by a
    rate the stream did not have. Cost if wrong: `settings.sampleRate` changes without the user,
    which is the intended signal.
  - The thread and timing contracts of `Receiver`, `ReceiverListener`, `AudioSink` and
    `SpectrumAnalyzer` are written in their KDoc.
  - `SineTable` clamps its index (a phase just below zero read past the table), and `BiquadTest`
    asserts the digital filter's real gain at 7 kHz (−26.0 ± 0.3 dB) instead of the analog value
    with a 2 dB tolerance.
- **Decided now, built first in Plan 3:** sources deliver a block when it is full or holds about
  20 ms of samples, and the queue is sized by duration (spec §4.3). Today a block is always 16,384
  samples: 7 ms at 2.4 MS/s but 68 ms at 240 kS/s. Not built now because it touches all three
  sources, the queue sizing and their tests, while the usual rates are unaffected. Cost if wrong:
  lumpy audio and a slow spectrum at the lowest rates until Plan 3's first task lands.
- **Decided:** the audio sink owns the refill after an underrun (silence until its buffer is back
  at the target level) and never blocks; the receiver does not write silence for blocks it
  discards (spec §4.2, §11). Cost if wrong: a small change in `AudioOutput` later.
- **One defect in the fix wave itself:** the re-review found that the analyzer replaced at
  `onStarted` lost the user's averaging and peak decay. The process allows one fix wave, so the
  controller fixed this directly, test first (a held peak fell with a peak decay of 0 dB/s; it no
  longer does). Cost if wrong: a five-line change that has its own test but no second reviewer.
- **Triage of the 60-odd minor findings from the task reviews:** a few were fixed in the wave, the
  rest are assigned to a later plan or dropped (§6).

## 5. Where the code differs from the plan document

The plan's listings were not edited after execution began. These files no longer equal them, each
because of a reviewed fix described in §4: `demod/NfmDemod.kt`, `source/rtltcp/RtlTcpClient.kt`,
`engine/Receiver.kt`, `engine/ReceiverSettings.kt`, `engine/AudioSink.kt` (KDoc),
`spectrum/SpectrumAnalyzer.kt` (KDoc), `source/IqSource.kt` (KDoc), `sim/SineTable.kt`, the spec, and
the tests `NarrowDemodTest`, `RtlTcpClientTest`, `FakeRtlTcpServer`, `ReceiverTest`,
`ScriptedSource`, `PerformanceTest`, `BiquadTest`, plus the new `SineTableTest`. The plan's test
counts are therefore lower than the suite's. The code and this report are the record; the plan
document is history.

## 6. Follow-ups for later plans

Plan 1's lists for Plans 3, 4 and 5 still stand (see its report, §5). Its "Before Plan 2" list was
taken up by Tasks 1, 2 and 15 of this plan; the periodic latency re-probe it asked for was replaced
by decision 4 of the plan. New items:

### Before the Android app (Plan 3)

- **First task: bound a block's duration.** Sources deliver a block when it is full or holds about
  20 ms (sample rate / 50), and the queue is sized by duration (spec §4.3). Today every block is
  16,384 samples: 7 ms at 2.4 MS/s, but 68 ms at 240 kS/s, which makes audio delivery lumpy against a
  150 ms sink buffer and caps the spectrum at 15 frames per second there. [final review I-4]
- **The Android audio sink follows the `AudioSink` contract:** `write` never blocks and drops what
  does not fit; after an underrun the sink plays silence until its buffer is back at its target
  level; the channel count can change with the mode; no audio arrives while a retune is pending.
  [final review I-5]
- Build sources with the kind the user chose, and take the final kind and capabilities from
  `ReceiverListener.onSourceStarted`. Do not call `Receiver.stop()` on the main thread: it can wait
  for the time-out plus the source's close. [final review]
- A hook to raise the DSP thread's priority and open a performance-hint session (spec §4.4); today
  the app can do it from the first callback. [final review M-5]
- `SimulatorSource` reports only interruption; an unexpected exception on its thread must go to
  `onError`. A listener's exception is reported as a source failure; give it its own error type.
  [T13, T12]
- `PerformanceTest` returns early (a pass) on a JVM without thread allocation counters; use an
  assumption so CI shows it as skipped. [T14]
- Tests still wanted: callbacks arrive on the DSP thread; queue overruns are counted and shown;
  `close()` returns promptly and no block is delivered after it. [T12, T10a]
- `RtlTcpClient.close()` guards with a plain flag; make it an atomic test-and-set now that
  `stop()` can close the source from a second thread. A `stop()` in the instant before the DSP
  thread starts returns true before `onStopped`; `stop()` lets an interrupt of its wait reach the
  caller. [fix-wave re-review]
- When a tunable source ignores a rate request, plan the tuner with the rate in force (no such
  source exists today). After the 5 s give-up, a late block with the real new epoch causes no
  reset. [fix-wave re-review]

### Before the sweep (Plan 4)

- Who keeps the time of the last latency probe for the "older than 5 minutes" rule (§7.1); nothing
  calls `recalibrate()` yet. [T15]
- One receiver owns its source for the whole session, but a sweep pauses the receiver and restores
  it (§7). Decide: a pause that lends the source, or a sweep that runs through the receiver.
  [final review]

### Before the scanner and recording (Plan 5)

- Automatic squelch after a reset: the noise floor reads a few dB low for about 100 ms, and an
  unknown floor holds the gate open, so each retune lets a short noise burst through and a scanner
  would see "open" on every new channel. Compensate the percentile for the number of averaged FFTs
  and hold the gate state while the floor is unknown. [final review M-2]
- Channel power for squelch and the S-meter is measured wider than the SSB and CW passbands, so a
  signal in the other sideband can open the squelch. [final review M-3]
- A block that yields no channel samples makes a manual squelch flicker; the FM discriminator emits
  one spike after a reset (a click on each scanner retune). [T10, T6]
- `Pll` and `RdsDemodulator` state can decay into denormal numbers on digital silence (an IQ file
  with exact zeros); flush at block end as `DcBlocker` does. [final review M-4]
- Recording "before the volume control" needs a tap inside `AudioOutput`; the scanner needs squelch
  state faster than the 10-per-second status. [final review]

### Polish (Plan 6)

- RDS radio text starts only after an error-free block B; check with field recordings. [T5]
- Test depth: RDS group 4A and version B edges, burst-correction and 50-block sync edges, `reset()`
  paths, odd sample rates, `SpectrumAnalyzer.latest` from a second thread. [T4–T7, T9]

### Dropped

The final review went through every other minor finding of the task reviews and recommended
dropping them; that was accepted. For the record, by task:

- T1: all six items (comment and guard polish in the hardened DSP primitives).
- T2: `BlockQueue(0)` accepted by the constructor (the only caller passes 8); a hard-coded epoch
  and a skipped `close()` in tests.
- T3: KDoc, `require` messages, a guard in the tone-fit test helper.
- T4: generator audio level constant, fully-qualified names, masking, KDoc.
- T5: the sync block not counted, `synced` KDoc, programme identification taken from block C′.
- T6: the noise filter not gated by pilot lock; `MultiplexTap` KDoc.
- T7: two comments; the NCO phase kept across `reset()`.
- T8: all items (KDoc and test style in the narrow demodulators).
- T9: a frame interval of 0 (ends in a clean stop with an error); `log10` under the frame lock.
- T10: a zero-frame write, KDoc, tests, a note on USB/LSB naming.
- T10a: a join-condition snapshot, KDoc, a 300 ms sleep in one test.
- T11: both items (simulated band wording).
- T12: the wanted centre after the give-up, a block left in the queue at stop, DC filter state
  across a rate change, a narrow put-back race, one status check that could be deterministic.
- T13: a burst after a stall, correlated noise between stations, a non-volatile thread field, the
  pacing test's bounds.
- T14: comment wording; a non-silence check in the allocation test.
- T15: classes missing from the §4.1 package tables; the wording about the WAV source's epoch.
- Not taken from the fix-wave re-review: re-planning the tuner when the usable fraction rises at
  `onStarted` (the first plan is only more cautious than needed).

## 7. To check on the first real-hardware run

Plan 1's list still applies. Added by Plan 2:

- Stereo: the pilot lock time and the blend thresholds (−52 dB full stereo, −40 dB mono) against
  real stations of different strength; the left/right orientation against a known broadcast.
- RDS: time to station name on a strong and on a weak station; no false names on empty channels.
- Automatic squelch on a real band: opens on signals, stays shut on noise, with RTL (usable
  fraction 0.75) and SDRplay (0.85).
- Narrow modes sit a quarter of the sample rate off the tuner centre; confirm there is no image or
  DC spur audible at that offset with a real tuner.
- The Android driver releases the dongle after the app closes the connection (Task 10a).
- Throughput on the phone: FM stereo with RDS and spectrum at 2.4 MS/s needs at least 3 times real
  time; watch `ReceiverStatus.load` and `overruns`.
