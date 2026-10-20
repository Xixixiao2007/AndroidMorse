# ADR-0019 — Recompile the original instead of rewriting it

- **Status:** accepted (human decision, 2026-10-20)
- **Supersedes:** the route assumption of ADR-0001 (a Kotlin rewrite); the Kotlin tree itself is
  **not deleted** — it stays on disk and in git as a working fallback (648 tests, 12 contests).
- **Deciders:** the project owner, verbatim: *"我要求一比一复制最好是拿到源码直接重新编译，只做一点UI位置调整，
  如果缺少依赖就自己安装一下"*, then, when offered *Windows-first* vs *straight to Android*, **B**
  (straight to Android).

## Context

ADR-0001 assumed the original could not be compiled here — no Delphi, no FPC/Lazarus — and that a
rewrite was the only route. **That assumption was wrong**, and the correction was measured on
2026-10-20:

- FPC 3.2.2 and Lazarus 4.0 install **without administrator rights** (Inno installers with
  `/SILENT /DIR=`); this box is not elevated, so `choco`/`winget` were dead ends.
- The original **does** compile under FPC once four classes of Delphi-only constructs are handled,
  and the whole surface turned out to be small:

  | Delphi construct | Count in tree | Fix |
  |---|---|---|
  | Anonymous method (`reference to procedure`) | **1** declaration, 1 call site | plain procedure type |
  | Out-of-range enum constant (`TEnum(-1)`) | **3** | untyped constant / runtime init |
  | Delphi XE unit names (`System.*`, `Vcl.*`) | **25** references | map to `Classes`, `SysUtils`, `ComCtrls`, … |
  | Duplicate units after that mapping | a few | de-duplicate `uses` (comments contain commas) |

- The UI surface is **two forms** (`Main`, `ScoreDlg`), so "only adjust UI positions" is realistic.
- The original ships its own tests (`Test/UnitTests.dpr`, 7 units) — a ready-made behaviour check.
- The patcher is reproducible: `.scratch/toolchain/patches/apply.py` rebuilds the tree from
  `upstream/MorseRunnerCE` and applies every fix; the compile command is recorded in
  `.scratch/progress.md` §27.

## Decision

Compile the **original Pascal source** for Android rather than continue the Kotlin rewrite. Only the
UI positions get adjusted for a phone.

## Consequences

**Good.** The contest logic, scoring, DXCC handling, timing and all twelve contests are the original
code, not a transcription — fidelity stops being an argument. Effort concentrates on the platform
boundary instead of on re-deriving behaviour.

**Costs and unknowns, stated honestly.**

1. The FPC Android cross-installer (`fpc-3.2.2.i386-win32.cross.android.exe`) requires an **NDK**, which
   is not installed; installing it is the first blocking dependency (≈1 GB).
2. **LCL for Android must be cross-built.** The Windows installer ships only
   `lcl/units/x86_64-win64`. LCL's Android widgetset (CustomDrawn) is the least-maintained part of
   LCL, and this app is a dense desktop UI; "only move the controls" may not survive contact with it.
   This is the single largest risk in this ADR and it is *not* yet disproved.
3. `SndOut.pas` drives Windows `waveOut*` (8 call sites). Android needs a different sink — the one
   genuinely non-mechanical piece of the port.
4. The Kotlin implementation stops receiving work. It remains buildable and tested; if the Android
   recompile proves infeasible, the fallback is one `git checkout` away.

## What would overturn this ADR

Evidence that LCL-Android cannot host two VCL forms, or that FPC's Android target cannot link this
app. Both are to be tested before more of the port is spent.
