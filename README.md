# Morse Runner for Android

A native Android port of **Morse Runner Community Edition 1.85.4** — the CW contest simulator
originally written in Delphi/VCL for Windows — rebuilt for a phone: one hand, portrait, a 2×4
canned-message strip instead of a function-key row, and the same pile-up, exchange and scoring
rules underneath.

It is not a from-scratch reimplementation of the game. The **contest engine is the original
Pascal**, kept in [`core-pascal/`](core-pascal/) and cross-compiled to an `arm64-v8a` shared
library with Free Pascal; a small C ABI ([`jni/mrcore_jni.c`](jni/mrcore_jni.c)) joins it to a
Kotlin + Jetpack Compose UI. The upstream clone in `upstream/` is used read-only as the
behavioural reference, and each behavioural change carries a comment naming the upstream code it
replaces.

- Author: **Charles Xiao (BH4GZK)** <https://github.com/Xixixiao2007>
- Reference: [w7sst/MorseRunner](https://github.com/w7sst/MorseRunner) @ `2dd6f5c` (1.85.4)
- Licence: **MPL-2.0** — see [LICENSE.md](LICENSE.md). The corresponding source of every
  MPL-covered file, including the Pascal engine that ends up in the APK, is in this repository
  (MPL-2.0 §3.2). Third-party components and bundled data that are **not** MPL are listed in
  [NOTICE.md](NOTICE.md).
- Planning artefacts: [CONTEXT.md](CONTEXT.md), [docs/adr/](docs/adr/), [docs/spec/](docs/spec/)

## What it does today

All twelve contests of MRCE 1.85.4 run end to end: CQ WPX, CWops CWT, ARRL Field Day, NCJ NAQP,
HST, CQ WW, ARRL DX, K1USN SST, JARL ALL JA, JARL ACAG, IARU HF and ARRL Sweepstakes. Each was
exercised on a phone (ZTE 7534N, Android 13) through one complete QSO and checked for a saved log
row, points and *verified* points: every contest scores and verifies, including Sweepstakes'
`<nr> <precedence> <check> <section>` exchange and HST's speed-based scoring.

CW goes out with the short codes the contest expects (`5NN`, and `N`/`T`/`A` for the exchange
digits), while every text field and the log stay in decimal — the abbreviation is applied to the
transmitted audio only. Enter sends the DX call and TU before it logs the QSO, as the desktop does.

## Layout

| Path | What lives there |
|---|---|
| [`core-pascal/`](core-pascal/) | the contest engine: MRCE's Pascal, adapted, plus `headless/` and `mrcore.lpr` (the C ABI). MPL-2.0 |
| [`jni/`](jni/) | the C shim between Kotlin and the engine — plain functions, no C++ |
| `app/` | Compose UI, `AudioTrack` sink, assets, the engine's Kotlin facade |
| `core-audio/`, `core-contest/`, `core-data/`, `core-expr/`, `core-morse/`, `core-qso/` | Kotlin modules from the first milestone: Morse timing and synthesis, contest metadata, DXCC/callsign data, the bounded expression language, QSO vocabulary |
| [`tools/`](tools/) | `build-native.sh` (cross-build), `verify-tests.py` (the evidence gate) |

`core-*` are plain Kotlin/JVM, so their unit tests run without a device.

## Build

Prerequisites: JDK 17, Android SDK 35, the Android NDK, Free Pascal with an `aarch64` cross
compiler, and Gradle 8.9.

```bash
# native: engine + JNI shim -> app/src/main/jniLibs/arm64-v8a/
FPC=/path/to/ppcrossa64 ANDROID_NDK_HOME=/path/to/ndk bash tools/build-native.sh

# Kotlin + APK
gradle --no-daemon -Pkotlin.incremental=false :app:assembleRelease
```

Release builds are signed from a keystore outside the repository: point `MR_SIGNING_PROPERTIES`
at a properties file holding `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without
it the build still succeeds but produces an unsigned APK.

```bash
# the evidence gate: every suite, skip count included (a skipped test is not evidence)
python tools/verify-tests.py --strict
```

## Data assets

`MASTER.DTA`, `dxcc.list` and the per-contest call-history files are redistributed unmodified
from upstream in `app/src/main/assets/`. They carry their own terms — the DXCC list is ARRL
material reproduced "with permission", not MPL-2.0. Read [NOTICE.md](NOTICE.md) before
redistributing anything.
