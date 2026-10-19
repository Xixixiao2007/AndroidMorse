package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.RosterStation

/**
 * The roster's station as the engine's save-time truth.
 *
 * ADR-0005 §3 made [QsoTruth] the seam between "a station was generated" (`:core-contest`) and
 * "a contact is judged" (`:core-qso`); the plan was for `app` to do the mapping. It lives here
 * instead because the mapping is exactly the station's call and its two sent pieces, and
 * because a JVM test has to be able to prove the whole chain
 * (`config → call history → roster → operator → machine → log → score`) without an APK.
 * `core-qso` already depends on `core-contest` for the contest document and `SeededRandom`, so
 * this adds no module edge.
 *
 * The per-field truth is deliberately **not** filled in: [RosterStation.exchange] carries the
 * `their<Group>` identifiers that *scoring* reads, while [QsoTruth.fields] speaks the config's
 * field keys (`check`, `section`). Mapping one to the other is a roster/generator concern
 * (ADR-0005 §6 and finding F4); until it exists the verdict is piece-level, which is what the
 * shipped two-piece exchanges need.
 */
fun RosterStation.asTruth(): QsoTruth = QsoTruth(
    call = call,
    exch1 = exchange.exch1,
    exch2 = exchange.exch2,
)
