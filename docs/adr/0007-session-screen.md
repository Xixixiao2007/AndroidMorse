# ADR-0007 — The session screen: an injected clock, the history as the station pool, and the phone's ear

Status: **accepted by delegation** (T7, 2026-10-19; the same standing delegation as ADR-0004/0005/0006:
"不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002 (contests are
data), ADR-0004 (determinism), ADR-0005/0006 (the QSO engine).

T7 is "playable on a phone" (spec §11): the Compose screen of spec §8 on top of the T6 engine. Its
decisions are about **how the UI is allowed to drive the engine**, and one of them fixed a defect
that only became visible once real data met the roster.

## 1. The session's clock is passed in (`tick(nowMs)`), and the keying length comes from the audio timing

`SessionController` has no wall clock: the screen calls `tick(System.currentTimeMillis())` every
40 ms and the controller reads time from that and nowhere else. Consequences:

- the station's reply delay (`DxOperator.getSendDelay`, in upstream audio blocks), my keying
  length and the contest countdown are all pure functions of the tick sequence, so a whole contact
  is replayable in a JVM test with no device and no sound card (`SessionControllerTest`, 12 tests);
- the UI never has to know a rule, and the controller never has to know a pixel;
- "my send finished" is not a callback from the audio layer (the mixer has none) but the same
  `MorseTiming`/`MorseEncoder` the synthesizer uses, so the controller's idea of the duration
  matches what the user heard to within the envelope ramp.

Audio leaves the controller as `AudioCue`s (`text`, `station`, `durationMs`) that the screen drains
and plays and forgets. Cues carry the `RosterStation`, so the player needs no roster lookup.

**Rejected:** a `Callback` from `CwAudioEngine` (the mixer is fire-and-forget by design, T5); a
wall clock in the controller (untestable and non-deterministic); a `ViewModel` (a lifecycle
dependency for state that T9 will persist properly anyway).

## 2. The station pool is the contest's call-history file, behind a `CallPool` seam

Upstream draws a caller from the contest's **own call list** (`TContest.PickStation` →
`CqWwCallList`, `CqWW.pas:141,272`) and from `MASTER.DTA` only when a contest has no history. T5's
roster took `MASTER.DTA` and skipped calls whose exchange the history could not describe.

Measured on the packaged assets: `MASTER.DTA` holds 46 039 unique calls, `CQWWCW.txt` 8 875, and
they overlap in **4 584** — a 9.9 % hit rate per draw, so a ten-attempt `add()` throws about
**a third of the time** and every station that does get generated draws its CQ zone from the T5
stand-in instead of the file. Wiring the parsed history in (T6) without changing the pool would
therefore have made the *app* worse than the harness.

So `core-data` gained `CallPool` (size + indexed access); `MasterDta` and `CallHistoryFile` both
implement it, and the roster takes the interface. `SessionBootstrap` uses the history when it loads
and `MASTER.DTA` + the stand-in when it does not (a session that explains itself beats one that
refuses to start). The schema's `station.callPool` is honoured as the fallback source; it was
loaded but never used before, so no document changed.

**Rejected:** a schema change to name the history as the pool (a T3/T10-sized edit for a wiring
question); keeping the stand-in's synthetic zones (visibly wrong to an operator: a `K1ABC` in zone
33); raising the retry count (hides a data problem instead of fixing it).

## 3. T7 plays "single call"; a pile-up needs a selection affordance that does not exist yet

Spec §2 lists `SingleCall`, `PileUp` and `Competition`. A pile-up asks the operator *which* of eight
callers to work, and neither spec §8 nor upstream's phone-less UI answers that for a screen whose
only inputs are an entry box and a 2×4 grid. So the session runs one station at a time
(`stationsOnAir = 1`), and the worked station is replaced when a contact is logged, which keeps the
frequency alive.

The idle station calls CQ on a timer (2 s after it appears, then every keying-length + 2 s), which
is upstream's single-call behaviour and also gives the operator something to hear before they press
anything. `DxOperator` supplies every reply after that; nothing here invents station behaviour.

Pile-up, the caller chooser and the station lifecycle are T8's.

## 4. The signal panel shows the station's own exchange; the log still judges what was typed

Spec §8 asks for "the station being worked and its decoded exchange (large type)". A phone has no
decoder and no waterfall, so the panel renders the last thing the station transmitted
(`StationScript` text) and the station's exchange pieces — the assistance that replaces the
operator's ear. The log's verdict is still computed from the **typed** boxes against the station's
truth (`ContestRules.judge`), so a mistyped exchange is still flagged; the panel tells the operator
what to type, not what they typed.

`StationScript` is T6's finding F5: the station-side wording table (`Contest.pas:412-450`) rendered
through the **same** token expander as `MessageScript` (`renderTokens`), with `<my>` = the station's
call and `<#>` = the station's exchange, because the tokens are relative to the sender. It changes
no `DxOperator` behaviour.

## 5. Entry boxes are two, and `Space` advances between them

Schema v1's exchange is two sent pieces, and spec §8 says "exchange fields". The screen shows two
boxes; a contest with `entryParse` (ARRL SS `123 A 72 OR`) rejoins them with a space
(`ContestRules.typedFields`), so no space is ever typed inside a box. That is what makes
`Space`-advances-the-field (`Readme.txt:235`) safe on a phone keyboard, and it is why `Space` is
intercepted before the text field.

## 6. Only two `SeededRandom` streams, held by the controller

The roster's and the operators' (`seed` and `seed + 1`), created from one session seed; nothing else
in `app` may draw randomness, so the same seed and the same ticks replay the same session (spec §6,
ADR-0004 decision 3). The audio layer's fade stream is derived from a fixed root via
`FadeSchedule.seedFor` (T5's rule), so it stays a function of the seed too.

## 7. What T7 does not do

- No persistence and no settings: the operator call is upstream's default `W1AW` with a DXCC-derived
  exchange, and the session dies with the process. Rotation survives only because the activity
  declares `configChanges`; process death and the settings screen are T9.
- No serial-number exchange: a contest whose `exchange.sent` reads a running serial (ARRL SS) starts
  with a static serial, because `ContestRules.operatorExchange` is computed once. That is T10's
  per-contest pass, not this ticket's.
- No Compose UI tests: `androidx.compose.ui:ui-test-junit4` needs Robolectric on the JVM, which this
  build does not have. The proof is `assembleDebug` plus a real WSA run with screenshots
  (`.scratch/shots/t7-0*.png`); declared, not skipped silently.
- No portrait device proof: WSA is a fixed tablet-shaped window and does not follow
  `wm size`/rotation, so the portrait branch (the same composables in a `Column`) is compiled and
  reviewed but not captured. Declared in the ticket's evidence.
