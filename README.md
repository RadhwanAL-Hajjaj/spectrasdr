# SpectraSDR

Open-source Android SDR receiver: spectrum and waterfall, wideband sweep analyzer, and listening
(WFM stereo + RDS, NFM, AM, SSB, CW) for RTL-SDR and SDRplay receivers.

Status: the core engine is complete and tested (sources, demodulators, stereo, RDS, spectrum,
receiver, simulator). The Android app is next.

## Layout

- `core/` — pure-Kotlin signal engine with no Android dependencies
- `docs/superpowers/specs/` — design spec
- `docs/superpowers/plans/` — implementation plans
- `tools/test-core.sh` — runs the core unit tests with kotlinc and JUnit 4, without Gradle

## License

GPL-3.0-or-later. See `LICENSE`.
