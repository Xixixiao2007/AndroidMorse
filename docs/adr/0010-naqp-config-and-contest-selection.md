# ADR-0010 — NCJ NAQP as the first per-contest ticket, and a contest to choose

Status: **accepted by delegation** (T10, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0004 (schema v1), ADR-0007 (determinism) and ADR-0009 (settings: the
operator's knobs act on the next session).

T10 is spec §11's first row of "one ticket per remaining contest (11 configs + behaviour checks)".
The ticket also has to add what the app is missing: **nothing could start a contest other than CQ
WW** (`MainActivity` hard-coded `SessionBootstrap.DEFAULT_CONTEST`, and `SessionSettings` had no
contest field), so a new `contests/*.json` would have been invisible.

## 1. The contest: NCJ NAQP — and why not the ARRL DX

The handoff named four selection criteria, in order. The facts, from
`upstream/MorseRunnerCE/`:

| Criterion | NCJ NAQP | ARRL DX CW |
|---|---|---|
| an upstream call history to package unmodified | **yes** — `NAQPCW.txt` (15 467 lines) | yes — `ARRLDXCW_USDX.txt` |
| schema v1 only (no new capability) | **yes** — name + state are history columns | **no** — see below |
| independent of F8 (the computed-once serial) | yes — no serial | yes |
| decidable behaviour checks (loader, roster, parse, scoring, a `.pas` rule) | yes | yes |

**ARRL DX needs a schema capability this ticket may not add.** Its exchange depends on *my*
location: W/VE send RST + state/province, DX send RST + power (`ArrlDx.pas`; `Ini.pas:157-162`).
Spec §4's environment table gives `exchange.sent.*` only `myFields[].key` — there is no
`myCountry` in that environment, so a `sent` expression cannot branch on where the operator is.
Adding `myCountry` there is a schema extension and belongs to T3/ADR-0004's discipline, i.e. a
decision ticket of its own. `EXCHANGE` is also a single `<#>` piece in our message script, so the
two pieces would need the same branch twice.

NAQP's exchange is fully expressible with what v1 has: `their.exch1` is the history's `Name`,
`their.exch2` is the history's `State` (empty for a DX station, `NaQp.pas:215-216,439-448`),
`theirCall`/`theirExch2` are already in the scoring environment, and the local/`DX` split is
`callContinent(theirCall) == 'NA' or callCountry(theirCall) == 'Hawaii'` — exactly
`IsCallLocalToContest` (`NaQp.pas:410-421`).

## 2. What the config is

`core-contest/src/main/resources/contests/naqp.json`, schema v1, registered in `manifest.json`:

| Part | Value | Primary source |
|---|---|---|
| id / name | `naqp` / NCJ NAQP | `Ini.pas:128` |
| duration | default 30, min 1, max 720 | `Ini.Duration` default (`Ini.pas:263`); 720 = the real 12-hour window, §5 |
| my fields | `name` (text), `state` (text) | `Ini.pas:129-133` (`<name> [<state\|prov\|dxcc-entity>]`, e.g. ALEX ON) |
| my defaults | `{"name": "ALEX", "state": "ON"}` | `Ini.pas:133` `ExchDefault: 'ALEX ON'` |
| validation | name `[A-Z][A-Z]*`, ≤10; state `[0-9A-Z/]*`, ≤6, may be empty | `ExchFields.pas:45,66` (`etOpName`, `etNaQpExch2`) |
| their | `hisName` ← history `name`, `hisState` ← history `state`; `exch1 = hisName`, `exch2 = hisState` | `NaQp.pas:439-448` |
| scoring | points `1`; multiplier `theirExch2` when the call is in NA or Hawaii | `NaQp.pas:395-404,410-421` |
| call history | `NAQPCW.txt`, comma, skip `!!Order!!`/`#`, ≥3 fields, `call,name,state[,userText]`, no dedupe, no DXCC gate | `NaQp.pas:95-159` |
| messages | none (base wording) | `NaQp.pas` overrides none |
| capabilities | `farnsworth: false`, `requiresMyDxcc: false` | `Contest.pas:200`; `NaQp.pas:178-210` warns but does not refuse |

The call-history asset is copied **unmodified** from `upstream/MorseRunnerCE/NAQPCW.txt` into
`app/src/main/assets/`, and the packaged bytes are re-parsed by `PackagedAssetsTest` (MPL-2.0,
spec §9).

## 3. One engine-semantics change: an existing empty TEXT cell is a value

NAQP's DX records carry an empty state (`6Y5PW,PATRICK,`; `NaQp.pas:125` says the empty state is
handled later, in `PickStation`). The generator refuses a bound column the record does not have —
deliberately, because a fabricated value would hide a broken file — and `StationRoster` skips
such calls, so before this ticket every DX station would have been dropped from a NAQP pile-up
(and 4 000+ of the file's records are DX).

`CallHistoryFile.readRecord` now records `""` for an **empty-cell, non-required TEXT column**
(the cell exists; a shorter row still yields no column), and keeps rejecting empty *required*
columns. That is a change of what a record *contains*, not of the schema's shape:

- IARU HF's `section` is `required: true`, so its real-file assertion ("every kept record has a
  section") is untouched;
- CQ WW's / ARRL SS's `userText` and `ignored` columns are text and unread;
- NAQP's `state` is `required: false`, so a DX record now keeps `state = ""` and the station
  generates `exch1 = <name>`, `exch2 = ""` — what upstream sends.

## 4. F9 (`station.callPool`) is settled as a documented fallback

Schema v1's `station.callPool` names the pool a contest *falls back* to (`MASTER.DTA`); the app's
rule "the contest's own call history is the pool when it loads" (T7/`SessionBootstrap`) stays
app behaviour. No schema field is added: `callHistory.asset` already names the file, and a second
"which pool" field would be a second source of truth for the same string. Recorded here because
T9's handoff listed F9 as T10's to decide.

## 5. F13 (`duration.default`) stays a fallback

The operator's setting is the source of truth and each contest's `duration` block clamps it
(ADR-0009 §1). A first run does **not** adopt the contest's own default: `default: 30` matches
upstream's `Ini.Duration` default, and NAQP's block only widens the clamp to the real 12-hour
window (the settings screen's own maximum is 240 minutes). No code change; recorded because T9
left the question open.

## 6. Contest selection: one settings field, one picker, the same semantics as the other knobs

- `SessionSettings.contestId` (default `cqww`, `ContestLibrary.DEFAULT_ID`), normalized against
  the ids **this build actually ships a document for** (`ContestLibrary.catalog()`); an unknown or
  unavailable id falls back to `cqww`. The 12-id `ContestRegistry` stays the authority on names a
  config may claim; the manifest stays the authority on what is playable.
- The picker is a step-through row in the existing settings dialog (the window is short; a
  dropdown popup is another thing to clip). The 2×4 grid and every other setting are unchanged.
- **It acts like duration, speed and pitch: on 重开一局.** A running contest keeps the contest it
  was started with. RIT stays the only live setting. The dialog's footer already says this.
- The session snapshot already stored the contest id (T9); the settings blob now stores it too,
  so a *fresh* session starts from the picker's value.
- `SessionPlanner` (new, `app/session`) holds the "resume or fresh" decision that used to be
  private to `MainActivity`, so the claim "the next session is the chosen contest" is provable on
  the JVM instead of only on a device.

## 7. Determinism

ADR-0007 §6's contract is unchanged. NAQP declares no dice (`their.dice` is empty), so the
roster's draws are exactly the same streams; the same seed and settings replay the same session,
and `SessionControllerTest` gains an NAQP replay case through the same code path.

## 8. Deviations and known gaps, declared

1. **The operator's name/state are the contest's `exchangeDefaults`, not an editable exchange.**
   Upstream keeps one editable exchange string per contest (`Ini.UserExchangeTbl`, default
   `ALEX ON`, `Main.pas:1193-1255`). This port has no exchange editor, so NAQP sends upstream's
   default until one exists. Follow-up ticket (recorded, not silently shipped): an operator
   exchange editor (or a `myName` setting).
2. **A DX operator would send a state too.** Upstream sends name only for a non-NA operator
   (`NaQp.pas:215-216`, `DxTypes`). Our `sent` pieces cannot branch on `myCountry` (§1), so the
   port sends `name + state` whatever the call. Fixing it needs the same schema capability ARRL
   DX needs; declared here rather than hacked into `app`.
3. **A NA station whose history row has no state loses its multiplier.** Upstream substitutes the
   DXCC prefix (`NaQp.pas:292-304`); this port keeps the file's empty value (no fabricated
   state), so such a station sends its name only. The shipped file is nearly complete, and
   inventing a state is the kind of "guess" ADR-0004 §3 refuses.
4. **No `their.parse`.** The state is used verbatim (`ExtractMultiplier`); there is no second
   parse step to perform, and a parse with no consumer would be dead config. A DX station's
   empty second piece is logged as `DX` — upstream's own convention for a non-NA station
   (`Log.pas:945-949` `etNaQpNonNaExch2`), adopted in `ContestRules.sameValue`; the two-box
   entry cannot complete an empty second box (`QsoMachine`'s completeness rule).
5. **One band, one mode.** NAQP's per-band and category rules are out of scope for a
   single-receiver simulation (same limitation as every earlier contest).
6. **A name longer than 12 characters is not dropped.** Upstream skips such a row
   (`NaQp.pas:124`); the schema's TEXT column has no length rule, and the shipped file has no
   such row (the >12 check was measured against all 15 448 data rows). A future file with one
   would be caught by a column length rule in the schema, not by another contest's special case.

## Rejected

- **ARRL DX CW now.** Needs `myCountry` in the `exchange.sent.*` environment (§1) — a schema
  extension in T3/ADR-0004's territory, not a config.
- **A contest picker as a new screen or menu.** Spec §8 fixes the session screen; the dialog is
  one tap from the contest, and the picker is one row in a dialog that already has to scroll.
- **Dropping the empty-state records.** It would silently delete every DX station from the pool.
- **Making every non-required column's empty value a value.** IARU HF's empty sections are why
  `required` means "reject", and its real-file test would have been relaxed to make room.
- **A `myName` setting now.** The ticket's UI scope is the contest choice; a name setting without
  an exchange editor would still not let the operator fix the state, and both belong in one
  follow-up.
