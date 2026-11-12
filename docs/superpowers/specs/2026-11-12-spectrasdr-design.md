# SpectraSDR — Design Spec (v1)

- **Date:** 2026-11-12
- **Status:** Design approved in chat; this written spec awaits review
- **License:** GPL-3.0-or-later
- **Working name:** SpectraSDR (renamable before first release)

## 1. Purpose

An open-source Android SDR receiver with three jobs:

1. **Real-time spectrum analyzer** — spectrum + waterfall of the tuner's live bandwidth.
2. **Wideband sweep analyzer** — TXAdvance-style sweeps across hundreds of MHz by hopping the tuner and stitching captures.
3. **Listening** — WFM (stereo + RDS), NFM, AM, USB/LSB, CW, with a channel scanner and audio/IQ recording.

Hardware: RTL-SDR dongles (including RTL-SDR Blog V4 and V4 Lite) and SDRplay RSP receivers.

Primary test device: Samsung Galaxy S24 Ultra (arm64, Android 14+) with an RTL-SDR Blog V4/V4L on USB-C OTG. The app must run on any Android 8.0+ device (arm64, armv7, x86-64).

## 2. Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Kotlin app; IQ arrives from external driver apps and TCP servers; **no native code in v1** | Fastest route to a working app; the signal engine is fully testable on the JVM; drivers are maintained by others |
| D2 | RTL-SDR over USB through SignalWare's **SDR driver** app (`marto.rtl_tcp_andro`) using the `iqsrc://` intent | v3.16 (2026-08-16) added RTL-SDR Blog V4 Lite support alongside V4; standard rtl_tcp protocol; same path also reaches HackRF and the legacy SDRplay Android driver |
| D3 | SDRplay in v1 through **rsp_tcp** (SDRplay/RSPTCPServer) on a Raspberry Pi or PC, standard and extended (14-bit) modes | Mature, official, exposes every RSP control |
| D4 | SDRplay direct USB through **OpenRSP** in phase 2 as a separate JNI module | OpenRSP's current build is from 2026-11-03 and its client-demo source is unpublished; it plugs into the same `IqSource` interface |
| D5 | GPL-3.0-or-later, public GitHub repository | User's choice; keeps OpenRSP and librtlsdr embedding open |
| D6 | `:core` module uses the **Kotlin standard library only** (no coroutines, no Android) | Builds and tests in the development workspace, which cannot reach Maven; keeps the engine portable |
| D7 | Android code is compiled and linted by **GitHub Actions** | The workspace cannot reach Google's Maven repository |
| D8 | Jetpack Compose + Material 3; OpenGL ES 3.0 for the waterfall | Native Android look; waterfall needs per-row GPU updates |
| D9 | minSdk 26, targetSdk/compileSdk 36 | OpenRSP needs Android 8.0+; current Play target-API rules |
| D10 | applicationId `io.github.radhwanalhajjaj.spectrasdr` | GitHub-namespace convention for open-source apps |

## 3. Scope

**In v1:** receiver screen, sweep analyzer, scanner, recordings, sources and settings, background listening (all detailed below).

**Out of v1:** DAB/DAB+, digital voice (DMR, P25, NXDN), ADS-B/AIS decoders, CTCSS/DCS decoding, OpenRSP direct USB, embedded librtlsdr or native DSP, frequency coordination / intermod planning, cloud scan sharing, GPS tagging, transmitting.

## 4. Architecture

### 4.1 Modules

**`:core`** — Kotlin/JVM library, stdlib only.

| Package | Contents |
|---------|----------|
| `source` | `IqSource` interface, sample converters, pooled `IqBlock`, `RtlTcpClient` (rtl_tcp + rsp_tcp extended), `WavIqFileSource` |
| `dsp` | `Fft`, windows, `Nco`, `FirDesign` (Kaiser windowed-sinc), `FirDecimator` (polyphase, complex), `DecimatorChain`, `FractionalResampler` with drift control, `DcBlocker`, `Agc`, `Deemphasis`, `Pll` |
| `demod` | `Demodulator` interface; `WfmDemod`, `StereoDecoder`, `NfmDemod`, `AmDemod`, `SsbDemod` (USB/LSB), `CwDemod`; `Squelch` |
| `rds` | `RdsDemodulator` (57 kHz subcarrier → bits), `RdsDecoder` (block sync, groups → `RdsState`) |
| `spectrum` | `SpectrumAnalyzer` (FFT frames, averaging, peak hold, display binning) |
| `sweep` | `SweepPlanner`, `SweepEngine`, `SweepResult`, CSV export |
| `scan` | `Channel`, `ScanList`, `Scanner` state machine, `ActivityLog` |
| `record` | `WavWriter` (crash-safe), `IqRecorder`, `AudioRecorder`, file naming and parsing |
| `engine` | `Receiver` orchestrator, `AudioSink` interface, listener interfaces |

**`:app`** — Android: Compose UI, `RadioService` (foreground, `mediaPlayback` type), `DriverLauncher` (`iqsrc` hand-off), `AndroidAudioSink` (AudioTrack), `WaterfallView` (GLSurfaceView), persistence (Room + DataStore), recordings browser (FileProvider sharing).

**`:openrsp`** (phase 2) — NDK/C++ JNI bridge to OpenRSP, exposing `OpenRspSource : IqSource`.

### 4.2 Core interfaces

```kotlin
interface IqSource {
    val info: SourceInfo                  // name, kind, capabilities, rates, gain model
    fun open(listener: SourceListener)    // non-blocking; starts the source thread
    fun close()
    fun setCenterFrequency(hz: Long)
    fun setSampleRate(sps: Int)
    fun setGain(gain: GainSetting)        // Auto, TunerTenthsDb, TunerIndex, RspLna, RspIfgr, RspAgc
    fun setPpm(ppm: Int)
    fun setOption(option: SourceOption)   // BiasT, DirectSampling, OffsetTuning, RspAntenna, RspNotches
}

interface SourceListener {
    fun onStarted(info: SourceInfo)
    fun onBlock(block: IqBlock)           // block carries seq, centerHz, sampleRate, interleaved floats
    fun onError(error: SourceError)
    fun onStopped()
}

interface AudioSink {
    val sampleRate: Int                   // 48000
    fun write(samples: FloatArray, frames: Int, channels: Int)
    fun fillLevel(): Float                // 0..1, drives drift compensation
}
```

### 4.3 Threads and data flow

1. **Source thread** (one per source): blocking reads → conversion to interleaved float IQ in pooled `IqBlock`s (16,384 complex samples each, tagged with a monotonically increasing `seq`, `centerHz`, `sampleRate`) → bounded queue holding about 0.5 s. When full, the oldest block is dropped and `overruns` increments.
2. **DSP thread:** for every block, the spectrum analyzer and the active demodulator chain run; audio goes to the `AudioSink`; the newest `SpectrumFrame` is published to a latest-value slot.
3. **UI/GL thread:** reads the latest frame at vsync; pushes waterfall rows to the GPU.

`AudioSink.write` must return within a few milliseconds. `AndroidAudioSink` uses an AudioTrack (PCM float, about 150 ms buffer, non-blocking writes) behind a small ring buffer.

## 5. Protocols

### 5.1 SDR driver app hand-off (USB)

- Manifest declares `<queries>` for `ACTION_VIEW` with scheme `iqsrc` (Android 11+ package visibility).
- Handlers are enumerated with `queryIntentActivities` and tried in turn until one returns `RESULT_OK`, so the system chooser never pins one driver.
- Intent: `ACTION_VIEW`, data `iqsrc://-a 127.0.0.1 -p <port> -s <sps> -f <hz> -P <ppm>`, launched through the Activity Result API. Gain is not passed in the intent; it is set over TCP after connecting.
- `RESULT_OK`: read `supportedTcpCommands` (int array; absent on old drivers) and connect TCP to `127.0.0.1:<port>`.
- Failure: show `detailed_exception_message` (code in `detailed_exception_code`).
- Driver missing: offer `market://details?id=marto.rtl_tcp_andro`.
- Optional auto-start on broadcast `com.sdrtouch.rtlsdr.SDR_DEVICE_ATTACHED`.
- Shutdown: send `0x7e` (exit) before closing the socket.
- Port: a free port found by binding an ephemeral socket; fallback 1234.

### 5.2 rtl_tcp stream

- **Header (12 bytes):** 4-byte magic (`RTL0`), tuner type u32 big-endian, gain count u32 big-endian. Tuner type selects the gain table (R820T/R828D: 29 steps, 0.0–49.6 dB).
- **Samples:** u8 I, u8 Q, centred on 127.5.
- **Commands (5 bytes):** u8 code + u32 big-endian parameter.
  - `0x01` frequency (Hz), `0x02` sample rate, `0x03` gain mode (0 auto, 1 manual), `0x04` gain (tenths of dB), `0x05` PPM, `0x06` IF gain, `0x07` test mode, `0x08` RTL AGC, `0x09` direct sampling (0 off, 1 I, 2 Q), `0x0a` offset tuning, `0x0b` RTL xtal, `0x0c` tuner xtal, `0x0d` gain by index, `0x0e` bias-T.
  - Android driver extras: `0x7e` exit, `0x7f` gain by percentage, `0x80` 16-bit signed samples.
- When `supportedTcpCommands` is present, only listed commands are sent, and the UI hides controls the source does not support (for example, the SDR driver app does not list bias-T `0x0e`).

### 5.3 rsp_tcp extended mode (SDRplay)

- After the 12-byte header, an extended server sends a **45-byte packed `RSP0` capability struct**:

  | Field | Type | Notes |
  |-------|------|-------|
  | magic | char[4] | `RSP0` |
  | version | u32 BE | 1 |
  | capabilities | u32 BE | bit0 bias-T, bit1 ref out, bit2 ref in, bit3 broadcast notch, bit4 DAB notch, bit5 AM notch, bit6 RF notch, bit7 AGC |
  | reserved | u32 | — |
  | hardware_version | u32 BE | RSP model id |
  | sample_format | u32 BE | 1 = u8, 2 = s16 |
  | antenna_input_count | u8 | |
  | third_antenna_name | char[13] | NUL-terminated |
  | third_antenna_freq_limit | i32 | **host byte order** (little-endian in practice; the server does not convert it) |
  | tuner_count | u8 | |
  | ifgr_min / ifgr_max | u8, u8 | IF gain reduction range (dB) |

- **Detection:** after the header, read 4 bytes. `RSP0` → read the remaining 41 bytes. Anything else → those 4 bytes are the first IQ samples. The user can also force standard mode.
- **s16 samples:** little-endian, interleaved I/Q, scaled by 1/32768.
- **Extended commands:** `0x1f` antenna (0 A, 1 B, 2 Hi-Z), `0x20` LNA state, `0x21` IF gain reduction (dB), `0x22` AGC (0/1), `0x23` AGC set point (dBFS, signed), `0x24` notch bitmask (AM 1, broadcast 2, DAB 4, RF 8), `0x25` bias-T, `0x26` reference out.
- The UI shows the network load: s16 at N MS/s needs about 32·N Mbit/s (2 MS/s ≈ 64 Mbit/s).

### 5.4 IQ WAV files

- RIFF/WAVE PCM, 2 channels (I = left, Q = right), 8-bit unsigned or 16-bit signed little-endian; WAV sample rate = IQ sample rate.
- Centre frequency is parsed from the file name pattern `(\d+)Hz` (SDR# and SDR++ conventions).
- SpectraSDR writes `SpectraSDR_yyyyMMdd_HHmmssZ_<freq>Hz_IQ.wav`.
- Playback is paced in real time and loops optionally.

## 6. Signal processing

### 6.1 Front end

- Conversion: u8 → (v − 127.5) / 127.5; s16 → v / 32768.
- DC removal: single-pole high-pass on IQ, on by default for RTL sources in the receiver (the sweep handles DC separately).

### 6.2 Spectrum

- FFT sizes 1,024–65,536 (default 4,096). Windows: Blackman-Harris 4-term (default) or Hann.
- Level in dBFS = 10·log10(|X|²) − 20·log10(Σw), so a full-scale complex tone reads 0 dBFS.
- Exponential averaging on linear power (α 0.1–1), peak hold with configurable decay, 10–60 frames/s (default 30), up to 4 FFTs averaged per frame within the CPU budget.
- Display binning: maximum of the FFT bins covered by each pixel column of the visible span (zoom and pan).

### 6.3 Channelizer

- NCO (double-precision phase) shifts the VFO offset to 0 Hz.
- Multistage polyphase FIR decimation (up to 3 stages, Kaiser-designed, ≥ 60 dB alias rejection), with the largest decimation first where the transition band is wide.
- **WFM:** decimation D = max(1, floor(fs / 240 kHz)), giving 240–480 kHz (2.4 MS/s → 240 kHz); rates below 240 kHz run undecimated. Channel filter ±100 kHz by default.
- **Narrow modes:** integer decimation to 48–96 kHz, then the fractional resampler to exactly 48 kHz.
- Mode filters: NFM 12.5 kHz (presets 6.25/25), AM 8 kHz (6/10), SSB 300–2,800 Hz, CW 500 Hz (250/1,000) around the pitch.

### 6.4 Demodulators

- **WFM:** polar discriminator arg(x[n]·x*[n−1]), MPX scaled so ±75 kHz deviation = ±1.0. Mono: 15 kHz LPF → de-emphasis (50 µs default, 75 µs option) → 48 kHz.
- **Stereo:** 19 kHz pilot PLL with lock detector; S = LPF₁₅ₖ(MPX · 2cos 2θ); L = M + S, R = M − S. M and S use identical filters so delays match. De-emphasis per channel; blend toward mono on weak pilot or noisy S.
- **RDS:** mix MPX down by 57 kHz (3θ from the pilot PLL when locked, otherwise a free-running Costas loop) → 2.4 kHz LPF → decimate → Costas loop for residual phase → symbol timing recovery at 2,375 baud → biphase decode to 1,187.5 bit/s → differential decode → block sync.
  - Block check: generator g(x) = x¹⁰ + x⁸ + x⁷ + x⁵ + x⁴ + x³ + 1; offset words A `0x0FC`, B `0x198`, C `0x168`, C′ `0x350`, D `0x1B4`. Syndromes are derived from the offset words at start-up.
  - Groups decoded: PI, PTY, TP/TA, 0A/0B (programme service name), 2A/2B (radio text, A/B flag handling), 4A (clock time).
- **NFM:** discriminator scaled so ±5 kHz = ±1.0 (±2.5 kHz option), 300 Hz high-pass (removes CTCSS), 3.5 kHz LPF, de-emphasis on by default.
- **AM:** envelope detector, DC block, AGC.
- **SSB:** shift the passband centre to 0 Hz (USB +1,550 Hz, LSB −1,550 Hz), complex LPF ±1,250 Hz, shift back, take the real part, AGC (attack 2 ms, decay 500 ms, hang 200 ms).
- **CW:** as USB with the passband centred on the pitch (default 700 Hz).
- **Squelch:** channel power in dBFS after the channel filter, 3 dB hysteresis. Auto squelch = noise floor (20th percentile of recent channel power) + 6 dB. Audio is gated with 5 ms ramps. Squelch state also drives the scanner.

### 6.5 Audio output

- 48 kHz float, mono or stereo; volume, mute and a soft limiter.
- Fractional resampler (polyphase windowed-sinc). Ratio = demod rate / 48,000 × (1 + correction); a PI controller holds the sink fill level near 50 % with at most ±500 ppm of correction, so the SDR and phone clocks can drift without stutter.

## 7. Sweep analyzer

- **Inputs:** start and stop frequency, resolution bandwidth (FFT size = next power of two ≥ fs / RBW), usable fraction u (RTL 0.75, SDRplay 0.85), dwell = K FFTs averaged per hop (default 8), settle time (default 120 ms for the USB driver app, 250 ms for network sources; user-adjustable).
- **DC handling:** *accurate* (default for RTL) steps hops by u·fs/2 (50 % overlap) so each hop's DC zone is filled by a neighbour; *fast* steps by u·fs and interpolates ±2 bins around DC.
- **Retune protocol:** send the new centre frequency, note the `seq` of the newest block received, discard every block up to that `seq` plus the settle time, then capture K·N samples.
- **Stitching:** keep each hop's usable window (minus its DC zone in accurate mode) and merge into a global frequency grid, averaging where hops overlap.
- **Baseline flattening** (option, on by default): estimate the hop passband shape as the median across hops for each relative bin, then subtract it in dB.
- **Traces and tools:** live, average-over-sweeps and max-hold traces; threshold line; peak list (local maxima above threshold, minimum spacing, top 50); markers; estimated duration shown before start.
- **Storage:** save sweeps (metadata + float32 levels) to overlay later. CSV export starts with a `#` comment line holding start, stop, RBW and time, followed by `frequency_mhz,level_db` rows (frequency to 6 decimals, level to 1 decimal).
- A sweep pauses the receiver (single tuner) and restores its state afterwards.

## 8. Scanner

- **Channel:** id, name, frequency, mode, bandwidth, squelch (dB or auto), list, lockout, notes.
- **Lists:** create, edit, delete; CSV import/export with columns `name,frequency_hz,mode,bandwidth_hz,squelch_db,lockout`.
- **Memory scan:** channels sorted by frequency and grouped into tuner windows (fs × usable fraction). Moving within a window retunes the NCO only (about 30 ms settle); moving across windows retunes the hardware (settle as in the sweep).
- **Range search:** start, stop, step, mode and bandwidth generate virtual channels; option to save hits to a list.
- **States:** Idle → Tuning → Settling → Dwell (default 80 ms) → Listening (squelch open) → Hang (default 2 s after squelch closes) → next channel. The user can hold (pause) on a channel; an optional maximum listen time forces the scan on.
- **Activity log:** start time, frequency, channel name, duration, peak level; CSV export.
- **Record hits** (option): one audio WAV per activity.

## 9. Recording

- **Audio:** WAV PCM16, 48 kHz, 1 or 2 channels — what you hear, taken before the volume control.
- **IQ:** the source's raw samples (u8 or s16) as a 2-channel WAV; files split at 2 GiB.
- **Crash safety:** WAV headers are rewritten every 2 s and on stop, so a file stays playable if the app dies or the disk fills.
- **Location:** app-specific external storage `Recordings/` (no storage permission needed); sharing via FileProvider; "Export to Downloads" through MediaStore.
- **Names:** audio `SpectraSDR_yyyyMMdd_HHmmssZ_<freq>Hz_<mode>.wav`; IQ as in §5.4.

## 10. Android app

- **Navigation:** bottom bar with Receiver · Sweep · Scanner · Recordings; source picker and Settings in the top bar.
- **Receiver screen:**
  - frequency readout with digit tuning (tap and swipe) and step selection
  - mode chips with bandwidth presets
  - spectrum and waterfall: tap to tune, drag to pan, pinch to zoom, draggable filter edges, averaging, peak hold, palette and contrast
  - squelch (with auto), volume, gain controls per source type, S-meter (dBFS)
  - RDS strip (PS, RT, PI, PTY, stereo indicator), record audio/IQ, bookmark
- **WaterfallView:** GLSurfaceView with OpenGL ES 3.0. An R8 ring texture (width ≤ 2,048 bins × 1,024 rows) receives one row per frame through `glTexSubImage2D`; dB values are normalised to 0–255 with the current range when written, and a fragment shader maps them through a palette lookup texture and applies the scroll offset. Range changes affect new rows. The spectrum trace is drawn in a Compose Canvas overlay.
- **RadioService:** foreground service (`mediaPlayback`) that owns the `Receiver`; notification with play/stop, frequency, mode and RDS name; partial wake lock while running; high-performance Wi-Fi lock for network sources; audio focus handling (pause for calls).
- **Persistence:** Room for channels, lists, servers, saved sweeps and the activity log; DataStore for settings and last state.
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS (runtime on Android 13+). No location or storage permissions.
- **Look:** dark-first Material 3; portrait and landscape layouts; works on tablets.

## 11. Error handling

| Condition | Behaviour |
|-----------|-----------|
| Driver app not installed | Explain and open its Play Store page |
| Driver refuses (no USB permission, unsupported device) | Show the driver's `detailed_exception_message` |
| Dongle unplugged / stream ends | Stop cleanly, show the reason, restart on `SDR_DEVICE_ATTACHED` or on user retry |
| Network source lost | Reconnect with back-off 1, 2, 4, 8, 15 s (up to 2 min), restoring frequency, rate and gain |
| Protocol error (bad header, short struct) | Close, show a readable message, log details |
| DSP overload | Drop oldest blocks; overload badge when > 1 % of blocks dropped over 10 s, with a suggestion to lower the rate or FFT size |
| Audio underrun | Drift-compensating resampler plus silence fill |
| Recording I/O error (disk full) | Stop recording, keep the file valid, notify |

All errors reach the user as plain-language messages, with technical detail in logcat.

## 12. Testing

**Core unit tests** (JUnit 4 API; run in the development workspace with kotlinc and on CI with Gradle):

| Area | Pass criterion |
|------|----------------|
| FFT | Matches a naive DFT for sizes 8–4,096 (relative error < 1e-4) |
| FIR design / decimators | Passband ripple < 0.5 dB, alias rejection ≥ 60 dB |
| NCO, resampler | Frequency accuracy; drift controller converges and holds fill level |
| WFM mono | 1 kHz tone at 75 kHz deviation → SINAD ≥ 40 dB |
| Stereo | Left-only tone → right channel ≤ −25 dB |
| RDS | Synthetic MPX carrying 0A (PS) and 2A (RT) groups, FM-modulated end to end → PI, PS and RT decoded exactly |
| NFM / AM | Tone recovered with SINAD ≥ 30 dB |
| SSB / CW | Opposite sideband rejected ≥ 40 dB; CW tone at the pitch |
| Squelch, scanner | Hysteresis; scripted channel powers exercise stop, resume, hang, lockout and window grouping |
| Sweep | Synthetic tones across hops found within ±1 bin and ±1 dB; DC zone filled in accurate mode |
| rtl_tcp / rsp_tcp | Fake server checks header parsing, the RSP0 struct (including the little-endian field), u8 and s16 samples, and command bytes; `supportedTcpCommands` filtering |
| Recording | WAV round trip; header valid after a simulated crash; file-name parsing |
| Throughput | WFM stereo + RDS chain at 2.4 MS/s runs ≥ 3× real time on the JVM |

**CI (GitHub Actions):** `./gradlew :core:test :app:assembleDebug :app:lintDebug` on every push, debug APK uploaded as an artifact; tags `v*` build a release APK.

**Device checklist (manual):** S24 Ultra + V4 through the driver app — open/close, FM stereo + RDS, NFM, AM airband, SSB on HF (V4 upconverter), sweeps of 88–108 MHz and 470–694 MHz, scanner, recording and playback, screen-off listening, unplug/replug; SDRplay through rsp_tcp in standard and extended mode.

## 13. Delivery

- Public GitHub repository: the user creates an empty repo; Claude attaches it with push access.
- Contents: GPL-3.0-or-later `LICENSE`, README with setup (driver app, rsp_tcp on a Raspberry Pi), CHANGELOG, Gradle wrapper, version catalog, CI workflow.

## 14. Milestones

Each milestone ends in a working, tested state.

1. **Core engine** — sources (rtl_tcp/rsp_tcp client, IQ files), DSP blocks, all demodulators, stereo, RDS, spectrum analyzer, `Receiver`; full unit tests and throughput test pass in the workspace.
2. **Android receiver** — Gradle project and CI, driver hand-off, network sources, `RadioService`, audio sink, receiver screen with spectrum and GL waterfall. First installable APK.
3. **Sweep analyzer** — engine, screen, saved sweeps, CSV export.
4. **Scanner and recording** — channel lists, memory scan and range search, activity log, audio/IQ recording, recordings browser, IQ playback.
5. **Polish** — settings, error states, landscape/tablet layouts, README and release build.

## 15. Phase 2 (after v1 works)

- `:openrsp` JNI source (attach to the OpenRSP driver app, `sdrplay_api_*` calls) once its client-demo source is public.
- Native DSP for 8 MS/s and above; embedded librtlsdr (RTL-SDR Blog fork) for faster sweeps.
- CTCSS/DCS decoding, IQ imbalance correction, frequency-database import, home-screen widget.

## 16. Risks

| Risk | Mitigation |
|------|------------|
| Kotlin DSP too slow at high sample rates | Multistage decimation, preallocated arrays, throughput test gate, rate caps; native DSP in phase 2 |
| Driver/network buffering leaves stale samples after a retune | Block `seq` tagging plus a configurable settle time |
| Android code cannot run in the development workspace | CI builds, a thin Android layer, the device checklist |
| rsp_tcp throughput over Wi-Fi | Default 2 MS/s s16; required bandwidth shown; u8 fallback |
| RDS robustness on weak signals | Costas loop, timing recovery and block-level error checks, tested with added noise |
