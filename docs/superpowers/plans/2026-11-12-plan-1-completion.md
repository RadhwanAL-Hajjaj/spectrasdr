# Plan 1 (core foundations) — completion report

Date: 2026-11-13 · Branch: `plan-1-core-foundations` · Plan: `2026-11-12-plan-1-core-foundations.md` · Spec: `../specs/2026-11-12-spectrasdr-design.md`

This report records what Plan 1 delivered, how it was checked, every decision taken during execution
that the plan or spec did not already settle, and the follow-ups that later plans must pick up.

## 1. Status

- All 13 tasks are implemented. Each was written test-first by one agent and then reviewed by a
  separate agent for spec compliance and code quality.
- Reviews found real defects in Tasks 8, 9, 12 and 13; each was fixed and re-reviewed. In Tasks 6 and
  12 the plan's own code failed the plan's own tests and was corrected.
- A whole-branch review then found one more defect and a few small gaps. They were fixed in one
  commit and re-reviewed.
- Result: 18 code commits, 28 source files (about 2,300 lines), 28 test files (about 2,100 lines),
  105 tests passing, no compiler warnings, the GPL-3.0-or-later header on every Kotlin file.

## 2. What exists now

| Area | Files (under `core/src/main/kotlin`) | Purpose |
|---|---|---|
| Test runner | `tools/test-core.sh` | Compiles `core` with kotlinc 2.1.21 and runs the JUnit 4 tests without Gradle |
| Samples and blocks | `source/SampleFormat`, `SampleConverter`, `IqBlock` (+ `BlockPool`), `BlockQueue` | Byte-to-float conversion, pooled IQ blocks, drop-oldest queue |
| DSP | `dsp/Fft`, `Window`, `Nco`, `FirDesign`, `FirDecimator`, `DecimatorChain`, `FractionalResampler`, `DriftController`, `DcBlocker`, `Agc`, `Deemphasis`, `Pll` | Building blocks for the spectrum and the demodulators |
| Source API | `source/IqSource` | `IqSource`, `SourceListener`, `SourceInfo`, gain, option and error types |
| Network source | `source/rtltcp/RtlTcpProtocol`, `RtlTcpClient`, `RspCapabilities` | rtl_tcp, the Android SDR driver's local server, and rsp_tcp extended mode |
| Retune synchronisation | `source/rtltcp/RetuneSync`, `CounterMarkerSync`, `LatencySync` | Tune epochs; exact boundaries from RTL2832U test-mode bursts; a latency probe and automatic fallback for servers without markers |
| Recording | `record/WavWriter`, `WavReader`, `RecordingNames`, `source/WavIqFileSource` | Crash-safe WAV files and replay of IQ recordings |

Not part of Plan 1, by design: the receiver pipeline and demodulators, the Android app and its CI
build, the sweep, the scanner and the recording features.

## 3. How it was verified

- Per task: a failing test was seen before the code was written, then an independent review of the
  diff against the task text and the spec.
- At the final code commit (`036099d`), on a clean export of the branch: the full suite twice,
  `OK (105 tests)` both times; a compile with warnings enabled, 0 warnings.
- Flakiness checks on the retune tests: `MarkerRetuneTest` 80 runs and `LatencyRetuneTest` 10 runs
  after the Task 12 fixes; the final reviewer ran the full suite 4 times and the retune tests 6 times.
- **Not verified:** anything on real hardware, and the Gradle/Android build. This sandbox cannot reach
  Google's Maven repository; the CI build on GitHub covers that in the Android plan.

## 4. Decisions made during execution

Each entry gives the decision, the reason, and what it costs if it is wrong.

### Setup

1. **Feature branch instead of a git worktree.** The repository was a fresh local one whose `main`
   held only documents, so a worktree isolated nothing. *If wrong:* nothing lost; the branch can move
   to a worktree at any time.
2. **Kotlin compiler pre-seeded** into the cache that `tools/test-core.sh` uses, so helper agents did
   not download it again. *If wrong:* the script's download path was not exercised here; CI will
   exercise it.
3. **Model per role.** The cheapest model for tasks whose code the plan gives in full, the standard
   model for multi-file and threaded tasks, the most capable model for the two concurrency-heavy task
   reviews and the final review. *If wrong:* extra fix rounds.
4. **Commit trailers name the model that made each commit.** The plan's Global Constraint had copied
   one model name for every commit. The session line is unchanged. *If wrong:* trailers differ
   between commits; they can be rewritten before merge.
5. **Standard model for Tasks 4–13.** Two cheapest-model helpers each created a stray one-off
   reminder in the session owner's account (both fired once and are spent). Later dispatches named
   the forbidden tools explicitly. *If wrong:* only a higher cost per task.

### Tasks 5–8 (DSP)

6. **`DecimatorChain` keeps the plan's behaviour where spec §6.1's "at most 8 per stage" cannot
   hold.** A prime factor above 8 becomes its own stage, and the 3-stage limit wins for factors above
   512 (1024 becomes 16, 8, 8). Since 8³ = 512, both limits cannot hold together; choosing rates that
   factor well is the receiver's job. *If wrong:* one oversized stage costs extra CPU.
7. **`FractionalResampler.process()` deviates from the plan's code.** Output positions are computed
   as start + n × step instead of being accumulated, and positions within 1e-9 of a whole sample are
   snapped. The plan's code failed the plan's own block-splitting test. The reviewer traced the
   change as correct. *If wrong:* a seam or an off-by-one at block edges.
8. **`Pll` gained `minLockAmplitude` (default 1e-3).** Below it the lock metric decays, so silence no
   longer reads as "pilot locked". *If wrong:* a real pilot weaker than 1e-3 reads as unlocked and FM
   decodes in mono.

### Task 9 (rtl_tcp client)

9. **Close order.** `close()` marks the client closed, sends the Android driver's exit command while
   the reader still runs (waiting up to 1 s), and only then stops. Errors are not reported once the
   user has closed. Spec §5.1 requires the exit before the socket closes; the plan's order could lose
   it. *If wrong:* an error that happens during a user close is not reported.
10. **One lock for ordering.** Every retune, gain, ppm and option change, and the initial replay, is
    wrapped, numbered and queued under one lock, so the order on the wire matches the epoch order
    (§7.1). *If wrong:* callers hold the lock slightly longer; queueing never blocks.
11. **Epoch baseline.** The stream counts retunes from the initial replay, not from zero. Retunes
    requested before the connection never carried markers; counting them made the marker sync wait
    for bursts that were never sent. *If wrong:* nothing visible.
12. **Read timeouts.** `readTimeoutMs = 5000`. A server that accepts the connection but sends nothing
    is reported as possibly busy with another client; silence in mid-stream is reported as ended
    (spec §11). The plan's code would wait for ever. *If wrong:* a server that is silent for more
    than 5 s in mid-stream is reported as ended.
13. **The command thread survives an interrupt during a delayed command.** `close()` interrupts it;
    uncaught, that would end the process on Android. *If wrong:* nothing.

### Tasks 11–12 (retune synchronisation)

14. **Two additions to Task 12.** The reader thread replaces the sync object only under the lock of
    decision 10, and a regression test pins decision 11. *If wrong:* one more test to maintain.
15. **Marker timeout and fallback rule (plan defect).** The marker timeout restarts at every burst
    end. Marker sync falls back to latency sync only if it timed out and has never seen a burst,
    which means the server ignores test mode. A `markerTimeouts` counter was added for diagnostics.
    With the plan's rule, back-to-back retunes sometimes triggered the fallback, whose probe starved
    the awaited epoch; the test failed about half the time. *If wrong:* a server whose test mode
    stops working in mid-session keeps marker sync and loses 1 s per retune instead of falling back.
16. **RSP gain restore.** AGC, IF gain reduction and LNA state are remembered separately. After a
    probe each known one is restored; AGC is switched on only when neither AGC nor IF gain reduction
    was ever set. The plan's code could leave an SDRplay at maximum IF gain reduction after every
    probe. *If wrong:* a manual IF gain reduction could be replaced by AGC when only the LNA state
    had been set.
17. **`recalibrate()` acts only on a client that uses latency sync.** Otherwise it permanently
    replaced marker sync with latency sync. *If wrong:* a marker-capable server cannot be re-probed
    on demand.
18. **The end of the probe is one locked section** (new sync plus the restoring retune). A caller's
    retune could otherwise slip in between and leave the labels and the tuner out of step.
    *If wrong:* nothing.
19. **The initial replay sends RSP gain commands only if the user set an RSP gain.** No forced
    "AGC on" at connect. *If wrong:* a server whose default is manual gain keeps it at connect.
20. **First hypothesis for the remaining flake.** "A stalled fake server produces zero-length
    test-mode pulses; fix the fake, not the client." Reproduction showed it was not the whole
    explanation; decisions 21 and 22 followed. *If wrong:* it cost one extra investigation round.
21. **The marker timeout needs both 1 s worth of non-burst samples and 1 s of wall-clock time.** An
    unpaced test server can buffer more than 1 s of old samples within milliseconds. Real-time
    sources cannot, so nothing changes for them. *If wrong:* a server that ignores test mode falls
    back after the longer of the two limits instead of after 1 s of samples.
22. **The fake server's pulse-length realism was kept**, against the controller's earlier
    instruction: a test-mode pulse lasts its wall-clock duration even when the reader stalls. Under a
    stall both effects occur, and the change models the real chip. *If wrong:* the fake is slightly
    more lenient than a stalled real server.
23. **Parked residual accepted.** Under an artificial 1000 ms start-up stall the back-to-back retune
    test still failed 1 run in 3, and the cause was not found. Realistic runs passed 80 of 80. A lost
    start-up burst degrades to latency sync, never to wrong labels. *If wrong:* on a device that
    stalls for about a second at connect, a session may start in latency sync instead of marker sync.

### Task 13 (WAV files)

24. **`WavWriter` failure path.** The header counts only bytes that were actually written. After a
    failed write the header is rewritten on a best-effort basis, the writer refuses further writes,
    and `close()` always closes the file without raising a second error (spec §11, "keep the file
    valid"). *If wrong:* a caller that expects `close()` to report a late error gets it from
    `write()` instead.
25. **An IQ file with no samples is reported as an error** and playback stops. With looping enabled
    it used to spin at 100 % CPU. *If wrong:* nothing.
26. **Every malformed WAV header gives one readable error** ("… is not a valid WAV file: …") in place
    of raw runtime errors. *If wrong:* file errors arrive when the source is constructed, not through
    `onError` as they do for network sources.
27. **`WavWriter` writes its header with the first flush**, not in the constructor, so a write
    failure is always reported by `write()`. *If wrong:* a recording killed before its first flush
    leaves an empty file instead of a header-only file.

### Final review

28. **Scope of the single fix wave.** Overflow-safe playback pacing (it failed after about 64 minutes
    of looped playback at 2.4 MS/s), rejecting a WAV sample rate of 0, a frequency range check,
    config validation at construction, `open()` strictly once, and documentation for non-tunable
    sources. *If wrong:* a caller that relied on silent wrap, an invalid config or a second `open()`
    now gets an immediate exception.
29. **The RSP probe keeps its full IF-gain step**, which is larger than the spec's 20 dB, and the
    receiver owns the periodic re-probe. Spec §7.1 is to be aligned when the receiver plan is
    written. *If wrong:* each probe changes RSP gain by more than specified for 1–3 s.
30. **Reader-thread exception mapping was left for the Android plan**, where the reconnect design
    decides how a misbehaving listener is handled. *If wrong:* until then a listener that throws
    ends its reader thread.
31. **This report is committed to the repository.** The execution ledger is untracked scratch and is
    deleted after the merge; later plans need the follow-ups below. *If wrong:* one extra
    documentation commit.

## 5. Follow-ups for later plans

Reviewers logged these as minor. None blocks the merge. The origin task is in brackets.

### Before the receiver and demodulators (Plan 2)

- `Agc`: the first loud samples get the full 60 dB gain, and loud steps overshoot; there is no output
  clamp. Clamp or limit before the audio sink, or start the envelope at the target; add a reset. [T7]
- A NaN input permanently poisons the state of `Agc`, `DcBlocker` and `Deemphasis`. [T7]
- `DcBlocker` and `Deemphasis` state can stick in denormal values after exact-zero input (squelch). [T7]
- `RtlTcpClient`: clear the block reference before `onBlock`, so that a listener which releases the
  block and then throws does not cause a second release that hides its exception. [T9]
- `Nco` is not thread-safe: hand tuning changes from the UI thread to the DSP thread safely. [T4]
- The receiver owns the latency re-probe (every 5 minutes and after reconnects) through
  `recalibrate()`. [final review]
- Choose sample rates whose decimation factors split into stages of at most 8; correct the
  `planStages` KDoc. [T5]
- `Pll`: the float phase can reach 2π by one ulp; `hypot` and `atan2` per sample are slow on Android,
  so compute the amplitude only when it is read. [T8]
- `FractionalResampler`: validate the output size (`maxOutput` is computed for the current
  correction). Size the work buffers once; the same applies to `FirDecimator` and
  `DecimatorChain`. [T5, T6]
- Align spec §7.1 with the probe as built. [final review]

### Before the Android app and CI (Plan 3)

- Map unexpected exceptions on reader threads, including exceptions thrown by a listener, to an error
  callback. On Android an uncaught exception ends the process. [T9, final review]
- Reconnect design: one client is one connection (now enforced). A reconnect creates a new client and
  carries frequency, rate and gain over. [T9, final review]
- `open()`/`close()` race at start-up: a `close()` before the socket exists lets the reader connect
  and call `onStarted` afterwards. [T9]
- `markerTimeouts` reads the current sync, so it drops to 0 after a fallback. Keep the count in the
  client for the Diagnostics screen. [T12]
- `close()` swallows an interrupt while it waits for the exit command. [T9]
- `setOption(BiasT)` before the handshake picks the RTL command on an RSP server; the replay corrects
  it. [T10]
- AUTO chooses markers for an rsp_tcp server that sends no extended header, so each retune waits 1 s
  until the fallback. The fallback also applies under explicit MARKER mode. [T11, T12]
- Messages: "for 5.0 s"; "x.wav: null" for a truncated file. [T9, T13]
- File source: a final block can arrive after `close()` at low sample rates; a file name without
  "…Hz" silently gives centre frequency 0; WAVE_FORMAT_EXTENSIBLE and float32 IQ files are rejected;
  `RecordingNames` does not sanitise the mode text. [T13]
- Build: `tools/test-core.sh` hides compiler warnings with `-nowarn` (there are none today), has no
  guard for an empty test set, and prints the JVM's `JAVA_TOOL_OPTIONS` banner.
  `BlockQueueTest.takeWaitsForTheProducer` depends on timing and may fail on a loaded CI
  runner. [T1, T2, T12]

### Before the sweep (Plan 4)

- A user command during a latency probe can shorten the measured latency. [T12]
- A failed or timed-out probe command can leave the tuner at probe gain. [T12]
- An all-zero baseline makes the step detector report a 512-sample latency. [T12]
- The settle length is fixed in samples when the sync is created and does not follow a later change
  of sample rate. [T12]
- With `RspAgc(off)` and no IF gain reduction ever set, the probe's maximum reduction stays in
  place. [T12]
- Show `markerTimeouts` in the sweep: 1 s per hop would be very visible. [final review]

### Before recording (Plan 5)

- `WavWriter`: document the single-thread contract; an odd data size gets no RIFF pad byte; split
  files at 2 GiB (spec §9). [T13]

### Any time (validation and test depth; no effect on behaviour today)

- Argument validation: `BlockQueue`, `BlockPool` and `IqBlock` capacities; `Windows.create(n)`;
  `FirDesign.kaiserTaps` (a zero transition silently gives 3 taps); `besselI0(NaN)`; `Nco`;
  `DecimatorChain.design`; `Agc` (a target of 0 divides by zero); `Pll`. [T2–T8]
- A standalone `IqBlock.release()` is a silent no-op; `BlockPool.obtain` writes two fields outside
  its lock, which is safe today. [T2]
- Test gaps: S16LE conversion with offsets and caps; queue take-after-clear; FFT size boundaries; NCO
  renormalisation (its test cannot fail without the code); decimator reset, `maxOutput`, even tap
  counts and a 66 dB alias check; resampler correction changes and 1-frame blocks; `Agc` hang and
  decay and a lower bound on the gain cap; `DcBlocker` cutoff and chunk continuity; `Pll` negative
  offsets and chunking; client replay of pre-open settings, bad magic, split samples and error-free
  close; RSP with `detectExtended = false`, a truncated RSP0, U8 on an S16 server and an unknown
  model; deterministic tests for the burst-end timeout reset and the 120 ms Android-driver default;
  the file source with truncated, 16-bit and odd-chunk files. [T2–T13]
- Small items: `CounterMarkerSync` needs 65 bytes for `minRun = 64`, its `timedOut` flag is sticky by
  design, and its guard length can lose one sample to truncation; the resampler's last phase row
  lacks the first prototype tap (about −100 dB); `DecimatorChain.stageFactors` allocates on each
  access; `RspCapabilities.encode` keeps 12 name bytes where `parse` reads 13; the fake server
  schedules a lone "test mode off" far ahead; `Signals.zeroCrossingFrequency` silently returns NaN
  with fewer than two crossings; a redundant write in `WavTest.rejectsZeroSampleRate`. [T3–T13]
- `RtlTcpClient.kt` is about 590 lines with the probe inside. Extract a probe collaborator before it
  grows again. [T12]

### Resolved or moot since they were logged

- `WavWriter` validates its arguments before it opens the file (Task 13 fix round).
- `WavReader` rejects a sample rate of 0; `RtlTcpConfig` validates its fields; `open()` is strictly
  once (final fix wave).
- `rspCapabilities` is never reset in the handshake: moot now that a client connects only once. [T10]

## 6. To check on the first real-hardware run

- RTL-SDR Blog V4 through the SDR driver app: does the RTL2832U counter restart between two
  test-mode bursts that arrive back to back? If two bursts merge, a double retune waits for the 1 s
  timeout: the labels stay correct, but the retune is slower.
- Pipeline latency. If it ever exceeded the 1 s marker timeout, test-pattern bytes would be delivered
  as samples. Real pipelines are expected at 100–300 ms; measure it.
- After connect, `syncMode` should read MARKER, and `markerTimeouts` should stay at 0 in normal use.
- rsp_tcp: the latency probe's result, that gain is restored after the probe, and that the RSP0
  capabilities parse for the actual model.
- Recording to a nearly full disk keeps the WAV file valid.
