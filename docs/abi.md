# The Kotlin ⇄ Pascal boundary

Route C (ADR-0020) splits the port at one seam: the original Pascal keeps the contest logic, Kotlin
draws the screen. This file is the contract across that seam. It is deliberately small.

## Rules that keep the seam cheap

1. **Plain C types only** — `Integer`, `Single`, `Pointer`, `PAnsiChar`. No Pascal strings, no
   objects, no callbacks. Anything richer is encoded as a byte buffer whose layout both sides own.
2. **No allocation across the boundary.** The caller passes a buffer and its capacity; the callee
   returns the number of bytes it wrote (or `-1`).
3. **Idempotent reads, explicit ticks.** Query functions take no state; the engine advances only
   through named calls.
4. **One opaque handle per run.** Engine state lives behind a `Pointer` the Kotlin side never
   inspects. `mr_run_start` returns it, `mr_contest_free` releases it.
5. **Nothing throws across the boundary.** A failure is `-1` (or `nil`); no Pascal exception
   crosses into Kotlin.

## Functions (what the library exports today)

```pascal
{ --- identity / contest catalogue (pure) -------------------------------- }
function  mr_version(): PAnsiChar; cdecl;
function  mr_contest_count(): Integer; cdecl;
function  mr_contest_name(idx: Integer; buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_contest_key(idx: Integer; buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_contest_class(H: Pointer; buf: PAnsiChar; cap: Integer): Integer; cdecl;

{ --- a contest object on its own (no run) ------------------------------- }
function  mr_contest_start(idx: Integer): Pointer; cdecl;
procedure mr_contest_free(H: Pointer); cdecl;

{ --- a run -------------------------------------------------------------- }
function  mr_run_start(idx: Integer; myCall: PAnsiChar): Pointer; cdecl;
function  mr_block(H: Pointer; outBuf: PSingle; cap: Integer): Integer; cdecl;
procedure mr_send(H: Pointer; msg: Integer); cdecl;
procedure mr_set_my_call(H: Pointer; text: PAnsiChar); cdecl;
procedure mr_set_his_call(H: Pointer; text: PAnsiChar); cdecl;
procedure mr_set_entry(H: Pointer; call, exch1, exch2: PAnsiChar); cdecl;
function  mr_save_qso(H: Pointer): Integer; cdecl;
procedure mr_set_monitor_level(H: Pointer; value: Single); cdecl;
procedure mr_set_data_dir(path: PAnsiChar); cdecl;

{ --- reads -------------------------------------------------------------- }
function  mr_contest_rate(): Integer; cdecl;
function  mr_block_size(): Integer; cdecl;
function  mr_log_count(): Integer; cdecl;
function  mr_log_cell(row, col: Integer; buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_score(which: Integer): Integer; cdecl;
function  mr_status_text(which: Integer; buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_last_error(buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_station_count(H: Pointer): Integer; cdecl;

{ --- harness only (the app never calls these) --------------------------- }
function  mr_station_call(H: Pointer; idx: Integer; buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_station_exch(H: Pointer; idx, which: Integer; buf: PAnsiChar; cap: Integer): Integer; cdecl;
function  mr_debug_state(H: Pointer; which: Integer): Integer; cdecl;
```

## The run API (implemented; verified on device)

The original desktop app advanced the simulation from its sound-card callback: every time the device
wanted a block it called `TContest.GetAudio`, which filters, modulates, runs the AGC, ticks the
operator and every station, and appends finished QSOs to the log. That call *is* the game loop, so
the core exposes it directly and the phone's UI drives it.

| function | meaning |
|---|---|
| `mr_run_start(idx, mycall) -> pointer` | create the contest of kind `idx`, set the operator call, reset the log and the rendered rows. `nil` on failure. |
| `mr_block(h, float* out, cap) -> int` | copies the next audio block (mono, `mr_contest_rate()` Hz) out of the ring the core's own thread fills; returns the sample count, or **0 when no block is ready yet** — call again, it is not the end of the run. The float scale is the AGC/mixer output (about 16-bit units: ~3 000 on the noise floor, ~19 000 on the operator's own CQ), so divide by 32768 before playback. **One thread only.** |
| `mr_send(h, msg)` | send a canned message by its `TStationMessage` ordinal (F1 CQ = 1, F2 exchange = 2, F3 TU = 3, F4 my call = 4, F5 his call = 5, F6 B4 = 6, F7 ? = 7, F8 NIL = 8). It goes through the form's `SendMsg` (`Main.pas:563`) — i.e. through `TStation.SendMsg` — because that is what records the message in `Me.Msg`, and `Me.Msg` is what tells the contest to put callers on the air when the message ends. |
| `mr_set_my_call` / `mr_set_his_call` | the operator's own call / the DX call just copied. |
| `mr_set_entry(call, exch1, exch2)` | the three typed fields of the form (`Edit1..3`): copied call, report, exchange. `Log.SaveQso` reads exactly these. |
| `mr_save_qso(h) -> int` | the Enter key, as the desktop's "TU & save" did: verify the copied call, let the contest adjust the exchange, answer with the DX call when it has not gone out, send TU, append the QSO. Returns the log length, `-1` for a bad handle, `-2` when the copied call is not acceptable. |
| `mr_set_monitor_level(h, 0..1)` | the `VolumeSlider1` of the form (`.dfm` default 0.75 = -15 dB); it is the self-monitor gain of the operator's own signal. |
| `mr_set_data_dir(path)` | where `DXCC.LIST` and the call histories live (the APK ships them in `assets/data`, the UI copies them to `filesDir`). Without it the contest still runs, but there are no callsigns to put on the air. |
| `mr_contest_rate` / `mr_block_size` | 11025 Hz / 512 samples. |

### Where the simulation runs

`TContest.GetAudio` *is* the simulation (filter, modulation, AGC, station ticks, appending finished
QSOs). It no longer runs on the caller's thread: `headless/AudioFeed.pas` starts one thread when the
library loads and `mr_block` is a pure consumer of the ring it fills (8 slots ≈ 0.37 s; the ring
paces the producer, so the simulation advances exactly as fast as the device plays). A pull that
finds it empty returns 0 immediately — the audio thread must never block on the engine.

Why: a raise on a thread the RTL did not create dies inside the unwinder (measured: SIGSEGV in
`fpc_popaddrstack`), so every engine bug was fatal on the phone. On an RTL thread it unwinds into a
handler that records it and keeps going.

**Build requirement:** `mrcore.lpr` must keep `cthreads` as the first unit in its `uses` clause.
Without it FPC links the RTL single-threaded: there is no thread manager, so creating a thread
aborts the process (measured: exit 232 with no tombstone) and a raise on a foreign thread kills it
in `fpc_popaddrstack`. Both symptoms look like unrelated engine bugs; they are one missing unit.
| `mr_log_count` / `mr_log_cell(row, col, buf, cap)` | the QSO log **as the original form rendered it**: `Log.LastQsoToScreen` formats each saved QSO per contest and appends it through `ScoreTableInsert`, so the core simply reads those rows back (0 = the row caption = UTC, 1..6 = the sub-items in the contest's own order). For CQ WPX that is `UTC, Call, RST, serial, Err, Wpm`. |
| `mr_score(which)` | 0 raw points, 1 verified points, 2 raw multipliers, 3 verified multipliers. |
| `mr_status_text(which, buf, cap)` | the three status captions the core's game loop writes into the desktop form, read back verbatim: 0 = the QSO rate (`n  qso/hr.`, `Log.pas:1107 ShowRate`), 1 = the run clock (`hh:nn:ss`, `Contest.pas:840`), 2 = the Pile-Up caption (`Pile-Up:  n`, `Contest.pas:842`). `-1` for an index other than 0..2. |
| `mr_last_error(buf, cap)` | the last exception an entry point caught, as `where: ClassName: Message`, or `""`. Rule 5 below is why this exists. |

### Why the rate and the clock are read, not recomputed

`ShowRate` already existed and already runs on every block: it counts the QSOs in the last five
minutes and writes `MainForm.Panel7` — the phone's panel was the only thing still showing a
literal `0  qso/hr.`, because nothing read the caption back. The clock has the same shape, and
reading `Panel2` is *more* correct than the Kotlin arithmetic it replaces: the core's clock is
`Tst.BlockNumber`, while the UI's sample count also includes the five one-sample blocks
`GetAudio` returns before it switches to full blocks (`Contest.pas:700-704`). One number, one
owner: the core keeps the run clock, the UI keeps the playback bookkeeping it needs for its
run-length auto-stop (`MainFormController.elapsedSeconds`), and the display shows the core's text.

Verified vs raw is the same seam, seen over time rather than as a second number: a QSO scores
*raw* when it is saved and only becomes *verified* when the DX station finishes the QSO and the
core copies its true data (`Contest.pas:800-815`). So the two columns differ between the save and
the confirmation — `verified = 0` while `raw = 1` — and catch up afterwards. A dupe never
verifies (`Log.UpdateStats` counts only error-free QSOs).

Starting a run resets the three captions along with the log and the score
(`headless/Session.pas StartRun`), because the game loop only rewrites them from the first full
block on: without the reset, Stop -> Run would show the previous run's rate and clock.

Evidence (2026-10-20, ZTE 7534N, CQ WPX):

- probe: `cqPeak=19350 calls=1 stations=1 call=P29SX exch=599/1 saved=1 log=1 pts=1 mult=1`
- app: `ContestAudio: peak=19350` while the CQ plays, `MainForm: QSO saved, log rows=1`,
  log row `00:00:22 | P29SX | 599 | 1 | NIL | 25`, score table `Pts 1 / Mult 1 / Score 1`.

### Rule 5, measured: a Pascal exception here ends the process

"Nothing throws across the boundary" is not a style rule. A Pascal exception raised while the
core is called from a JNI frame cannot be caught in Kotlin, and on the ZTE 7534N it did not even
reach a handler: the process ended with `exited cleanly (255)`, no tombstone, no logcat line. So
every entry point that mutates state (`mr_block`, `mr_send`, `mr_set_entry`, `mr_save_qso`,
`mr_run_start`) now catches, records into `mr_last_error` and returns a negative code (`-3` from
`mr_save_qso`). A failed audio block returns 0 samples — silence for one block — instead of
killing the run. `mrcore.lpr` also installs FPC's `ErrorProc`/`ExceptProc` hooks, which append
`RunError n` / `Unhandled Class: Message` to `mr-trace.log` in the data dir: a runtime error that
bypasses `try..except` is otherwise invisible on the phone.

Reads that hand back **engine-owned strings** (`mr_status_text`, `mr_log_cell`) take the engine
lock. The game loop reassigns those captions and appends those rows on the audio thread, and a
Pascal string is refcounted: an unlocked read hands the caller a reference the writer is about to
free. Measured before the lock: a native `SIGSEGV` in `fpc_popaddrstack` on the audio thread,
36 s into an idle run.

### Harness-only functions

`mr_station_call`, `mr_station_exch` and `mr_debug_state` exist so a test can drive a QSO without a
human copying CW by ear (the probe reads the caller's exchange, types it into the entry fields and
presses Enter). The app must never display what a station is sending: Morse Runner is copy-by-ear,
and the only decoder upstream has (`DebugCwDecoder`) is off by default.

`mr_debug_state` indices: 0 stations, 1 log rows, 2 run mode, 3 operator state (0 listening,
3 sending), 4 block number, 5 last message ordinal, 6 monitor level ×1000, 7 the operator's envelope
length, 8 its send position, 9 completed operator messages.

### Globals the desktop form used to own

Engine state the VCL form created is now created by the core itself, because a nil there is a hard
SIGSEGV, not a soft failure:

- `gDXCCList` (upstream `Main.pas:450`) — created in the headless form stub;
- `Histo` (`Main.pas:452`) — created in the library's `initialization`;
- `Tst` (the running contest pointer) — assigned by `mr_run_start`;
- the CW keyer (`MakeKeyer`, `Main.pas:482`) — created in the library's `initialization`;
- `VolumeSlider1.Value` (0.75) and the score table's three rows — the stubs reproduce what
  `Main.dfm` supplied; without them the operator's audio was multiplied to zero and the first saved
  QSO raised `EListError` on the empty score list.

`Log.Clear` is deliberately **not** used: it also clears `Tst.Stations` and repaints VCL widgets,
so the core clears only the log arrays and rendered rows it owns.
