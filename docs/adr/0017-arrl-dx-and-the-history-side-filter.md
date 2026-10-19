# ADR-0017 — ARRL DX CW: the first contest whose *station pool* depends on which side I am on, and the history's declarative row filter

Status: **accepted by delegation** (T17, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0003 (the bounded expression language), ADR-0004 (schema v1 and its
extension discipline), ADR-0005 (the QSO engine's seams), ADR-0006 (call history / station /
operator), ADR-0007 (determinism), ADR-0015 (the optional history and the running serial) and
ADR-0016 (the last per-contest shape).

T17 is spec §11's eighth "one ticket per remaining contest" row. ARRL DX CW is the **first
contest in this port where "which side of the contest am I on" is a real input**, not a constant:
the operator is either inside the contest's home region (W/VE — the USA and Canada) or outside it
(DX), and that single fact decides

- **who I can work** — a W/VE station works only DX stations, a DX station works only W/VE
  stations (`ArrlDx.pas:157-158`); the *station pool itself* is different;
- **what my contact is worth** — `3` points either way, but the multiplier is the worked station's
  DXCC entity for W/VE and the worked station's state/province for DX (`ArrlDx.pas:344-362`).

Upstream holds the two exchanges in `TDualExchContest`, whose Karnaugh table
(`DualExchContest.pas:66-76`) is three booleans: is my home call local, is the *station* the
simulated DX one, and is the requested message the sent or the received one. `TArrlDx` supplies the
first (`ArrlDx.pas:196-213` `OnSetMyCall`: my DXCC entity is `United States of America` or
`Canada`, with a best-effort prefix guess and an error string when the lookup fails).

## 1. Why ARRL DX now, and what was left

T16 applied the four criteria T10 established and chose HST over ARRL DX and ARRL Field Day
(ADR-0016 §1). Its inventory then read, for ARRL DX: "**blocked** — `myCountry` is not in
`exchange.sent.*`, and `ArrlDx.pas` also filters the history by my country". That reading was
half right, and §2 records which half.

What is true of the ticket as handed over: one upstream call-history file to package unmodified,
one new capability in the schema, and decidable behaviour checks on both sides of the contest.
What was wrong: the capability is **not** a new identifier in `exchange.sent.*`.

## 2. The decision: the blocker is the history's row filter, not `exchange.sent.*`

The handoff for this ticket proposed widening `exchange.sent.*` with `myCountry` so the sent piece
could be `ifElse(<I am W/VE>, <state>, <power>)`. Reading the upstream source and this port's sent
model says that is the wrong seam, for three reasons.

**(a) Upstream's dynamic exchange-2 type is a *received*-field mechanism.** `Main.pas:1348-1400`
`ConfigureExchangeFields` computes `RecvExchTypes := Tst.GetRecvExchTypes(skMyStation, MyCall,
Edit1.Text)` — the type of the exchange I *copy down* depends on the station I am working. The
comment above it (`:1351-1359`) and its `:1353` line ("ARRL DX: Exchange 2 changes between
etStateProv and etPower") describe exactly that. My *own* exchange's types come from
`OnSetMyCall`/`Me.SentExchTypes`, and they change the **box**, not the value.

**(b) Upstream sends the same thing on both sides: the box's text.**

```pascal
etStateProv, etPower:  // e.g. NAQP (OR); ARRLDX (OR | KW)     // Main.pas:1548
  begin
    Ini.UserExchange2[SimContest] := Avalue;
    Tst.Me.Exch2 := Avalue;
  end;
```

One branch for both types: the operator types their state *or* their power and `Me.Exch2` holds it
either way. The type only decides the caption and the length rule (`Contest.pas:585`
`etStateProv: Length(text) > 1`; `:589` `etPower: Length(text) > 0`). `Ini.pas:157-164` agrees:
`ExchType2: etStateProv; // or etPower`, `ExchDefault: '5NN ON'; // or '5NN KW'`,
`Msg: '''RST <state|province|power>''`.

**(c) The port's sent model is "one field per piece", and the UI is two boxes.** `ContestRules`
splits `exchange.myFields` into two halves to label a piece-level verdict
(`ContestRules.fieldPieces`), and `SessionController.exchangeLabels()` draws exactly two entry
boxes from the same split (spec §8: "entry — call field + exchange fields"). A conditional over two
*alternative* fields would need a third field (`rst`, `state`, `power`), which (i) splits 2/1 —
labelling the boxes "RST / State-Prov" and "Power" — and (ii) shows the operator a box that is
never transmitted. Both are UI changes, and T17 was scoped without them.

**What the document therefore says.** `exchange.sent.exch2` is the field itself:

```json
"myFields": [ { "key": "rst", … }, { "key": "exch2", "type": "text", "label": "State/Prov or Power" } ],
"sent":     { "exch1": "rst", "exch2": "exch2" }
```

`ArrlDxRulesTest` pins it: a W/VE operator sends `5NN OR`, a DX operator `5NN 100`, and the sent
piece needs no country at all — not even when the operator's call does not resolve in DXCC.

**What the real prerequisite is.** `myCountry` was already in `scoring.points/multipliers`'s
environment (spec §4, since T5/T6), so the two-sided **multiplier** needed no widening. The one
thing `myCountry` could not reach was the **station pool**: `ArrlDx.pas:157-158`

```pascal
// W/VE stations work only DX stations (those with non-empty Power field);
// DX Stations work only W/VE stations (those with non-empty State field)
if (    HomeCallIsLocal and not rec.Power.IsEmpty) or
   (not HomeCallIsLocal and not rec.State.IsEmpty) then
  ArrlDxCallList.Add(rec);
```

No earlier contest filtered its own history by who the operator is, so the schema had no way to say
"keep the rows this operator can work". That is the ticket's engine prerequisite, and §3 is why it
took the shape it did.

> **Recorded correction.** The directed handoff named `exchange.sent.*` as the blocking edge. The
> evidence above says otherwise, so this ADR acts on the evidence and records the change rather
> than widening an environment no expression needs. Deciding *how* to express a contest is the
> delegated half of the work; the upstream source is the fact half.
>
> **Recorded correction to ADR-0010 §8.2.** That note said NAQP's DX-side blank piece "needs the
> same schema capability ARRL DX needs". ARRL DX does not need it (one typed box, §2(b)) — NAQP
> does, and on NAQP's own terms: it already has *two* fields (`name`, `state`) for two pieces, so a
> `sent.exch2` of `ifElse(<I am DX>, '', state)` would fit the split with no field-count change.
> That remains an NAQP-scope decision (T10's document), not this ticket's, and the widening is not
> taken here on another contest's behalf.

## 3. `callHistory.filter`: a declarative per-row predicate

The schema gains one optional key inside `callHistory`:

```json
"filter": "ifElse(myCountry == 'United States of America' or myCountry == 'Canada', power != '', state != '')"
```

**Why an expression and not a flag.** ADR-0002 makes a contest a document; the existing
`requiresMyCallInDxcc` is a boolean flag because it is a *precondition* on the whole file ("refuse
everything unless my call resolves"), while ARRL DX's rule is a *predicate over each row*. A flag
vocabulary for it would have to invent something like `keepRowsWith: { local: "power", dx: "state" }`
— more schema for one contest, and still not data. The expression language already exists for
"contest-specific decision", so the predicate is written in it (ADR-0003).

**What it sees** (spec §4, new environment row):

| Identifier | Value |
|---|---|
| the history's own `columns[].name` | that cell of the row; a declared column the row does not carry reads as `""` |
| `myCall`, `myCountry`, `myContinent`, `myZone`, `mySection` | the station properties (spec §4), bound once per file from `CallFacts` |

and **nothing else**: a history is read before any QSO exists (`theirCall` has no value) and before
the contest's own expressions run (`constants` are not bound), so the loader rejects both. The
asymmetry is deliberate and enforced at load time — `ContestLoaderTest` proves a filter reading
`theirCall` is refused.

**Where it runs.** In `CallHistoryFile.parse`, per row, **after** the row has satisfied its column
spec and **before** the dedupe bookkeeping and the pool: a filtered row is not a station, exactly
as upstream's `continue` leaves it out of `ArrlDxCallList`. Everything downstream — the roster, the
autocomplete list, `SessionBootstrap`'s `note` — therefore sees the filtered pool and nothing else.

**It fails closed and loudly**, which is the part a silent `continue` would get wrong:

| Situation | Result |
|---|---|
| the predicate cannot be evaluated (an operator call that does not resolve in DXCC, so `myCountry` is absent) | `HistoryLoad.Rejected`, naming the row and the expression error |
| the predicate does not produce a boolean | `HistoryLoad.Rejected`, naming the row and the type it produced |
| the predicate keeps no row at all | `HistoryLoad.Rejected` ("no usable records"), never an empty pool |

A rejection is visible: `SessionBootstrap` falls back to `MASTER.DTA` with the stand-in exchange
and shows the reason in its note (T9's contract), so the operator sees "呼号表不可用（…）" instead
of an empty sky.

**Contrast with upstream.** `ArrlDx.pas:200-208` *guesses* the side from a call prefix when the
DXCC lookup fails (`A`/`K`/`N`/`W`/`VE`) and reports an error string. The port refuses instead: a
pool built from a guessed side would be the wrong contest's pool (a W/VE operator would work W/VE
stations), and a wrong pool looks exactly like a right one. This is the same fail-closed choice
every other contest's data path makes (ADR-0006, ADR-0011 §3).

## 4. What the document is

`contests/arrldx.json`, schema v1, loadable by `ContestLibrary.load` with no new loader rules
beyond §3:

| Part | Value | Upstream |
|---|---|---|
| `id` / `name` | `arrldx` / `ARRL DX CW` | `Ini.pas:157-158` |
| `duration` | `30 / 1 / 60` minutes | §9 below |
| `runModes` | `single-call`, `pile-up` | base modes; ARRL DX has no special run mode |
| `myFields` | `rst` (type `rst`) + `exch2` (type `text`, label `State/Prov or Power`) | `Ini.pas:159-163`: `etRST` + `etStateProv` **or** `etPower` |
| `sent` | `exch1: rst`, `exch2: exch2` | §2 above — `Me.Exch2` on both sides (`Main.pas:1548`) |
| `their.exch1` | `'599'` | `ArrlDx.pas:311-315` `getExch1` |
| `their.exch2` | `hisState + hisPower` | `ArrlDx.pas:320-323` `getExch2` = `State + Power` |
| `their.bindings` | `hisState` ← `state`, `hisPower` ← `power` (both) | the file has both columns; each row fills one |
| `validation` | `rst` must be three characters; `exch2` must not be empty | `Contest.pas:585,589`; see §8.3 |
| `scoring.points` | `3` | `ArrlDx.pas:351` `Qso^.Points := 3` |
| `scoring.multipliers[0].key` | `ifElse(myCountry == 'United States of America' or myCountry == 'Canada', callCountry(theirCall), theirExch2)` | `ArrlDx.pas:352-359` |
| `callHistory` | header-driven, `minFields: 4`, five columns, `filter` (§3) | `ArrlDx.pas:75-190` |
| `capabilities.requiresMyDxcc` | `true` — the contest cannot decide its own side without my entity | `ArrlDx.pas:196-213` |

Two details worth recording:

- **Both columns must be bound, and `minFields: 4` guarantees their cells exist.** The roster skips
  a call whose history record lacks a bound column (`StationRoster.add`), so binding `state` *and*
  `power` requires every record to carry both cells. Measured: every data row in the file has at
  least four cells, so `state` (cell 3) and `power` (cell 4) always exist — filled or empty. An
  empty `TEXT` cell is a value (T10's rule), which is what makes `hisState + hisPower` equal the one
  piece the station actually sends.
- **The file carries two `!!Order!!` header lines** — one for the DX section, one for the US
  section (`ArrlDx.pas:133-138`). The header is read once, from the first; the first header's
  indices happen to describe both sections (the US section's rows place the state in the same
  third cell and simply stop). Upstream re-reads the second header and gets `PowerInx = -1`, whose
  `GetValue` returns `''` — the same value the port gets from the absent-yet-declared cell, so the
  two agree. The second header line itself is dropped by the callsign grammar (`!!ORDER!!` is not a
  call), which `PackagedAssetsTest` pins.

## 5. The two-sided multiplier, in one key

```
ifElse(myCountry == 'United States of America' or myCountry == 'Canada', callCountry(theirCall), theirExch2)
```

- **W/VE side** → the worked station's DXCC entity (`ArrlDx.pas:352-355`
  `gDXCCList.FindRec(dxrec, Qso^.Call)` → `dxrec.Entity`). `theirExch2` is the station's power
  there, so it would be the wrong key — the lookup is what makes a DXCC entity out of a call.
- **DX side** → the worked station's own state/province (`ArrlDx.pas:359` `Result := Qso.Exch2`), a
  string with no lookup.

`callCountry` is the existing `CallFacts` built-in (spec §5), and `myCountry` is the existing
scoring identifier (spec §4) — **no new built-in and no new identifier** was needed. The three
values the expression chooses between are all already in the environment, which is why the
multiplier half of this ticket is document-only.

## 6. The data asset, measured

`ARRLDXCW_USDX.txt`, copied **unmodified** from `upstream/MorseRunnerCE/` into
`app/src/main/assets/` (the location every other history uses; `callHistory.asset` names it).
sha256 `18f1c5238f611f3cba396da21c8fb0aeba694c401d595d05e70ae9af8ac658f7`.

| Fact | Measured value |
|---|---|
| file size / lines | 97 310 bytes / 8 369 lines |
| data rows (non-comment, non-blank, non-header, non-empty call) | **8 357** |
| `!!Order!!` header lines | **2** (line 1: `!!Order!!,Call,Name,State,Power,UserText,`; line 3 793: `!!Order!!,Call,Name,State,`) |
| DX section (`…,Power,UserText`): rows / with a power / with a state | 3 781 / **3 781** / 0 |
| W/VE section (`…,State`): rows / with a state / with a power | 4 576 / **4 576** / 0 |
| rows carrying **both** a state and a power | **0** (so the two pools are disjoint and add up to 8 357) |
| distinct power values | **38** — `100`×1312, `KW`×945, `500`×485, `K`×412, `400`×173, `200`×114, `5`×90, `300`×47, `50`×45, …, `100.`×1 |
| longest power / longest state | 4 (`100.`) / 2 |
| distinct state/province values | **61** (AB … YT, incl. DC) |
| **W/VE operator's pool** (`filter` keeps `power != ''`) | **3 781** stations |
| **DX operator's pool** (`filter` keeps `state != ''`) | **4 576** stations |
| overlap between the two pools | **0** |
| DX calls with no DXCC entity (costs the W/VE side those contacts, §8.6) | **3**: `D1A`, `D1DX`, `LU1AW/D` |

MPL-2.0 (spec §9): the file is redistributed unmodified, its upstream copyright banner (`#` lines
naming ARRL and the file itself) is kept byte for byte, and `NOTICE.md`'s call-history entry covers
it. No modification of an MPL-covered file is being published — the port *reads* the data.

## 7. Determinism

The pool is a pure function of the file's order, the row's cells and the station properties: no
dice, no clock, no randomness (`Dice` is not involved in a filter). Two parses of the same bytes
with the same operator produce the same call list in the same order (`ArrlDxTest`), and a roster
built twice from one seed replays identically (`ArrlDxSessionTest`, both sides). ADR-0007 is
unchanged: the filter adds a deterministic row decision, not a new source of entropy.

## 8. Deviations and known gaps, declared

1. **The copied power is compared strictly.** Upstream's `etPower` comparison reduces the CW
   confusions `T/O/A/N → 0/0/1/9` before comparing (`Log.pas:889-892` `ReducePowerStr`,
   `:940-941`), so `1OO` copies a `100` correctly. The port's `TEXT` comparison is strict
   (`ContestRules.sameValue`), so a W/VE operator typing `1OO` is flagged where upstream would
   accept it. The comparison rule would have to know my side, which is a second, unrelated
   widening; a strict comparison can only *add* an error label, never lose a contact (the contact
   is still logged, and still scores).
2. **A mismatch on the box is labelled `ERR`.** Upstream labels it `leST` for a state and `lePWR`
   for a power (`Log.pas:937,941`) — a side-dependent name. The port has one shared label per field
   type and no `lePWR`; `exch2` therefore falls to the generic `QsoError.Err`.
3. **`validation` checks only "not empty".** Upstream's two length rules differ by one character
   (`> 1` vs `> 0`, `Contest.pas:585,589`) and follow my side, which the spec §4 validation
   environment cannot see. The union of the two rules is `length > 0`, which rejects exactly what
   both reject (an empty box) and never rejects a legitimate value — a 1-character power (`K`) is
   legal.
4. **My own second piece is empty, because the port has no exchange editor.** Upstream keeps one
   editable exchange string per contest (`Ini.UserExchangeTbl`, `Main.pas:1193-1255`, edited in
   `ExchangeEdit`) and ARRL DX's own default is side-dependent in the comment only
   (`ExchDefault: '5NN ON'; // or '5NN KW'`, `Ini.pas:161`). This port derives my exchange instead
   (`SessionBootstrap.myExchange`: the config's `exchangeDefaults`, then the DXCC table), and
   `station.exchangeDefaults` is a static map — so `arrldx.json` declares `{ "rst": "5NN",
   "exch2": "" }` and the device sends `5NN` with an empty piece until an editor exists. This is
   **inherited**, not new: ADR-0010 §8.1 recorded the same gap for NAQP (which ships hard-coded
   `ALEX`/`ON` defaults), together with the follow-up ticket ("an operator exchange editor (or a
   `myName` setting)"). ARRL DX is simply the first contest where the gap is *visible* instead of
   defaulted away, because there is no correct static value. The side is still verifiable on a
   device without the editor: the pool size, the stations' exchange shape and the multiplier rule
   all change with my call (see the ticket's device evidence).
5. **Duration `30 / 1 / 60`.** `Ini.pas:263` carries only the global default of 30 minutes and
   `docs/` has no ARRL DX window artifact — the same declared gap as T10–T16.
6. **An unresolvable worked call costs a W/VE contact its points.** The multiplier is
   `callCountry(theirCall)` and the engine fails closed on an unknown call, so the contact scores
   0 where upstream keeps 3 points and no multiplier (`ArrlDx.pas:353-355`). Measured: **3 calls of
   the 3 781** (`D1A`, `D1DX`, `LU1AW/D`) — pinned in `PackagedAssetsTest`. The DX side never
   looks a call up, so it cannot hit this at all. The same class of gap as ADR-0011 §3.
7. **`capabilities.requiresMyDxcc: true` is a declaration, not yet an effect.** The capability is
   loaded and unread for every contest (inherited gap); ARRL DX is the contest where it is most
   literally true, since without my entity there is no side.
8. **No special operating mode.** ARRL DX has no `rmHst`-style mode in upstream to port; the
   contest is operated like the rest.
9. Inherited, unchanged: the multiplier reads `theirCall` rather than the typed call (ADR-0005 §5);
   a duplicate does not score (ADR-0005 §8); `exchange.myFields[].send` and `messages` are still
   loaded-unread where a config declares them.

## 9. Duration: no artifact gives ARRL DX's window

`Ini.pas:263` sets the session length globally (30 minutes), and the ARRL DX block
(`Ini.pas:157-164`) carries no duration of its own. `docs/` has no artifact either, so the document
declares `{ "default": 30, "min": 1, "max": 60 }` exactly as T10–T16 did and this section is the
gap's record.

## Rejected

- **`myCountry` in `exchange.sent.*`.** §2: the sent piece is the typed box on both sides, so the
  identifier would be accepted by the loader and read by no expression — dead surface in a fixed
  contract. The widening is not taken speculatively.
- **One `state` field and one `power` field**, with `sent.exch2` conditional. §2(c): it splits the
  two entry boxes unevenly (2/1) and gives the operator a box that is never sent; both are UI
  changes this ticket was not scoped for.
- **A `callHistory.side` / `keepRowsWith` flag.** §3: more schema than the predicate, and it would
  still be a vocabulary invented for one contest.
- **Filtering the pool in `SessionBootstrap`** instead of in `CallHistoryFile`. §3: that puts a
  contest rule in the app and leaves the roster's `CallPool` unfiltered — the same file for
  everyone, which is exactly what upstream does not do.
- **A `try`/`orElse` built-in** to keep 3 points on an unresolvable call (§8.6). A new built-in is
  an explicit extension point (spec §5) and this one exists to paper over 3 calls of 3 781; the
  deviation is declared instead.
- **A prefix-based best guess** of my side when the DXCC lookup fails (§3). The pool would silently
  become the wrong contest's pool.
