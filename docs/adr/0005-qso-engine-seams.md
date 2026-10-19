# ADR-0005 — The QSO engine: transcriptions, seams, and two deliberate deviations

Status: **accepted** (T6, 2026-10-19). Builds on ADR-0002 (contests are data), ADR-0003
(bounded expression language), ADR-0004 (schema v1).

T6 is "a complete QSO can be worked and scored" (spec §11). Its decisions are about **which
upstream behaviour is transcribed where**, because three different upstream units own parts of
a contact and confusing them is how logging bugs start (see CONTEXT.md, *QSO state* vs
*station state*).

## 1. What T6 transcribes, and from where

| Concern | Upstream source | Our unit |
|---|---|---|
| Which message `Enter` sends | `Main.pas:952-1093 ProcessEnter` | `QsoMachine.enter` |
| The message vocabulary | `Station.pas:19-22 TStationMessage` | `QsoMessage` |
| Message wording and tokens | `Contest.pas:412-450 SendMsg`, `Station.pas:242-300 SendText`, `Station.pas:364-430 NrAsText` | `MessageScript` |
| Queueing and `Esc` | `Station.pas:222-235 SendMsg`, `MyStn.pas:108-115 AbortSend` | `QsoMachine.send/abort` |
| Save-time verdicts | `Log.pas:998-1090 CheckErr`, `Log.pas` `TQso.CheckExch1/2` | `ContestRules.judge` |
| Points and multipliers | `CqWW.pas:226-262 ExtractMultiplier`, `arrlss.json`/`cqww.json` `scoring` | `ContestRules.score` |
| Aggregation, raw/verified, rate | `Log.pas:116-117`, `THisto` | `QsoLog` (T1, extended here) |

`ProcessEnter` is transcribed statement by statement, including the two early exits (empty
call ⇒ send CQ; Ctrl/Shift/Alt-Enter ⇒ log) and the `C`/`N`/`Q`/`R` decision table. The only
rewrite is that "valid exchange" means the contest's own `validation` rules instead of
upstream's per-field length checks — the rules are data now (ADR-0002/0004).

## 2. `core-qso` depends on `:core-contest` (api)

Scoring and validation are contest-authored expressions, already compiled by the loader. The
handoff's instruction — *"core-contest 已有 loader 与表达式引擎，直接用，不要重写"* — is the
reason: a second rule engine would be a rewrite of T3 and would drift from it.

The dependency is `api`, because `ContestRules` exposes `ContestConfig`/`FieldType` in its
signature. It costs nothing at runtime: `core-contest` is pure Kotlin and already in the APK.

## 3. The roster stays outside `core-qso`; the seam is a two-piece target

The station roster lives in `:core-contest` (spec §3) and is being split into `:core-station`
by another session while T6 runs. `core-qso` therefore **does not import it**. Instead:

```kotlin
data class QsoTruth(val call: String, val exch1: String, val exch2: String)
```

is what the engine needs from *the station being worked*: its call and the two exchange pieces
it actually sent (upstream's `TrueCall`/`TrueExch1`/`TrueExch2`, filled by
`TDxStation.DataToLastQso`). `app` maps whatever the roster exposes onto it. Benefit beyond
decoupling: the engine's tests need no call-history asset to exercise a contact.

This is also why verdicts are computed against `QsoTruth` rather than by asking the roster:
the comparison belongs to the QSO engine, the generation belongs to the roster.

## 4. One ordered queue, and `Esc` completes the send

Upstream keeps two things: a `set of TStationMessage` (`Msg`, used for flag queries such as
"is `msgCQ` in this send?") and an append-only list of rendered text pieces (`Pieces`, the
actual stream). An ordered `ArrayDeque<QsoMessage>` carries both: enqueue order is the send
order, and `contains` answers the flag queries. No behaviour depends on the set's unordered
nature.

`Esc` transcribes `TMyStation.AbortSend`: the queue is dropped **and the send is reported as
finished** (`Msg := [msgGarbage]` + `ProcessEvent(evMsgSent)`). Dropping the queue without
finishing the send would leave the state machine waiting for an audio event that never comes.

## 5. Deviation 1 — `their*` is ground truth; the typed values are the log

Upstream extracts points and multipliers from the **logged** values (`Qso^.Nr`, `Qso^.Call`),
then `CheckErr` flags the contact afterwards. Schema v1 separates the two: `myFields[].key`
binds what the operator typed, `theirExch1/2` and `their<Group>` bind what the station sent.
We follow the schema: the multiplier/pionts expressions see the station's true exchange.

Consequence, stated plainly: a contact with a mistyped exchange scores as if typed correctly
(upstream scores as typed *and* flags it). Both are already-invalid logs; the difference is
which wrong number the operator sees before the contact is thrown out. We prefer the schema
over the original here because a second, silent value model would make `their*` mean two
things in one expression.

## 6. Deviation 2 — piece-level exchange verdicts

Our station model has exactly two exchange pieces (T5 generates two strings, and the schema's
`sent`/`their.parse` are two-piece). Upstream's `TQso` compares four *typed* fields for ARRL SS
(`NR`, `Prec`, `Check`, `Sect`), because its `TDxStation` holds typed fields.

So a verdict names the **piece** that is wrong (`NR`, `PREC`, `CHK`, `SEC`, `ZN`, `RST`, `QTH`)
and carries the true piece as the correction, but cannot say "the serial was right and the
precedence was wrong" inside piece 1. Recovering that granularity needs typed fields on the
station (or a `sent`/`their.parse` split for exch1) — filed as a follow-up, not smuggled in.

## 7. `messages` in the config are wording overrides

`ContestConfig.messages` is a free-form map (`ContestLoader.readMessages`); spec §4 uses keys
`CQ` and `longCQ`. `MessageScript` looks a message up by a canonical key (`CQ`, `longCQ`, `TU`,
…); an absent key falls back to the upstream default wording transcribed from
`Contest.pas:412-450`. Unknown keys are ignored rather than rejected: the loader accepted the
document, and refusing it at QSO time would turn a cosmetic typo into a dead contest.

## 8. Error labels are upstream's, verdicts are not

`QsoError` reuses upstream's `TLogError` labels (`NIL`, `DUP`, `CALL`, `RST`, `NR`, `ZN`, `SEC`,
`CHK`, `PREC`, `QTH`, `ERR`) because they are the user-visible column values in MR's log and
operators recognise them. Only the subset schema v1 can produce is computed; the rest are
reserved, not emitted speculatively.

`QsoLog` keeps T1's contract: `DUP` and `NIL` are **rejected** (not added, totals untouched).
Every other verdict is accepted-with-errors, mirroring upstream's "the contact is in the log,
the error column explains it".

## 9. Not in T6

- `StationIdRate` ("TU <my>" every third QSO, `Contest.pas:417-421`) — a settings key (T9).
- Station-ID rate bookkeeping, LID behaviour, the DX operator's reply choice (`DxOper.pas`) —
  T8. T6 accepts "their audio completed" as an input.
- The 4-field ARRL SS verdict granularity (§6).
- `NrAsText`'s per-contest special cases (`Station.pas:371-395`): the `<#>` rendering uses
  the two-piece model, which is what the schema expresses.

## 10. A schema defect T6 had to decide: one field key, two meanings

`validation[].when` and `scoring.points/multipliers[].key` are allowed the same identifiers —
`myFields[].key` (spec §4). In the shipped documents they mean different things:

- CQ WW: `{ "field": "zone", "when": "not (zone >= 1 and zone <= 40)" }` is the rule that fires
  while the operator types the *station's* zone. Reading `zone` as "my zone" would make the rule
  dead code (my zone is valid before the session starts) and leave nowhere to type his.
- IARU HF: `"points": "... theirExch2 == itu ..."` only scores 5/3/1 correctly when `itu` is
  *my* ITU zone; reading it as the typed box compares the station's exchange with its own copy,
  which is always equal and always scores 1.

So the same key is the entry box in validation and my own station in scoring. `ContestRules`
implements exactly that: `validate` evaluates against the typed values, `score` evaluates against
`MyStation.exchange` with the typed box as the fallback when my value is unknown.

This is recorded as a defect rather than a design: the schema should carry two sets of field
definitions (`my` and `theirs`) or two namespaces. Filed as a follow-up on the T3 schema
(`.scratch/progress.md`, *T6 findings*); a fix changes the documents and the loader, not the
engine.
