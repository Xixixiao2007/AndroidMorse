# NOTICE — provenance and third-party data

This app is a **port/rewrite of Morse Runner Community Edition (MRCE) 1.85.4**, a Windows
Delphi/VCL program. Nothing in `upstream/` is modified or shipped: that clone is git-ignored and
used read-only, as the behavioural reference.

## Upstream project

- Morse Runner Community Edition — <https://github.com/w7sst/MorseRunner>
- Reference commit: `2dd6f5c` (version 1.85.4, May 2026)
- License: **Mozilla Public License 2.0**, with the "Incompatible With Secondary Licenses"
  notice — see [LICENSE.md](LICENSE.md), copied verbatim from upstream. This repository is a
  derivative work and keeps the same terms.
- Original Morse Runner by Alex Shovkoplyas, VE3NEA (2004–2016); Community Edition maintained
  since 2022.

## Behaviour transcribed from upstream (MPL-2.0 covered)

The Kotlin in this repository is written from scratch; where behaviour is derived, the source
file and line are cited in the code's KDoc. The main ones:

| This repository | Upstream source |
|---|---|
| `core-audio/MorseTiming.kt`, `MorseEncoder.kt`, `CwSynthesizer.kt` | `VCL/MorseKey.pas`, `VCL/FarnsKeyer.pas`, `VCL/MorseTbl.pas` |
| `core-data/MasterDta.kt` | `CallLst.pas` |
| `core-data/DxccList.kt` | `DXCC.pas` |
| `core-data/CallPrefix.kt` | `Util/CallsignUtils.pas` |
| `core-data/ArrlSections.kt` | `Util/ArrlSections.pas` |
| `core-*` contest rules | `Contest.pas`, `CqWW.pas`, `ArrlSS.pas`, `IaruHf.pas`, `ExchFields.pas` |
| `core-qso/QsoLog.kt` | `Log.pas` |

Golden test data was transcribed from `Test/*.pas` (callsign utilities, ARRL SS exchange
parser, lexers).

## Data files (NOT MPL-2.0)

These are redistributed **unmodified** from upstream, and they carry their own terms:

- **`DXCC.LIST`** — upstream's own header reads: *"Used with permission: DXCC Country List,
  copyright ARRL."* The ARRL DXCC list is ARRL material, not MPL-2.0. It is reproduced here
  because upstream distributes it with its app; **redistributing this app publicly should be
  confirmed with ARRL or replaced with an equivalent source that permits redistribution.**
  This is an open risk, recorded rather than resolved.
- **`MASTER.DTA`** — the callsign pool, binary, unmodified from upstream. Upstream carries no
  visible provenance for it.
- **Contest call-history files** (`CQWWCW.TXT`, `SSCW.TXT`, `IARU_HF.txt`, …) — per-contest,
  unmodified from upstream; several were assembled from public contest logs and N1MM call
  history files, as documented in `IaruHf.pas:76-83`.

## This repository

Original Kotlin code: © the contributors of this repository, under MPL-2.0 unless a file says
otherwise.
