# Spec — Morse Runner for Android

Status: **draft for approval** (2026-10-19). Supersedes nothing.
Reference implementation: `upstream/MorseRunnerCE` (MRCE 1.85.4, MPL-2.0).
Decisions behind it: ADR-0001 (native), ADR-0002 (contests are data), ADR-0003 (expression engine).

## 1. Goal

A native Android app that reproduces **Morse Runner Community Edition 1.85.4** contest
simulation: simulated CW pile-ups at your chosen speed/pitch, QSO state machine driven by
canned messages, per-contest exchange validation, log + scoring, and the realism effects
(QRM / QRN / QSB / flutter / lids).

**Non-goals for v1:** SO2R, networking, integration with N1MM/DxLog, score submission,
Windows/Linux parity of INI keys.

## 2. Glossary (domain language — use these words in code)

| Term | Meaning |
|---|---|
| **Contest** | A configuration document describing exchange fields, validation, scoring multipliers and station behaviour. |
| **Exchange** | The information exchanged in a QSO: what I send and what the other station sends (e.g. `5NN 14`). |
| **Message** | A canned transmission bound to a function key: CQ, EXCH, TU, MYCALL, HISCALL, B4, QRZ(?), NIL, WIPE, NR?. |
| **QSO state** | The engine's current expectation: `CallingCQ`, `WaitingForCall`, `SendingExchange`, `WaitingForExchange`, `Logged`. |
| **DX station** | A simulated station calling or answering: call sign, speed, pitch, strength, behaviour profile. |
| **Run mode** | `SingleCall` (one station at a time), `PileUp` (many callers), `Competition` (timed scoring run). |
| **LID** | A deliberately imperfect simulated operator (mistakes, wrong RST, sends when it should not). |
| **Mult** | Multiplier for score (unique prefix, zone, section, society…). |
| **NIL** | "Not in log" — the engine's judgement that the station was not actually worked. |

## 3. Architecture

Single Gradle project, modules split by *testability without Android*:

```
app/              Compose UI, Activity, audio output, asset loading, persistence
core-contest/     schema model + loader + expression engine  (pure Kotlin/JVM)
core-qso/         station roster, QSO state machine, log, scoring  (pure Kotlin/JVM)
core-audio/       Morse timing table, sample synthesis, mixer  (pure Kotlin/JVM except AudioTrack sink)
```

`core-*` have **no Android dependencies** so they run as fast JVM unit tests; `app` wires them
to Compose, `AudioTrack` and `SharedPreferences`.

## 4. Contest configuration (schema v1 — settled by T3)

One JSON document per contest, loaded and validated at runtime (ADR-0002). The draft in
this section was frozen after the T3 pressure test: CQ WW, ARRL SS and IARU HF — the three
hardest — are expressible, so the remaining nine are expected to fit (ADR-0004).

```jsonc
{
  "schemaVersion": 1,
  "id": "cqww",
  "name": "CQ World Wide DX CW",
  "duration": { "default": 30, "min": 1, "max": 60 },   // minutes
  "runModes": ["single-call", "pile-up"],               // subset of single-call|pile-up|competition

  // Expression constants, injected as identifiers into every evaluation.
  "constants": { "arrlSections": ["CO", "IA", "…"] },

  "exchange": {
    // What the operator types. type ∈ rst|int|serial|enum|check|text.
    "myFields": [
      { "key": "rst",  "type": "rst", "send": "5NN", "label": "RST" },
      { "key": "zone", "type": "int", "range": [1, 40], "label": "CQ Zone" }
    ],
    // Optional: one text box parsed as a whole; the named groups are field keys.
    "entryParse": { "pattern": "^(?P<nr>[0-9]+) (?P<prec>[QABUMS])$" },
    // Optional: how my exchange splits into the two sent pieces.
    "sent": { "exch1": "str(nr) + ' ' + prec", "exch2": "…" },

    // The station being worked: generation rules, not an expectation string.
    "their": {
      "dice": ["d0", "d1"],                       // seeded draws in [0,1), supplied by the runtime
      "bindings": {
        "hisZone": { "source": "history", "column": "zone" },
        "hisSer":  { "source": "serial" }
      },
      "derive": { "zone": "ifElse(num(hisZone) < 9 and d0 < 0.05, '0' + hisZone, hisZone)" },
      "parse": { "pattern": "^(?P<chk>[0-9]{2}) (?P<sect>[A-Z]{2,3})$" },   // → theirChk, theirSect
      "exch1": "'599'",
      "exch2": "zone"
    }
  },

  "validation": [
    { "field": "zone", "when": "not (zone >= 1 and zone <= 40)", "error": "Invalid CQ zone" }
  ],

  "scoring": {
    "points": "…",
    "multipliers": [ { "key": "'ZN-' + str(zone)" }, { "key": "…", "when": "…" } ]
  },

  "callHistory": {
    "asset": "CQWWCW.TXT",
    "delimiter": ",",
    "skipPrefixes": ["!!Order!!", "#"],
    "header": { "marker": "!!Order!!", "names": { "call": "Call", "zone": "CQ Zone" } },
    "minFields": 3,
    "columns": [
      { "name": "call",     "index": 0, "type": "callsign" },
      { "name": "zone",     "index": 1, "type": "digits", "required": true },
      { "name": "userText", "index": 2, "type": "text" }
    ],
    "dedupe": false,
    "requiresMyCallInDxcc": false
  },

  "station": {
    "callPool": "MASTER.DTA",
    "speedRange": { "min": "speed - 5", "max": "speed + 10" },
    "exchangeDefaults": { "rst": "599" }
  },

  "messages": { "CQ": "CQ SS <my>", "longCQ": "CQ CQ SS <my> <my> SS" },
  "capabilities": { "farnsworth": false, "requiresMyDxcc": false }
}
```

### The evaluation environment (fixed contract)

Every expression is evaluated against this identifier set; any other identifier is a
**load-time error**, so a misspelled field can never reach a QSO.

| Expression | Identifiers available |
|---|---|
| `station.speedRange.*` | `speed` |
| `exchange.sent.*` | `myFields[].key` |
| `exchange.their.derive.*` | earlier derives, `their.bindings` names, `their.dice` |
| `exchange.their.exch1/exch2` | derives, bindings, dice |
| `validation[].when` | `myFields[].key` |
| `scoring.points`, `scoring.multipliers[].key/when` | `myFields[].key`, `theirCall`, `theirExch1`, `theirExch2`, `their<Group>` from `their.parse`, `myCall`, `myCountry`, `myContinent`, `myZone`, `mySection` |
| every expression above | `constants` |

`myCountry`/`myContinent` come from the DXCC lookup of the operator's own call (spec §9);
`theirCall`/`theirExch*`/`their<Group>` are the logged values of the QSO.

### Load-time validation (what a bad config cannot do)

The loader rejects a document — naming the file, the field and the `line:col` of the
offending JSON or expression:

- JSON syntax, wrong type, unknown field, missing required field, bad enum value;
- expression compile errors: syntax, unknown function, arity, literal regex
  (`ExprError.render()`);
- an identifier outside the environment table above;
- an invalid `entryParse`/`their.parse` regex, or a group with no matching field;
- `schemaVersion` ≠ 1, an `id` that is not a known contest id, duplicate field keys.

## 5. Expression language

- Literals: numbers, strings, booleans, lists.
- Operators: `+ - * / %`, `== != < <= > >=`, `and or not`, `?:`, `in`.
- Identifiers resolve to exchange fields, station properties, or constants.
- Built-ins (v1): `upper/lower/len/substr`, `matches(str, regex)`, `num(str)`, `str(x)`,
  `callPrefix(call)`, `callZone(call)`, `callCountry(call)`, `callContinent(call)`,
  `arrlSectionOf(call)`, `indexOf(list, value)`, `listAt(list, index)`,
  `unique(list)`, `count(list)`, `sum(list)`, `ifElse(cond, a, b)`.
- **No** loops, assignment, I/O or reflection. Evaluation carries a step budget (default 10 000)
  and fails closed with a typed error.
- **No randomness.** Dice are inputs: the config declares them (`their.dice`) and the runtime
  supplies seeded draws, so evaluation stays deterministic (ADR-0004).
- Errors are user-visible in the contest editor: `line:col — message`. Compilation also
  answers `identifiers()`, so the loader checks every name against the §4 environment table.
- A new built-in is an explicit extension point: spec edit + ADR-0003 conformance + tests.
  Four were added by the T3 pressure test (`str`, `indexOf`, `listAt`, `callContinent`).

## 6. Runtime

- **Station roster**: 0–8 concurrent simulated stations; each has call, speed (WPM),
  pitch (Hz), level, fade profile, and a behaviour profile (normal / LID).
- **QSO state machine** (mirrors upstream `Main.pas`): message sends are queued; `Enter`
  sends the state-dependent message; the engine advances on received audio completion.
- **Log + scoring**: every QSO validated on save: dup, wrong call, wrong RST, wrong
  exchange field, NIL. Score = points × multipliers, plus a 5-minute rate histogram.
- **Timing**: the upstream keyer, **not** textbook PARIS — one unit is `60/48/WPM` s =
  `1250/WPM` ms (`VCL/MorseKey.pas:249`; the word is 48 units with a 5U inter-word space).
  Gaps are 3U inter-character, 5U inter-word, 4U inter-message. Farnsworth
  (`VCL/FarnsKeyer.pas:292-293`) stretches the gaps only, for contests that allow it.
  Already implemented and tested in `core-audio/MorseTiming.kt` (T1).

## 7. Audio

- One `AudioTrack` (streaming, 48 kHz mono 16-bit, low-latency attributes), fed by a
  **lock-free block mixer** on a dedicated thread; no allocation on the audio path.
- Each CW signal = sine at its pitch × **Blackman-Harris** key-on/key-off ramp. Upstream
  `RiseTime` is 5 ms (`VCL/MorseKey.pas:80`) and the ramp length is
  `Round(2.7 × RiseTime × Rate)` samples (`:150-154`), i.e. ~13.5 ms of samples at any rate.
- Effects: QRN (impulse + band noise), QRM (other CW signals off-frequency), QSB
  (Rayleigh-like slow amplitude modulation), flutter (fast periodic AM), LIDS behaviour.
- Self-monitor of my own sending, with the same synthesizer.
- Verification: JVM tests assert dit/dah/gap sample counts and envelope continuity;
  on-device check is "audible, click-free, correct rhythm" on WSA.

## 8. UI (phone-first)

Portrait, top→bottom:
1. **Status bar** — contest name, elapsed/remaining, score, rate.
2. **Signal panel** — the station being worked and its decoded exchange (large type).
3. **Entry** — call field (soft keyboard, autocomplete from call history) + exchange fields,
   `Enter` on the keyboard sends.
4. **Message grid — 2 rows × 4 columns** (human-mandated):

| | | | |
|---|---|---|---|
| **CQ** (F1) | **EXCH** (F2) | **TU** (F3) | **NR?** (F12) |
| **?** (F7) | **B4** (F6) | **NIL** (F8) | **WIPE** (F11) |

Long-press on **CQ** sends **MYCALL** (F4); long-press on **TU** sends **HISCALL** (F5).
ESC/stop-sending is a small button next to the entry field.
Physical keyboards: F1–F12, Enter, Space, Esc map 1:1 (works on WSA/tablet/Bluetooth).

Landscape: grid moves to a right-hand column, enlarging the signal panel.

## 9. Data assets

| Asset | Source | Notes |
|---|---|---|
| `MASTER.DTA` (1.2 MB) | upstream | call-sign pool; convert to an indexed binary asset at build time |
| `DXCC.LIST` | upstream | prefix → country/zone; needs a converter + verification test |
| contest call-history `*.txt` | upstream | per-contest, `asset` in the config; formats differ — `CQWWCW.TXT` is headerless positional, `IARU_HF.txt` is header-driven; see `docs/contests/exchange-facts.md` |
| `arrlSections` | upstream `Util/ArrlSections.pas` | enum values for the expression built-ins |

MPL-2.0 obligations: keep upstream copyright notices, publish modifications of MPL-covered
files. Data files are redistributed unmodified.

## 10. Testing

- `core-*` unit tests on the JVM (red → green per ticket).
- Behavioural parity tests transcribed from `upstream/MorseRunnerCE/Test/*.pas`
  (callsign utils, DX oper, DXCC list, lexers, SS exchange parser).
- Device smoke test per milestone: build → `adb install` → launch on WSA → screenshot.

## 11. Tickets (tracer bullets, blockers first)

| # | Ticket | Blocked by | Proves |
|---|---|---|---|
| T1 | Project skeleton, module split, WSA deploy script | — | APK builds and installs from the real source tree |
| T2 | Expression engine + exhaustive unit tests | — | language specified in §5 works, fails closed |
| T3 | Schema v1 + configs for CQ WW / ARRL SS / IARU + loader + load-time validation | T2 | **schema pressure test** — hardest contests expressible |
| T4 | Audio engine: Morse timing, envelope, single-signal playback | T1 | correct, click-free CW on device |
| T5 | Station roster + call/exchange generation from MASTER.DTA | T3, T4 | plausible pile-up audio |
| T6 | QSO state machine + messages + log + scoring | T3, T5 | a complete QSO can be worked and scored |
| T7 | Compose UI: 2×4 grid, entry + autocomplete, status/rate | T6 | playable on a phone |
| T8 | Realism effects QRM/QRN/QSB/flutter/LIDS | T5 | authentic sound |
| T9 | Settings + persistence (duration, speed, pitch, RIT, bandwidth) | T7 | configurable session |
| T10–T20 | One ticket per remaining contest (11 configs + behaviour checks) | T3, T6 | all 12 contests preserved |

**Milestone A (playable CQ WW): T1–T7.** Milestone B: T8, T9. Milestone C: T10–T20.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Schema cannot express HST/ARRL SS/IARU | T3 pressure test *before* the engine (blocking) |
| Audio latency/glitching on real phones | allocation-free mixer, 40–60 ms buffer, WSA + user's phone check |
| Rewrite drift from upstream behaviour | parity tests from `Test/*.pas`; contest-by-contest verification |
| 12 contests is a long haul | configs are data; each contest is an independent ticket |
