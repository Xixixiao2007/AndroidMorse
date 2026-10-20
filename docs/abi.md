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
