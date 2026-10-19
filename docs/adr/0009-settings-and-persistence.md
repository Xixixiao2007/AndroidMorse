# ADR-0009 — Settings and persistence: the operator's knobs, and a contest that outlives the process

Status: **accepted by delegation** (T9, 2026-10-19; the standing delegation of ADR-0004/0005/0006/0007/0008:
"不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0004 (determinism),
ADR-0007 (the injected clock and the two interactive random streams), ADR-0008 (what T9 exposes) and on
`Ini.pas` / `Contest.pas` / `Main.pas` as primary sources.

T9 is "settings + persistence (duration, speed, pitch, RIT, bandwidth)" plus F10, T7's finding that a
process death loses the contest. The ticket's own rule decides most of this ADR: **a setting that is
stored but does nothing is a lie**, so every knob below is wired to the code that reads it, and the one
approximation (bandwidth) is declared rather than hidden.

## 1. The settings are one value type, and it lives in `app`

`SessionSettings` (`app/.../settings/SessionSettings.kt`) is a plain data class of primitives with one
`normalized()` that clamps every field to the range its control can express. Spec §3 assigns
"persistence" to `app` (and `SharedPreferences` by name), so the model, the codec and the two stores sit
there; none of them imports Android, which is why `SettingsTest`, `SessionSnapshotTest` and
`SessionPersistenceTest` run on the JVM. Only the two platform edges are Android code:
`PrefsStringStore` (a `StringStore` over `SharedPreferences`) and `MainActivity`'s wiring.

The knobs, their primary source, and what reads them:

| Setting | Upstream | Default | Range | Read by |
|---|---|---|---|---|
| duration | `Ini.Duration` (`Ini.pas:263`), `SpinEdit2` | 30 min | 1–240, then the contest's `duration` range | `SessionController.durationMs()` |
| speed | `Ini.Wpm` (`:227`), `SpinEdit1` | 25 WPM | 10–120 | `RosterSettings.speedWpm` (station speed ranges) and my keying |
| pitch | `Ini.Pitch` (`:240`), `ComboBox1` | 600 Hz | 300–900, step 50 | `RosterSettings.pitchHz`, the QRM's reference pitch, `SessionAudio`'s self-monitor |
| RIT | `Ini.Rit` (`:242`), `RitStepIncr = 50`, arrows in `Main.pas` | 0 Hz | ±500, step 50 | `StationAudio.voice(..., ritHz)` (see §2) |
| bandwidth | `Ini.BandWidth` (`:239`), `ComboBox2` | 500 Hz | 100–600, step 50 | `Receiver.bandPeakFor` → the QRN bed (see §3) |
| QRN | `Ini.Qrn` (`:252`) | **on** | on/off | `CwAudioEngine(bed = …)` vs `bed = null` |
| QRM | `Ini.Qrm` (`:253`) | **on** | on/off | `SessionController(qrm = …)`: the QRM clock is parked at `Long.MAX_VALUE` |
| flutter | `Ini.Flutter` (`:255`) | **on** | on/off | `RosterSettings.flutter` |
| LIDs | `Ini.Lids` (`:256`) | **on** | on/off | `RosterSettings.lids` → the roster's 10 % draw → `DxOperator.lids` |
| callsign | `Ini.Call` (`:223`, upstream default `VE3NEA`) | `W1AW` | `[A-Z0-9/]`, ≤ 15 | `SessionController.myCall`, the contest exchange derived from it |

**The four switches are on by default, contradicting upstream's off values.** That is T8's decision
continued, not T9's: ADR-0008 §3-4 shipped the effects on because a compiled-but-never-heard effect
proves nothing, and ADR-0008 §4 named T9 as the owner of the switch. A switch that defaults to "you
cannot hear the feature" would force the human's ear check to be repeated per setting; the switch's job
is the other direction — to prove an effect can be turned **off** (`SessionControllerTest` asserts the
QRM stream is empty for three minutes with `qrm = false`).

**`duration` versus the contest.** Schema v1 gives each contest a `duration` block (spec §4) and
`SessionController.start(durationMinutes = config.duration.defaultMinutes)` still falls back to it for a
caller with no preference, but the app always passes the operator's setting, clamped by the contest's
`min`/`max`: a 240-minute preference in a 60-minute contest is a 60-minute contest, not a session that
never ends. That leaves the schema's `duration.default` as a fallback rather than the source of truth —
recorded here rather than silently changed (T10 may want the per-contest default to win on a first run;
that is a decision for the ticket that ships the other contests).

**The callsign is in this ticket after all.** The handoff's list stops at the five knobs plus the three
switches, but ADR-0007 §7 and `SessionBootstrap` both say T9 owns the placeholder operator, and the
parameter was already threaded from the bootstrap into `MyStation`. It is the one field of the settings
that changes what the contest *sends* (the zone comes from the callsign's DXCC entry), so it is included;
removing it is a one-line edit if the human disagrees.

## 2. RIT is a receive offset on the stations, not a knob on every cue

Upstream subtracts the RIT term **inside the station loop** and mixes the operator's own sidetone in
afterwards (`Contest.pas:721-731`):

```
Bfo := Stations[Stn].Bfo - RitPhase - i * TWO_PI * Ini.Rit / DEFAULTRATE;
...
RitPhase := RitPhase + Ini.BufSize * TWO_PI * Ini.Rit / DEFAULTRATE;
```

Demodulating with a reference that ramps by `−Rit` moves every received tone **up** by `Rit` Hz, and the
sidetone never sees the term. The port keeps exactly that asymmetry and nothing else:

- `Receiver.receivedPitchHz(stationPitchHz, ritHz)` is the arithmetic (`station + RIT`);
- `StationAudio.voice(station, id, text, rootSeed, ritHz = 0)` shifts the voice it builds, so the seam is
  one parameter on the one function that turns a station into audio;
- `AudioCue.receiveOffsetHz(ritHz)` is the **rule**: a cue with a station is a received transmission and
  moves; a cue without one is my own sending and stays. Putting it on the cue type means the audio layer
  cannot get the distinction wrong, and `SessionControllerTest` asserts both halves;
- `SessionAudio.setRit(hz)` lets the settings screen apply it to a **running** contest — a RIT knob you
  have to restart the app for is not a RIT knob. The value is read on the audio worker per transmission.

**Deviation, declared: the QRM does not shift.** Upstream's interfering station is a `TStation` in the
same loop, so it moves with RIT too. Here it does not, because its pitch is the *evidence* for ADR-0008
§2's "off frequency" claim (the 100 Hz floor) and a test that asserts the offset against the operator's
pitch would become a test of RIT. The interfering signal is interference, not a station being worked;
the handoff's instruction is the same ("QRM 是干扰，不随主调谐").

## 3. Bandwidth scales the noise floor; there is still no filter

Upstream's bandwidth builds a two-stage, three-pass moving average (`Contest.pas:125-144`):
`Points = Round(0.7 · Rate / Bandwidth)`, `GainDb = 10·log10(500 / Bandwidth)`, i.e. a narrower receiver
keeps less noise and then boosts what is left by the same factor, so the station comes back at the same
level and the **noise floor** drops.

This port has no receiver filter at all — ADR-0008 §1 calibrated the QRN bed for a filterless generator —
so the audible half of the same physics is applied directly: white noise through a bandwidth `B` keeps
`B / B₀` of its power, so its amplitude scales as `sqrt(B / B₀)`:

```
Receiver.bandPeakFor(B) = NoiseBed.DEFAULT_BAND_PEAK · sqrt(B / 500)
```

- `bandPeakFor(500)` is exactly T8's calibrated bed, so an untouched setting sounds as T8 shipped it;
- 100 Hz is `sqrt(0.2) ≈ 0.45×` the bed (−7 dB relative to a station — upstream's `10·log10(500/100)`),
  600 Hz is `sqrt(1.2) ≈ 1.10×`; `ReceiverTest` asserts both ratios, and asserts them once more through
  a rendered `NoiseBed` so the setting is proven to reach the audio rather than a number object.

**What it does not do, and why that is honest rather than lazy:** without a filter a narrow receiver does
**not** reject the off-frequency QRM, does not round the CW envelope, and does not touch station levels.
Rejected alternatives: emulating the two 3-pass moving averages in the mixer (they filter a complex
baseband this port does not have — ADR-0008 §1 already argued the calibration is cheaper than a DSP
emulation, and doing it here would be a signal-processing project inside a settings ticket); scaling the
*stations* with bandwidth (that would invert upstream's gain compensation and make a narrow receiver
deaf).

## 4. Persistence is one small string store, `SharedPreferences` underneath

`StringStore` is the whole platform surface: `read(key)`, `write(key, value)`. Production is
`PrefsStringStore` over `SharedPreferences`; the tests are a `HashMap`. Two keys exist — `settings` and
`session` — each holding one blob, so a half-written change cannot exist: either the old blob or the new
one is read.

`SharedPreferences` over DataStore: the store holds ~12 scalars read once in `onCreate` and written when
a setting or a contact changes, all synchronous; DataStore's advantages are async and transactional
writes, which buy nothing for a value re-read after a restart, and it would add a coroutine dependency to
a screen that deliberately has none (ADR-0007 §1 rejected a ViewModel for the same reason). Spec §3 also
names `SharedPreferences` as what `app` wires.

The blob format is `key=value` lines with `\ \n \r \t ,` escaped (`EncodedText`, tested through the
hostile cases): readable with `adb shell run-as … cat` when a support question is "what does the app
think my settings are?", and immune to a line being split by its own content. The session blob nests the
settings under `set.*` and one line per contact, ten tab-separated escaped fields.

## 5. What a resume restores — and what it cannot

F10 is closed for the part that matters: **the score and the clock**. `SessionSnapshot` stores the
contest id, the session seed, the wall-clock start, the *effective* duration, the settings the session
was started with, the two entry boxes, and every contact in the log (points, multipliers, elapsed,
verified, verdict errors, corrections, scoring failure). `MainActivity` rebuilds the controller with
those values and re-records the contacts into a fresh `QsoLog`, which reproduces the totals because the
log's arithmetic is a function of the records.

- **The clock keeps running:** elapsed is `now − startMs`, so a resumed contest is further along, not
  restarted. `SessionSnapshot.isResumable` requires `0 ≤ elapsed < durationMs`; a contest whose clock ran
  out is cleared instead (`SessionPersistence` clears on the finished flag, `MainActivity` on load).
- **A running contest keeps its own settings:** the snapshot's settings win over the saved settings, so
  editing the speed does not silently rewrite a session that is already on the air.
- **What is regenerated, not restored:** the station on the air, the operator's patience, a half-sent
  message and the keyer queue. Those would need a full event journal — every tick of a 30-minute
  contest — and replaying them would make the snapshot both enormous and fragile. The resumed station is
  the *first* station of the seed again; the operator's ear will hear a different caller than the one
  that was mid-transmission, and ADR-0009 says so instead of pretending otherwise.
- A corrupt or foreign blob decodes to `null` and the app starts fresh (a snapshot whose clock or seed
  cannot be read is not a session this build can trust).

The seed is now **per session** (`System.currentTimeMillis()` at the first start) rather than T7's fixed
constant, and it is stored: that is what makes both a resume and the determinism claim work — the same
seed and settings still replay the same session, they are simply no longer the same session every launch.
The noise bed and the fade stream's root take the same seed, so the band a session hears replays with it.

## 6. When a value acts, and where the knobs are

The dialog is not a live mixing desk, and it says so in its own footer:

- **RIT applies immediately** on `保存` — receive-side, cheap, and upstream's own arrow buttons work that
  way;
- **everything else applies to the next session.** Duration, speed, pitch, bandwidth and the switches are
  baked into the roster, the contest clock or the audio engine when a session starts, so `重开一局`
  persists and starts a new contest. A setting that waited for a restart to *ever* act would be the lie
  the ticket forbids; a setting that rebuilt the roster mid-transmission would cost the operator the run.

**The entry point is a button at the right end of the status bar**, opening a dialog over the session.
Spec §8 fixes the session screen's layout and the 2×4 grid's meaning, so nothing in the grid changed and
the operator is one tap from the contest. Rejected: a navigation drawer (a phone contest screen has no
second destination), a separate settings activity (a lifecycle for one dialog, and it would have to
re-create the session anyway), a long-press on the status bar (undiscoverable, and the status bar is the
one row spec §8 defines as read-only).

## 7. Determinism is untouched

ADR-0007 §6's contract is intact: two interactive `SeededRandom` streams (roster `seed`, operators
`seed+1`) and two derived ones (fades `seedFor(root, call)`, QRM `seedFor(seed, "qrm")`). Settings change
the *inputs* to those streams, not the number of them, and `SessionControllerTest` asserts that two
sessions with non-default settings and one seed replay identically.

## 8. What T9 does not do

- **Contest selection.** Only `cqww` exists; the snapshot stores the id for T10.
- **The level knobs ADR-0008 §4 named** (the bed level, the QRM rate, the QSB depth) stay constants. T9's
  authorization is the five knobs and the switches; the QSB-shape observation ("96 % of gains sit at the
  1.0 ceiling") is an ear-dependent taste question for the human, not a bug, and `FadeSchedule.DEFAULT_DEPTH`
  is where it will be answered.
- **Callsign validation** is a character filter, not a licence check: the DXCC lookup decides the zone,
  and an unknown call falls back to the contest's exchange default (T5/T6's behaviour, unchanged).
- **No Compose UI test.** Same constraint as T7 (`ui-test-junit4` needs Robolectric, which this build does
  not have). The proof is `assembleDebug` plus the WSA run; the device evidence is landscape-only because
  WSA's window does not follow `wm size`, exactly as T7 declared.
- **`myCall` is not used for the QRM's `hisCall` message** — it already is, through the controller's own
  field; noted only so the next reader does not go looking for a second path.
