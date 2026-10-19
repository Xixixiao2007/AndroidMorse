# ADR-0001 — Native Android app in Kotlin + Compose

- Status: accepted (human chose "B" on 2026-10-19)
- Deciders: human (BH4GZK), agent

## Context
The reference program (Morse Runner Community Edition 1.85.4) is Delphi/VCL, Windows-only.
No Delphi, FPC/Lazarus, Flutter or Android SDK toolchain was present initially except a
full SDK found at `C:\Program Files (x86)\Android\android-sdk` and JDK 17.

## Decision
Rebuild as a **native Android application in Kotlin**, UI in **Jetpack Compose**,
`minSdk 24`, `targetSdk 35`, `buildToolsVersion 35.0.0`.
Build locally with Gradle 8.9 + AGP 8.7.3; verify by installing on the user's WSA
(`adb connect 127.0.0.1:58526`, Android 13 / API 33 / x86_64).

## Consequences
+ A real installable APK; best audio latency and phone-integration.
+ Verification is possible without a physical phone (WSA).
− The Pascal source cannot be compiled; every module is a rewrite, validated behaviourally
  against the upstream unit tests in `upstream/MorseRunnerCE/Test/`.
− Builds depend on Aliyun Maven mirrors (see `.scratch/progress.md` §6b); `dl.google.com`
  is unreachable from this machine.

## Alternatives rejected
- PWA / Capacitor: rejected by the human (wanted a native APK).
- Free Pascal cross-compile: VCL is not portable; no FPC installed.
