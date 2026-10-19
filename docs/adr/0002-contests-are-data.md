# ADR-0002 — Contests are data, not code

- Status: accepted (human, 2026-10-19)
- Deciders: human (BH4GZK), agent

## Context
Upstream implements each contest as a Pascal class (`Contest.pas`, `CqWW.pas`, `ArrlSS.pas`,
`IaruHf.pas`, `NaQp.pas`, `ACAG.pas`, `ALLJA.pas`, `CWSST.pas`, `CWOPS.pas`, `ArrlDx.pas`,
`ArrlFd.pas`, `CqWpx.pas`, `SerNRGen.pas`). That is ~5.5k lines of contest-specific logic.
The human's instruction: *"比赛以配置文件形式导入 … 按 12 个比赛为例，保留里面所有的东西"* —
contests must be loadable configuration and no feature may be dropped.

## Decision
A **contest = a configuration document** (versioned JSON) interpreted by one generic engine.
All 12 upstream contests ship as bundled configs; the same mechanism can import a user file.
Procedural parts are expressed with a **bounded expression language** (ADR-0003), not code.

## Consequences
+ Adding or fixing a contest is a data change; users can import their own contest files.
+ One engine to test, one place where QSO rules live.
− The schema must be expressive enough for the hardest contests (ARRL SS, IARU, HST);
  getting v1 right is a design cost paid up front.
− Every upstream contest must be re-expressed and behaviourally verified against
  `Readme.txt` contest descriptions and `Test/*.pas`.

## Rejected
- Hard-coded Kotlin contest classes (fastest to start, but contradicts the instruction and
  makes 12 contests 12 code paths).
- Scripting runtime (Lua/JS) for behaviours — dependency, size and Android risk.
