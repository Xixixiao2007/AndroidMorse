# NOTICE — copyright, provenance and third-party terms

This app is a **port of Morse Runner Community Edition (MRCE) 1.85.4**, a Windows Delphi/VCL
program. Nothing in `upstream/` is modified or shipped: that clone is git-ignored and used
read-only as the behavioural reference.

## Copyright

| Part | Holder |
|---|---|
| Morse Runner (original, 2004–2016) | © Alex Shovkoplyas, **VE3NEA** |
| Morse Runner Community Edition (2022–2026) | © Morse Runner Community Edition Contributors |
| This Android port — the whole repository | © 2026 **Charles Xiao (BH4GZK)** |

## Upstream project

- Morse Runner Community Edition — <https://github.com/w7sst/MorseRunner>
- Reference commit: `2dd6f5c` (version 1.85.4)
- Licence: **Mozilla Public License 2.0**, with the "Incompatible With Secondary Licenses"
  notice — see [LICENSE.md](LICENSE.md), which is that licence verbatim. This repository is a
  derivative work and keeps the same terms.

## Corresponding source (MPL-2.0 §3.2)

The published APK contains an executable form of MPL-covered software: `libmrcore.so`, cross-built
from the Pascal contest engine. **Its source is committed in this repository** —
[`core-pascal/`](core-pascal/) holds every `.pas`/`.lpr` file that goes into that binary, and
[`tools/build-native.sh`](tools/build-native.sh) records the exact compiler invocation. Anyone who receives the APK can therefore obtain, rebuild and modify that
engine, as the licence requires.

## Derived files

### Pascal engine (`core-pascal/`)

The engine is MRCE's own Pascal source, modified for Android: the contest core
(`Contest.pas`, `Log.pas`, `Ini.pas`, the twelve contest units, `DxStn.pas`, `DxOper.pas`,
`Station.pas`, `MyStn.pas`, `Main.pas`, `Util/*`, `VCL/*`) is a **file-by-file derivative** of
upstream and stays under MPL-2.0. Every change is marked in place with a comment naming the
upstream behaviour it replaces.

The `headless/` directory and `mrcore.lpr` are new here: they replace the VCL form with the C ABI
the Android UI speaks. They are MPL-2.0 as well, so the whole tree has one licence.

### Kotlin

Kotlin written for the first milestone is original; where behaviour was transcribed, the source
file and line are cited in the code's KDoc.

| This repository | Upstream source |
|---|---|
| `core-audio/MorseTiming.kt`, `MorseEncoder.kt`, `CwSynthesizer.kt` | `VCL/MorseKey.pas`, `VCL/FarnsKeyer.pas`, `VCL/MorseTbl.pas` |
| `core-data/MasterDta.kt` | `CallLst.pas` |
| `core-data/DxccList.kt` | `DXCC.pas` |
| `core-data/CallPrefix.kt` | `Util/CallsignUtils.pas` |
| `core-data/ArrlSections.kt` | `Util/ArrlSections.pas` |
| `core-qso/*` | `Log.pas`, `Contest.pas`, `ExchFields.pas` |
| `app/.../ui/*` | `Main.dfm` layout and the 2×4 message strip |

Golden test data in `core-pascal/Test/` was transcribed from upstream's `Test/*.pas`.

## Third-party components

| Component | Where | Licence |
|---|---|---|
| **TPerlRegEx** (regular-expression engine used by the exchange lexers) | `core-pascal/PerlRegEx/` | **Mozilla Public License 1.1** (© Jan Goyvaerts) — the file carries its own notice; it is used unmodified and its source ships here |
| **Free Pascal RTL / SysUtils / Classes** (linked statically into `libmrcore.so`) | build toolchain, not vendored | **LGPL-2.1 with the Free Pascal static-linking exception**; the link exception covers this binary, and the toolchain source is at <https://www.freepascal.org/> |
| **Android NDK libc++ / libc** (linked into the JNI shim) | build toolchain | Apache-2.0 / BSD-style, as shipped with the NDK |
| **Kotlin standard library, AndroidX, Jetpack Compose, Material 3** | Gradle dependencies | Apache-2.0 |
| **App icon** | `app/src/main/res/mipmap-*` | Morse Runner artwork (MPL-2.0, from upstream `MorseRunner.ico`) composed with the Android robot, which is a trademark of Google used under the [Android brand guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines) |

## Data files (NOT MPL-2.0)

These are redistributed **unmodified** from upstream (in `app/src/main/assets/`), and they carry
their own terms:

- **`dxcc.list`** — upstream's own header reads: *"Used with permission: DXCC Country List,
  copyright ARRL."* It is ARRL material, not MPL-2.0, reproduced here because upstream
  distributes it with its app. **Recorded decision (project owner, 2026-10-19): no further
  action** — the list is effectively unenforced in practice and this project will not gate on
  it. The provenance stays documented above; that documentation is the whole of the mitigation.
- **`MASTER.DTA`** — the callsign pool, binary, unmodified from upstream. Upstream carries no
  visible provenance for it.
- **Contest call-history files** (`CQWWCW.txt`, `SSCW.txt`, `IARU_HF.txt`, `K1USNSST.txt`,
  `NAQPCW.txt`, `FDGOTA.txt`, `JARL_*.TXT`, `CWOPS.LIST`, `ARRLDXCW_USDX.txt`, …) — per-contest,
  unmodified from upstream; several were assembled from public contest logs and N1MM call history
  files, as documented in upstream `IaruHf.pas:76-83`.
