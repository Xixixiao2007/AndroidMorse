# ADR-0008 — Realism effects: a noise bed, a second cue stream, and the switches that turn them on

Status: **accepted by delegation** (T8, 2026-10-19; the standing delegation of ADR-0004/0005/0006/0007:
"不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0007 (the session's
injected clock and its two random streams) and on `Qsb.pas`/`QrnStn.pas`/`QrmStn.pas` as primary sources.

T8 is "authentic sound" (spec §7's QRN, QRM, QSB, flutter and LIDS). The effects are not rules: they
must be audible, they must not perturb the QSO engine, and the same session seed must still replay the
same session. These are the seams that decide that.

## 1. QRN is a mixer-level bed, not a station

`NoiseBed` (core-audio) is handed to `CwMixer` as an optional `bed`; `render` adds it into every block
before the master gain, exactly as upstream sums its noise into the same complex block the stations
write (`Contest.pas:695-716`). It is **not** a `CwVoice`: the band is present when nothing transmits,
it has no message length and no roster entry.

- Levels are upstream's, corrected for the filter this port does not have: a uniform bed at
  `0.015` peak (upstream's `3·NOISEAMP` against a station's `9000…45000` is 20× louder, but its two
  3-pass moving averages cut the RMS by about that much), impulses at 60× the bed at 110/s (upstream's
  1 % at 11 025 Hz), bursts at 0.215/s — upstream's 1 % per 46 ms block — with the crash capped at 8×
  the click rather than upstream's 55×, so the mixer's full-scale clamp is not the effect.
- The render path stays **allocation-free** and **block-size independent**: the xorshift state and the
  burst fields are plain fields and every draw is per sample. `NoiseBedTest` measures the allocation
  and proves the block invariance.
- The seed is per session (`MainActivity` passes the session seed), so the noise a session hears is as
  replayable as the stations in it.

**Rejected:** a `TQrnStation`-shaped voice (a "station" with no call, no exchange and no state is a
lie in the audio layer and would consume a mixer slot); a filter emulation (we have no receiver filter;
calibrating the constants to the ear is cheaper and is what the numbers above are).

## 2. QRM is a second cue stream with its own derived random stream

`QrmStation` (core-qso) models `TQrmStation`: a call from the contest's pool, `5000 + 25000·Random`
level, a Gaussian ±300 Hz offset, 30–50 WPM, `1 + Random(5)` transmissions — the drawn first message
(`QRL?` / `QRL?   QRL?` / long CQ / `QSY QSY`, a seven-way draw) and then a long CQ per remaining
patience, 4 s ± 2 s apart.

The session schedules it on the injected clock and emits `QrmCue`s on a **separate stream**
(`drainQrm()`); the screen passes them to `SessionAudio.playQrm`, which plays them as a steady
`StationAudio.signal` (no QSB — `QrmStn.pas` has no `TQsb`).

- **Why apart:** T7's `drainCues()` is the QSO's contract (my sending and the station being worked, in
  order). Putting interference in the same deque would have changed that contract and every test that
  asserts a single cue. Two deques keep the interference unable to reorder, delay or drop a QSO cue.
- **Which randomness:** ADR-0007 §6 says the roster's and the operators' streams are the only sources
  of truth, and that the fade stream is *derived* from a fixed root. The interference follows the same
  rule: `SeededRandom(FadeSchedule.seedFor(seed, "qrm"))` — derived from the session seed, never
  shared, so no contact can be perturbed and the seed still replays the interference.
- **Off frequency:** upstream's Gaussian may land at 0 Hz, i.e. on the operator's own frequency. A
  phone has no spatial cue to separate them, which would make spec §7's "off-frequency" unprovable and
  the effect sound like a second copy of the worked station. The offset is floored at 100 Hz, keeping
  its sign — the one transcription deviation, declared in `QrmStation`'s KDoc.
- **Rate:** upstream draws a QRM once per ~4 minutes. A two-minute device check has to hear one, so
  the gap is 40 s ± 20 s; T9 exposes the rate and this becomes its default.

**Rejected:** a QRM "voice" inside the roster (the roster draws real stations with exchanges; a QRM has
neither); scheduling it in the audio layer (the clock lives in the controller, ADR-0007 §1).

## 3. QSB/flutter and LIDS are wiring, and wiring is the ticket

`FadeSchedule` (T5) and `DxOperator`'s LID dice (T6) already existed; T8 makes the session use them:

- `SessionController.start` now passes `RosterSettings(lids = true, flutter = true)` — upstream's
  defaults are `Lids = true, Flutter = false` (`Ini.pas:252-254`), but a compiled-and-never-heard effect
  proves nothing, and spec §7 lists flutter as part of the sound. T9's settings screen owns the switch.
- `fillStations` builds `DxOperator(..., lids = station.behaviour == BehaviourProfile.LID)` — the
  roster's 10 % LID draw finally reaches the operator that rolls the LID dice. No second random source:
  one operator, one stream.
- `StationAudio.voice` already generated the fade schedule; `StationAudioTest` now asserts what
  "audible" means mechanically: the gains exist, stay inside the fade depth, flutter's mean step is
  more than twice QSB's, and the interfering signal has no fade at all.

## 4. What T8 does not do

- **Pile-up** (the caller chooser, eight concurrent stations, F11's patience timeouts). T8 is the
  sound of the one station on the air; a pile-up is its own ticket.
- **Settings**: the bed level, the flutter share, the QRM rate and the depth are constants. T9 exposes
  them; every constant names the setting it will become.
- **Per-contest QRM wording**: `ArrlSS.pas:257` (and siblings) override upstream's `msgLongCQ`. Our
  schema's `messages` map has no key for it, and the shipped session is CQ WW, whose wording is
  upstream's base text. T10's per-contest pass carries the override.
- **The ear check** is still the human's: machine checks cannot prove "sounds like a contest band".
  The device run proves the session advances for a minute with every effect running and nothing throws
  (`.scratch/shots/t8-0*.png`), and no more than that.
