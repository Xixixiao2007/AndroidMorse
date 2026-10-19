# ADR-0006 — The other half of T6: the call-history reader and the station-side operator

- Status: **accepted by delegation** (2026-10-19, same delegation as ADR-0004/0005: "不需要我同意，
  你们爱怎么做怎么做" / "如果有选择题，自动选最优解").
- Supersedes nothing. Refines ADR-0005 §3 (the roster seam).

## Context

T6 ("QSO state machine + messages + log + scoring", spec §11) was worked by **two sessions in
one shared tree**, because the parent session handed the ticket off while another session was
still writing `app/**` and `core-audio/**`. The split is recorded here so the next reader does
not think a file is missing:

| Deliverable (handoff) | Delivered by | Where |
|---|---|---|
| my-station state machine, message queue, `Enter`, canned messages | first session (`35b17b3`) | `QsoMachine`, `Messages`, `QsoEntry` |
| log verdicts + scoring + raw/verified + rate | first session (`35b17b3`) | `QsoVerdict`, `ContestRules`, `QsoLog` |
| **`HistoryLookup` implementation (three call-history formats)** | this session | `CallHistoryFile`, `Callsign` |
| **station-side operator state machine (`DxOper.pas`)** | this session | `DxOperator` |
| config → roster → operator → machine → log → score proof | this session | `T6ContractTest` |

ADR-0005 records the first session's decisions; this one records the second's.

## Decision

1. **The call-history reader is config-driven, not contest-driven.** One parser reads
   `callHistory` (delimiter, skip prefixes, header line, minimum fields, per-column type and
   requiredness, dedupe, the DXCC gate) and produces the `HistoryLookup` T5 left as a seam.
   The three shipped files differ only in their spec: CQ WW is positional with a numeric zone
   (`CqWW.pas:79-98`), ARRL SS is positional with an integer check and the callsign grammar
   (`ArrlSS.pas:106-126`), IARU HF is header-driven, de-duplicated and gated on the operator's
   DXCC lookup (`IaruHf.pas:114-151`).
   - A header's marker cell is **dropped before the indices are computed**: the Pascal does
     `tl.Delete(0)` ("shifts others down by one", `IaruHf.pas:117`), and without that the first
     data column is read as the second. This was the one real bug found while writing the
     tests.
   - A file with no usable record, or whose DXCC gate fails, is `HistoryLoad.Rejected` rather
     than an empty lookup: an unusable asset is a data error, not a session with no stations.
   - The callsign grammar lives in `core-data` (`Callsign`), because that is where callsign
     knowledge already lives and `ColumnType.CALLSIGN` promised "the shared callsign grammar".

2. **The call-history asset names are the real file names.** `cqww.json`/`arrlss.json` named
   `CQWWCW.TXT`/`SSCW.TXT`, which Windows resolves case-insensitively but Android assets do
   not. They now name `CQWWCW.txt`/`SSCW.txt`. T3's `ContestLoaderTest` string-replaces the
   literal, so its fixture was updated with the rename — a test-only edit, recorded in the
   commit.

3. **`DxOperator` is a faithful port with two deliberate omissions.**
   - Kept: the eight states and every transition, `Patience`/ghosting, `MorePatience`,
     `RepeatCnt`, `GetSendDelay`/`GetReplyTimeout`, the callsign matcher (wildcard, Levenshtein,
     substring, confidence) and the LID accept/reject dice. The matcher is pinned by the
     upstream's own 180-case golden table (`Test/DxOperTest.pas`).
   - Omitted: the `rmHst` branches (HST is out of Milestone A; T10–T20 owns it) and
     `GetWpm`/`GetNR`, because the roster already draws speed and serial (T5) and a second
     source of truth would drift.
   - Timing is expressed in **audio blocks with an explicit clock** (`AudioBlocks`, upstream
     `11025 Hz / 512`), so the constant is honest when the Android engine runs at 48 kHz.
   - Randomness comes from the caller's `SeededRandom` in upstream's order, including the two
     draws that happen only under a condition (the repeat count and the `NR?` roll). Delphi's
     short-circuit `or` is preserved; `Round` is half-to-even, like `Math.rint`.

4. **The roster→engine adapter lives in `core-qso`, refining ADR-0005 §3.**
   `RosterStation.asTruth()` is two fields (the call and the two sent pieces). ADR-0005 §3
   planned for `app` to do it; keeping it in `core-qso` (which already depends on
   `core-contest`) is what lets a JVM test prove the whole chain without an APK. It adds no
   module edge and the engine's machine still knows nothing about the roster.

## Consequences

+ The "one QSO can be worked and scored" claim is now tested end to end with the real CQ WW and
  ARRL SS documents: CQ → roster station → operator answers → `Enter` thrice → `TU` → verdict →
  3 points × 2 multipliers for CQ WW, 2 points × 1 section for SS.
+ A mis-copied RST is logged with its `RST` verdict and counts raw only; a non-matching call is
  a `CALL` verdict with the correction; an unworked contact is `NIL`; a repeated call is `DUP`.
+ The operator's timing is testable without audio, and a future 48 kHz engine passes its own
  `AudioBlocks`.
− Two engines now own station facts: the roster (speed/pitch/serial) and the operator
  (patience/replies). That is the upstream split (`DxStn` vs `DxOper`), but a reader must not
  move a fact from one to the other without a ticket.
− The per-field truth (`QsoTruth.fields`) is still unfilled by the roster adapter, so verdicts
  are piece-level for a two-piece station (ADR-0005 §6, finding F4). The mapping from
  `their<Group>` to config field keys is the remaining seam.

## Rejected

- **Parsing call histories inside each contest's code** (as the Pascal does): twelve loaders
  would drift; the schema already declares the shape, so one reader + three documents is the
  ADR-0002 answer.
- **Copying `Test/DxOperTest.pas`'s table at test runtime**: it would skip without `upstream/`,
  and a skipped test is not evidence (AGENTS.md).
- **Keeping `DxOperator` in `core-contest`**: it is the *operator's* counterpart, and the QSO
  engine owns it (spec §3). It needs no contest document, so it belongs with the engine.
- **Hard-coding upstream's sample rate into the delays**: the Android audio engine's block size
  is T4's decision, not T6's.
