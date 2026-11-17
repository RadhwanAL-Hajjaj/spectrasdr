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
installs over the previous one; it cannot update a build signed with another key.

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
