# Morse Runner → Android

> Status: **building** — grilling complete, spec approved, Milestone A in progress
> (T1–T2 shipped; T3 next).
> Facts below are verified against the upstream source or measured on this machine, not assumed.

## Verified facts — recon 2026-10-19

### The reference program (what we are porting)
- Desktop copy: `D:\Users\BH4GZK\Desktop\Morse Runner 1.85.4\`
  — `MorseRunner.exe` (PE32, x86, GUI), contest data (`MASTER.DTA`, `*.txt`), `MorseRunner.ini`.
- Identity: **Morse Runner Community Edition (MRCE) v1.85.4** (May 2026) — see `upstream/MorseRunnerCE/Readme.txt:333`.
  Current development line is `1.86-dev`.
- Original author: Alex Shovkoplyas, VE3NEA (2004–2016). Community Edition maintainers since 2022.
- Upstream source: <https://github.com/w7sst/MorseRunner> (this is the maintained repo; `mbridak/MorseRunner` is a mirror).
  License **MPL-2.0**. Local clone: `upstream/MorseRunnerCE` at `2dd6f5c`.
- Stack: **Delphi / Pascal, VCL** — upstream states Windows-only. ≈13.1k LOC, 31 `.pas` + `.dpr`.
- Contests: ARRL DX, ARRL Field Day, ARRL Sweepstakes, CQ WPX, CQ WW, IARU HF, NAQP,
  JARL ALL JA, JARL ACAG, CWOPS CWT, K1USN SST, HST.
- Domain modules: `Contest.pas` (contest/exchange rules, 992), `DxOper.pas` (749),
  `Main.pas` (UI + QSO state machine, 2783), `Station.pas`, `DxStn/MyStn`, `Qrn/Qsb/Qrm`,
  `Log.pas` (log + scoring, 1172), `Ini.pas`, `CallLst/DXCC/SerNRGen`,
  `VCL/*` (CW audio synthesis: `MorseTbl`, `SndOut`, `SndCustm`, `Mixers`, `WavFile`).
- Unit tests exist: `upstream/MorseRunnerCE/Test/*.pas` (callsign utils, DX oper, DXCC list, lexers, exchange parsers).

### CW audio timing — transcribed, NOT textbook (verified in `VCL/`)
The port must not use textbook PARIS numbers; Morse Runner's keyer differs deliberately:
- One unit is `60/48/WPM` s = **1250/WPM ms** (`MorseKey.pas:249`, comment "48U = 1 word w/ 5U
  inter-word space"). Textbook is `1200/WPM`; the word still lasts `60/WPM` s, so the *speed is
  correct* while elements run ~4 % long.
- Gaps (`MorseKey.pas:243-245`): **3U** inter-character, **5U** inter-word (not the textbook 7U),
  **4U** inter-message.
- Farnsworth (`FarnsKeyer.pas:292-293`; contests K1USN SST and CWops only, gated by
  `Contest.pas:200 IsFarnsworthAllowed`, character rate default 25 from `Ini.pas:271`):
  `DelayPerWord = 60/WpmS − 31·60/WpmC/48` seconds, stretched gap unit = `DelayPerWord/17`;
  elements stay at `WpmC`. Invariant: `31 units @ WpmC + 17 units @ WpmS = 60/WpmS` s per word.
- Envelope ramp is Blackman-Harris over `2.7 × riseTime × Rate` samples (`MorseKey.pas:150-154`),
  not a plain cosine.

### Interaction model today (PC — the thing to adapt)
- 12 function keys send canned messages: F1 CQ · F2 exchange · F3 TU · F4 my call ·
  F5 his call · F6 B4 · F7 "?" · F8 NIL · F11 wipe · F12 NR?
- `Enter` sends the state-dependent message; `Space` auto-completes/advances fields;
  `Esc` stops sending. Typed fields: Call / RST / CQ-Zone / Exchange.
- RIT ±500 Hz (arrow keys / wheel), RX bandwidth (left/right), keying speed (PgUp/PgDn).
- Run modes: Single Call, Pile-Up, WPX / HST competition. Duration 1–60 min.
  Score raw + verified; 5-minute rate histogram.

### Local toolchain (measured on this machine)
- Present: Node v24.19.0, npm 11.17.0, Python 3.14.7, .NET 9.0.302, `adb` 1.0.41 (`C:\WINDOWS\adb.exe`), git 2.50.1.
- **Absent**: Android SDK (`ANDROID_HOME` empty; no SDK dir), Gradle, `javac` (only JRE 1.8), Flutter/Dart, FPC/Lazarus, Delphi.
- No Android device attached (`adb devices` → empty).
- Network: direct `https://github.com` blocked; proxy `127.0.0.1:7892` works
  (git already configured for it). `codeload.github.com` and `api.github.com` reachable directly.

## Decisions (human-confirmed, 2026-10-19)

| # | Decision | ADR |
|---|---|---|
| Q1 | **Native Android app** — Kotlin + Jetpack Compose, real APK | [0001](docs/adr/0001-native-android-kotlin-compose.md) |
| Q2 | **Full function-key mapping on touch** — 8 canned-message buttons in 2 rows × 4 columns, call entry with autocomplete; physical F1–F12 mapped too | — |
| Q3 | **Contests are configuration documents**, imported at runtime; all 12 upstream contests preserved with no feature dropped | [0002](docs/adr/0002-contests-are-data.md) |
| Q4 | **Bounded expression language** for contest behaviour (no loops/IO/reflection) | [0003](docs/adr/0003-bounded-expression-language.md) |

Spec: [`docs/spec/mr-android-spec.md`](docs/spec/mr-android-spec.md) — status: **approved** (Q5).

## Glossary

Definitions verified against the upstream source; where a term has two senses in MR, both are
listed, because confusing them is how logging bugs start.

- **Contest** — the rule set one session runs under: exchange fields, validation, points,
  multipliers, call-history source, message wording, capability flags. Upstream subclasses
  `TContest` once per contest (`CqWW.pas`, `ArrlSS.pas`, `IaruHf.pas`, …); here it is a JSON
  document loaded at runtime (ADR-0002).
- **Exchange** — the information that completes a contact. MR always has **two** pieces,
  `Exch1` and `Exch2` (e.g. RST + zone). A contest may let the operator type them as one string
  and split it (ARRL SS: `[nr] prec chk sect`), which is why the schema has an `entryParse` step.
- **Message** — one canned transmission, upstream `TStationMessage` (`Station.pas:19-22`):
  `msgCQ`, `msgNR`, `msgTU`, `msgMyCall`, `msgHisCall`, `msgB4`, `msgQm`, `msgNil`,
  `msgGarbage`, `msgLongCQ`, `msgAgn`, … The eight touch buttons and the F1–F12 keys send these.
- **QSO state** — the step of the contact the operator is in (idle, calling CQ, waiting for a
  call, sending exchange, waiting for exchange, logged). Not to be confused with **station
  state** (`Station.pas:26-36`: `stListening`, `stCopying`, `stPreparingToSend`, `stSending`),
  which describes the *audio* pipeline of one station.
- **DX station** — a simulated station being worked (upstream `TDxStation`): callsign, exchange,
  speed, pitch, level, fade, behaviour profile. **My station** (`TMyStation`) is the operator's
  own side, which is also synthesised so it can be monitored.
- **Run mode** — how the simulation behaves, not which contest: single call, pile-up, or
  competition (WPX / HST), per spec §6.
- **LID** — a deliberately badly-behaved simulated operator. Upstream's definition is about
  callsign handling: a LID *rejects a correct call* and *accepts a wrong one without correcting
  a partial call* (`DxOper.pas:514-521`). It is a behaviour profile, not an error in our code.
- **Multiplier** — a distinct value (zone, country, section, prefix) that multiplies total
  points. One contact may contribute several: upstream stores a `;`-separated `MultStr` and
  splits it into a deduplicated list (`Log.pas:250-253`).
- **NIL** — two senses: (a) as a *message*, "no station here / not heard" (`msgNil`); (b) as a
  *log verdict*, a contact that is not valid (`leNIL`, `Log.pas:48`) — what `QsoLog` returns when
  the callsign is empty.
- **RST** — signal report (readability, strength, tone), sent `599`/`5NN` in CW contests.
- **CQ zone / ITU zone** — index numbers identifying location. A DXCC row may hold **several**
  zones (`3,4,5` for the USA), so a single zone for scoring comes from the call-history file,
  as upstream does (`CqWW.pas:280`).
- **Serial number** — the running contact number some contests exchange (`#` in the config).
- **Prefix** — the leading part of a callsign identifying the entity. Two extraction modes:
  WPX (prefix + district digit, e.g. `DL2`) and DXCC (longest prefix, e.g. `DL2XYZ`); they are
  not interchangeable — `RC2FX` is Kaliningrad as `RC2F` but European Russia as `RC2`.
- **Call-history file** — per-contest text file mapping callsigns to exchange values (zone,
  section, check). Formats differ: `CQWWCW.TXT` is headerless positional, `IARU_HF.txt` is
  header-driven. See `docs/contests/exchange-facts.md`.
- **Dice** — our term (ADR-0004) for the seeded random numbers a contest config declares as
  inputs (`their.dice`). Evaluation stays deterministic and replayable; the language has no
  `random()`.


