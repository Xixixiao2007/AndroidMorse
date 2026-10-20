# The Kotlin ⇄ Pascal boundary

Route C (ADR-0020) splits the port at one seam: the original Pascal keeps the contest logic, Kotlin
draws the screen. This file is the contract across that seam. It is deliberately small.

## Rules that keep the seam cheap

1. **Plain C types only** — `Integer`, `Double`, `PChar`. No Pascal strings, no objects, no
   callbacks. Anything richer is encoded as a byte buffer whose layout both sides own.
2. **No allocation across the boundary.** The caller passes a buffer and its capacity; the callee
   returns the number of bytes it wanted, so a short buffer is a resize request, not an error.
3. **Idempotent reads, explicit ticks.** Query functions take no state; the engine advances only
   through named calls.
4. **One opaque handle.** Engine state lives behind an `Int64` handle the Kotlin side never
   inspects. `mr_init` returns it, `mr_destroy` releases it.

## Functions

```pascal
{ --- version / identity ------------------------------------------------- }
function mr_version(): PChar; cdecl;                       { e.g. '0.1' }
function mr_contest_count(): Integer; cdecl;
function mr_contest_name(idx: Integer; buf: PChar; cap: Integer): Integer; cdecl;
  { returns the byte length the name needs; <cap means it was fully written }

{ --- static helpers (pure, no engine) ----------------------------------- }
function mr_prefix_of(call: PChar; buf: PChar; cap: Integer): Integer; cdecl;
  { the DXCC-mode prefix, the same rule the original's ExtractPrefix uses }

{ --- engine ------------------------------------------------------------- }
function mr_init(contestIdx, seed: Integer; myCall: PChar): Int64; cdecl;
  { <= 0 means failure; 0 is never a valid handle }
procedure mr_destroy(handle: Int64); cdecl;

function mr_station_count(handle: Int64): Integer; cdecl;
function mr_station_call(handle: Int64; idx: Integer; buf: PChar; cap: Integer): Integer; cdecl;
function mr_station_exchange(handle: Int64; idx: Integer; buf: PChar; cap: Integer): Integer; cdecl;
  { the pair the operator must copy, e.g. '599 14' — the exact text the
    original's exchange generator produces for that station }

function mr_score(handle: Int64): Integer; cdecl;
function mr_qso_count(handle: Int64): Integer; cdecl;
```

## Why these first

`mr_version`, `mr_contest_count`, `mr_contest_name` and `mr_prefix_of` are **pure**: they prove the
library loads and runs on the device without dragging in engine state. They are the present slice.

The engine functions come after the stub work lands, because they need `Contest`/`Log` to compile
headless — and that is exactly what the current slice is measuring.

## Error convention

- Integer-returning functions return `-1` for "unavailable" and `>= 0` otherwise.
- Buffer functions return the required capacity when it exceeds `cap`; the caller then retries with
  a larger buffer. A negative return is a failure.
- The library never throws across the boundary: FPC exceptions are caught inside the exported
  wrapper and converted to `-1`.

## Kotlin side

```kotlin
private external fun mr_version(): String
```
with `System.loadLibrary("mrcore")`. The Gradle build copies the FPC output into
`app/src/main/jniLibs/arm64-v8a/libmrcore.so`; nothing else about the APK changes.

## The run API (implemented; verified on device)

The original desktop app advanced the simulation from its sound-card callback: every time the
device wanted a block it called `TContest.GetAudio`, which filters, modulates, runs the AGC, ticks
the operator and every station, and appends finished QSOs to the log. That call *is* the game loop,
so the core exposes it directly and the phone's UI drives it.

| function | meaning |
|---|---|
| `mr_run_start(idx, mycall) -> void*` | create the contest of kind `idx`, set the operator call, reset the log. `0` on failure. |
| `mr_block(handle, float* out, cap) -> int` | one audio block (mono, `mr_contest_rate()` Hz); returns the sample count. The float scale is the AGC/mixer output (about 16-bit units, peak ~3000), so divide by 32768 before playback. **One thread only.** |
| `mr_send(handle, msg)` | send a canned message by its `TStationMessage` ordinal (F1 CQ = 1, F2 exchange = 2, F3 TU = 3, F4 my call = 4, F5 his call = 5, F6 B4 = 6, F7 ? = 7, F8 NIL = 8). |
| `mr_set_my_call` / `mr_set_his_call` | the operator's own call / the DX call just copied. |
| `mr_set_data_dir(path)` | where `DXCC.LIST` and the call histories live (the APK ships them in `assets/data`, the UI copies them to `filesDir`). Without it the contest still runs; multipliers fall back to zero instead of crashing. |
| `mr_contest_rate` / `mr_block_size` | 11025 Hz / 512 samples. |
| `mr_log_count` / `mr_log_cell(row, col, buf, cap)` | the QSO log; columns 0 UTC, 1 Call, 2 Recv, 3 Sent, 4 Pref, 5 Chk, 6 Wpm. |
| `mr_score(which)` | 0 raw points, 1 verified points, 2 raw multipliers, 3 verified multipliers. |

Evidence (2026-10-20, ZTE 7534N): 200 blocks → `samples=99845 peak=3150.5 block=512 rate=11025`;
`contestStart(0) -> TCqWpx`.

### Globals the desktop form used to own

Three pieces of engine state were created by `TMainForm` and are now created by the core itself,
because a nil there is a hard SIGSEGV, not a soft failure:

- `gDXCCList` (upstream `Main.pas:450`) — created in the headless form stub;
- `Histo` (`Main.pas:452`) — created in the library's `initialization`;
- `Tst` (the running contest pointer) — assigned by `mr_run_start`.

`Log.Clear` is deliberately **not** used: it also clears `Tst.Stations` and repaints VCL widgets,
so the core clears only the log arrays it owns.
