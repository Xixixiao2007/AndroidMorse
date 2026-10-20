# ADR-0020 — Original Pascal for the logic, Kotlin/Compose for the screen

- **Status:** accepted (human decision, 2026-10-20, answer "C")
- **Supersedes:** ADR-0019's assumption that the whole application could be recompiled to Android.
  The toolchain findings of ADR-0019 stand and are reused; only the *scope* changes.
- **Deciders:** the project owner.

## Context

ADR-0019 chose to recompile the original. The toolchain half of that worked and is documented in
`.scratch/progress.md` §27–31: FPC 3.2.2 + Lazarus 4.0 install without admin, the Android
cross-compilers exist, NDK r16b is required (r21 dropped MIPS), and LCL cross-builds for
`aarch64-android` after two genuine LCL bugs are patched.

The runtime half fails, and the failure is not ours:

- The built APK installs and is correctly signed, then takes a **SIGSEGV inside `JNI_OnLoad`**
  (`addr2line` of `#00 pc 0x1275a4` resolves to that function), before its own first log line.
- Two LCL defects were found and patched on the way: `customdrawnwinapi.inc` uses `lFont.ftFont.*`
  (a Qt-only member) in five places, and `android/jni.pas` declares only three of the **four**
  `reserved` slots the JNI spec puts before `DestroyJavaVM`, so `vm^.GetEnv` reads a shifted pointer.
  Patching both and rebuilding still leaves the crash.
- LCL's own comments admit the area is fragile ("GetEnv crashes ... unless you build with
  `-CpARMV6`"), i.e. it was written for 32-bit ARM. The test device (`Redmi 2602BRT18C`,
  Android 16/API 36) reports **`abilist32` empty** — arm64 only — so that route is closed.

LCL-CustomDrawn is therefore not a viable UI on current Android devices. Its UI layer is also the
part the human already agreed to change ("只做一点UI位置调整" is impossible without a widget set,
and a phone UI has to be re-laid out regardless).

## Decision

Split the application along the seam the code actually has:

- **Contest logic stays original Pascal.** Compile it — headless — into a `.so` for
  `aarch64-android` with FPC, exporting a small stable ABI. This is the 20 255 lines that carry the
  domain: the twelve contests, exchange generation, callsign/DXCC handling, QSO validation and
  scoring.
- **The screen is Kotlin/Compose.** The existing Kotlin implementation already provides the UI
  scaffolding, the audio engine and the data tables; it drives the Pascal core through JNI.
- **Audio** is Kotlin (`AudioTrack`), because the original's `SndOut.pas` is Windows `waveOut`.

## Consequences

**Good.** The valuable part of the original is reused verbatim, and the part that cannot exist on
Android (VCL widgets, `waveOut`) is excluded instead of ported. The Kotlin tree already has 648
tests and 12 contests validating expected behaviour, so the two sides can be cross-checked against
each other — an unusually strong safety net for a port.

**Costs, stated honestly.**

1. The Pascal logic is **not** dependency-free: `Contest.pas` pulls in `Mixers`, `VolumCtl`,
   `MovAvg`, `SndTypes` and the station/audio types. A headless build must cut those, either by
   stubbing them or by excluding the units that need them. This is the real work of route C and it
   is not yet measured.
2. A JNI boundary must be defined and kept small. Every call crossing it needs simple types
   (`Integer`, `Double`, `PChar`/byte arrays) — no objects, no strings-by-value, no callbacks
   unless deliberately designed.
3. Two build systems must be run to produce one APK (Gradle for the Kotlin side, FPC for the `.so`),
   and the `.so` must be copied into the APK's `jniLibs`.
4. The Pascal side loses the original's `Main.pas` orchestration; the Kotlin side must reproduce
   the QSO flow that `Main.pas` encoded.

## What would overturn this ADR

Evidence that the Pascal logic cannot be compiled headless at all (the audio/GUI coupling proves
inseparable), or that the JNI boundary cannot carry the state the logic needs.

## Update — first headless slice (2026-10-20, measured)

The seam this ADR bets on now exists end to end, and it works:

- The slice-1 glue (`.scratch/mrcore-verify/pascal/`) builds a `libmrcore.so` (`ELF64`,
  `AArch64`) from the original `Util/CallsignUtils.pas`, exporting `mr_version`, `mr_prefix_of`
  and `mr_wpx_prefix` (names per `docs/abi.md`).
- A throwaway verifier APK (`.scratch/mrcore-verify/`) loads it through a 40-line C JNI shim and
  checks 36 vectors lifted from the original's own test table
  (`Test/CallsignUtilsTest.pas`) on the test device: **pass=36 fail=0**.

So "Kotlin calls the Pascal core" is proven, and the boundary is cheap (plain C types, caller
buffers, no allocation). Note that the repo keeps exactly **one** ABI truth — `docs/abi.md` plus
`jni/` and `app/src/main/jniLibs/` — which is why the slice-1 wrapper stayed in scratch rather
than becoming a second `pascal/` tree beside the full core's JNI bridge.

**New risk, measured and not yet solved.** Loading an FPC-built `.so` on this device
(Redmi 2602BRT18C, Android 16 / API 36) **crashes inside the FPC runtime's startup** roughly half
to four-fifths of launches: SIGSEGV during `dlopen`, inside `System`'s
`CommonMainAndroid`, before any library code runs. A control library with no business units
crashes at the same rate, so it is the toolchain, not the ported code. A crashed launch leaves no
state; relaunching usually brings it up.

That does not overturn the ADR — the seam is real — but route C's viability now hinges on one
question, which is the next ticket: **can the FPC Android RTL be made to load reliably?**
(candidates: a newer cross-RTL; reading `rtl/android/sysandroid.pp`; loading via `JNI_OnLoad`).
If the answer is no, this ADR's "logic in Pascal" half is in the same position the LCL UI was in
before ADR-0020: correct in principle, unusable on current hardware.
