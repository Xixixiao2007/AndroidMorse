# Morse Runner for Android

A native Android port of **Morse Runner Community Edition 1.85.4** — the CW contest simulator
originally written in Delphi/VCL for Windows — rebuilt for a phone: one hand, portrait, 2×4
canned-message buttons instead of a function-key strip.

This is a **rewrite**, not a build: the original cannot be compiled here (no Delphi, no
FPC/Lazarus). Behaviour is transcribed from the Pascal with `file:line` citations in the code,
and the upstream clone in `upstream/` is used read-only as the reference.

- Reference: [w7sst/MorseRunner](https://github.com/w7sst/MorseRunner) @ `2dd6f5c` (1.85.4)
- Licence: MPL-2.0 — see [LICENSE.md](LICENSE.md); provenance and third-party data (which is
  **not** MPL) in [NOTICE.md](NOTICE.md)
- Planning docs: [CONTEXT.md](CONTEXT.md) (facts, decisions, glossary), [docs/adr/](docs/adr/),
  [docs/spec/mr-android-spec.md](docs/spec/mr-android-spec.md)

## Status

Milestone A is "playable CQ WW". Ticket order and current state live in
`.scratch/progress.md` (kept current by every session) and spec §11.

| Ticket | Scope | State |
|---|---|---|
| T1 | project skeleton, module split, WSA deploy script | done |
| T2 | bounded expression language | done — 78 tests |
| T3 | schema v1, three contest configs, loader with load-time validation | done — 61 tests |
| T4 | CW audio: timing, envelope, single-signal playback | done — 20 tests, verified on WSA |
| T5 | station roster, pile-up, lock-free block mixer | in progress |
| T6 | QSO state machine, log, scoring | groundwork only (`QsoLog`) |
| T7 | Compose UI: 2×4 grid, entry, autocomplete | not started |

## Modules

| Module | What lives there | Android deps |
|---|---|---|
| `app` | Compose shell, `AudioTrack` sink, persistence | yes |
| `core-expr` | the bounded expression language (lexer → parser → evaluator) | no |
| `core-contest` | config schema, JSON reader, loader, the 12 contest ids | no |
| `core-data` | `MASTER.DTA`, `DXCC.LIST`, callsign/prefix, ARRL sections | no |
| `core-qso` | QSO state vocabulary, log, scoring aggregation | no |
| `core-audio` | Morse timing, text → elements, CW synthesis | no |

`core-*` are plain Kotlin/JVM, so they are covered by fast unit tests with no emulator.

## Build and test

The `JAVA_HOME` / `ANDROID_HOME` values below are what this machine actually has; they are not
set system-wide.

```bash
cd /c/DSHproj/AndroidMorse
export JAVA_HOME='C:\Program Files (x86)\Android\openjdk\jdk-17.0.14'
export ANDROID_HOME='C:\Program Files (x86)\Android\android-sdk'

# one module while iterating
cmd.exe //c "C:\DSHproj\AndroidMorse\tools\gradle-8.9\bin\gradle.bat --no-daemon -Pkotlin.incremental=false :core-audio:test"

# everything, at ticket closure
cmd.exe //c "C:\DSHproj\AndroidMorse\tools\gradle-8.9\bin\gradle.bat --no-daemon -Pkotlin.incremental=false assembleDebug test"

# then prove the numbers, including the skip count (a skipped test is not evidence)
python tools/verify-tests.py --strict
```

`-Pkotlin.incremental=false` is there because two Gradle builds in this tree corrupt the Kotlin
incremental cache; see `AGENTS.md` for the other workarounds.

## Run on Windows Subsystem for Android

```bash
# start WSA if it is suspended, then connect
cmd.exe //c "start shell:AppsFolder\MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe!App"
"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe" connect 127.0.0.1:58526

# build, install, launch
bash tools/deploy-wsa.sh
```

## Data assets

`MASTER.DTA`, `DXCC.LIST` and the per-contest call-history files are redistributed unmodified
from upstream. They carry their own terms — the DXCC list is ARRL material reproduced "with
permission", not MPL-2.0. Read [NOTICE.md](NOTICE.md) before publishing anything.
