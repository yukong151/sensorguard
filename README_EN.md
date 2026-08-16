# SensorGuard — Sensor Privacy Guardian

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.0.0-brightgreen.svg)](CHANGELOG.md)
[![Rust](https://img.shields.io/badge/Rust-tests%2095%2F95-orange.svg)](core-rust)

[简体中文](README.md) | **English**

A lightweight **Android sensor privacy monitoring** tool. Monitors app access to microphones, cameras, and IMU sensors in real time, detects anomalous sampling patterns, and guides users to system privacy settings. **All processing on-device, zero network access.** Open-source community build, aimed at security researchers; fully local, no backend.

## Features

- **Real-time dashboard**: system health, encrypted-storage status, today's event statistics
- **Event timeline**: chronological record of app access to mic, camera, location, and IMU, with exact app attribution / package name (core community feature)
- **Anomaly detection engine**: 20 hard rules + 3 statistical tests (KS test, burst entropy, diurnal KL divergence) + Lomb-Scargle periodicity + Isolation Forest (v1.1)
- **Exact attribution**: via Shizuku, reads `dumpsys sensorservice` / `dumpsys media.camera` to attribute sensor/camera access to the exact app package
- **One-tap intervention**: deep links to system privacy settings for mic/camera anomalies
- **Encrypted logs**: AES-256-GCM storage, key protected by Android Keystore, one-tap wipe
- **Fully offline**: main process has zero network permission; all inference runs in on-device Rust

## Architecture

```
┌─────────────────────────────────────────────┐
│ Android (Kotlin)                            │
│  Mic/Camera/Location probes + Shizuku        │
│  Timeline / Detail UI / Encrypted (Room)     │
└───────────────┬─────────────────────────────┘
                │ JNI (flatbuffers)
┌───────────────▼─────────────────────────────┐
│ Rust Core (core-rust)                       │
│  24h sliding window · KS/BurstEntropy/KL/Lomb│
│  SPSC event ring buffer · Verdict            │
└─────────────────────────────────────────────┘
```

- **core-rust/**: Rust core (anomaly detection, ring buffer, rule engine, Isolation Forest)
- **app/**: Android layer (Kotlin: probes, UI, encrypted storage)
- **schemas/**: flatbuffers event/alert schemas

## Build

```bash
# Rust core (arm64)
cd core-rust && cargo build --release --target aarch64-linux-android

# Android APK (single community build variant, shows app attribution)
./gradlew :app:assembleInternalDebug   # debug build
./gradlew :app:assembleInternalRelease # release build (community)
```

Requirements: Android SDK, Android Studio JBR (Java 17), Rust nightly + android target.

### Direct install

Community release APK (properly signed) is available from [GitHub Releases](https://github.com/yukong151/sensorguard/releases). Sideloading supported (enable "Install unknown apps" on device).

Signing & key management: see [docs/signing.md](docs/signing.md).

## Exact Attribution via Shizuku

Attributing sensor/camera events to exact app packages requires Shizuku. Activation and authorization steps: [docs/SHIZUKU_WIRELESS_SETUP.md](docs/SHIZUKU_WIRELESS_SETUP.md) (wireless debugging method; reactivation needed after every reboot).

Without Shizuku, the app silently degrades: sensor/camera events show "unknown source"; all other features keep working.

## Documentation

The full design document (threat model, algorithms, performance budget, compliance) has been archived during the v1.0 code delivery phase and is no longer published with the source. The `docs/` directory retains:
- `docs/store_listing.md`: store listing checklist
- `docs/SHIZUKU_WIRELESS_SETUP.md`: Shizuku wireless activation steps
- `docs/PIA.md`: privacy impact assessment
- `docs/sbom.txt`: dependency SBOM (CycloneDX 1.5)
- `docs/maintenance.md`: maintenance & governance, v1.1 roadmap

For contribution, see [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md); version history is in [CHANGELOG.md](CHANGELOG.md).

## License & Acknowledgments

- **License**: [Apache License 2.0](LICENSE)
- **Algorithm originality & attribution notice**: [NOTICE](NOTICE) ([中文版](NOTICE_CN.md))

The core algorithms (KS test, Lomb-Scargle, Isolation Forest, KL/entropy) are **original implementations** based on published mathematical formulas, with no third-party statistical library linked. Design inspiration comes from academic work such as Spearphone (NDSS'20), AccelEve (SenSys'19), EarSpy (2022), and from the engineering approaches of open-source projects such as Access Dots, PilferShush Jammer, and TrackerControl (no code reused).

Parts of the design documentation were drafted with LLM assistance; the implementation code was authored and reviewed by humans.

## Privacy & Disclaimer

SensorGuard alerts/observations are **informational only and do not constitute an accusation** of any app. All data is processed locally; no personal data is collected and no network access is used.
