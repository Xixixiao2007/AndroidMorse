package com.bh4gzk.morserunner.contest

/**
 * The **two-pass club load** of ARRL Field Day (`ArrlFd.pas:192-383`), the one history whose
 * pool is an algorithm rather than a row predicate (ADR-0018).
 *
 * The upstream file is one row per station: `Call,Class,Section,Club` (`FDGOTA.txt`). Upstream
 * builds its pool in two passes because a club can be represented twice — once by a club station
 * (class suffix `A`/`C`/`F`) and once by home/portable members (`D`/`E`/`B`) — and a club's score
 * must not double-count:
 *
 * - **Pass 1** (`ArrlFd.pas:222-306`) adds every row as it is read, except home/portable rows.
 *   A club station whose club already owns a station replaces it only when its **transmitter
 *   count is strictly greater** (`TxCnt`, the leading digits of the class). A home/portable row
 *   **without** a club name is kept with probability `0.25`. A home/portable row **with** a club
 *   name is deferred to pass 2.
 * - **Pass 2** (`ArrlFd.pas:308-350`) walks the deferred clubs in file order. A club already
 *   occupied in pass 1 has its whole group thrown away. Otherwise exactly one row is picked from
 *   the group's **top transmitter-count segment** (`PickPendingCallIdx`, `:130-160`), and kept if
 *   its class ends in `B` or with probability `0.25`.
 * - The final pool is sorted by call (`FdCallList.Sort`, `:349`).
 *
 * **The randomness (ADR-0018 §3).** Upstream calls the unseeded, process-global `Random`, which
 * ADR-0007 cannot replay. This port turns every draw into a *pure function of the session seed and
 * the decision's identity* — [draw] — so the same seed replays the same pool byte for byte, and a
 * decision does not move when an unrelated row is added to the file. The purpose tags
 * ([KEEP_DRAW], [PICK_DRAW]) are part of that contract: renaming one changes every replay.
 */
internal object TwoPassClubLoad {

    /** The column the strategy groups deferred rows by; declared by the config, never invented. */
    internal const val CLUB_COLUMN: String = "club"

    /** The column that carries the class, whose leading digits are the transmitter count. */
    internal const val CLASS_COLUMN: String = "class"

    /** The row's own callsign, the pool's key. */
    private const val CALL_COLUMN: String = "call"

    /** Home stations: `EndsWith('D') or EndsWith('E')` (`ArrlFd.pas:253`). */
    private val HOME_SUFFIXES = setOf('D', 'E')

    /** Portable stations: `EndsWith('B')` (`ArrlFd.pas:254`) — always kept in pass 2 (`:344`). */
    private const val PORTABLE_SUFFIX = 'B'

    /** `Random < 0.25` (`ArrlFd.pas:262`, `:344`). */
    private const val KEEP_CHANCE = 0.25

    /** The draw that keeps an eligible row, one in four (`ArrlFd.pas:262,344`). */
    internal const val KEEP_DRAW: String = "keep"

    /** The draw that picks one row from a deferred club's top transmitter-count segment (`:130-160`). */
    internal const val PICK_DRAW: String = "pick"

    /**
     * Runs the two passes over [rows] (already parsed and filtered, in file order) and returns the
     * pool keyed by call, **in call order** (`ArrlFd.pas:349`). An empty result is not a contest:
     * [CallHistoryFile.parse] turns it into [HistoryLoad.Rejected].
     */
    fun apply(rows: List<Map<String, String>>, seed: Long): Map<String, Map<String, String>> {
        val pool = LinkedHashMap<String, Map<String, String>>()
        // Pass 1's `ClubNames` (`ArrlFd.pas:283-306`): the club station that owns each club.
        val clubStations = LinkedHashMap<String, Map<String, String>>()
        // Pass 2's `PendingStations` (`:295-303`), grouped by club in file order.
        val pending = LinkedHashMap<String, MutableList<Map<String, String>>>()

        // --- Pass 1: club stations in, home/portable handled or deferred -----------------
        for (row in rows) {
            val call = row[CALL_COLUMN].orEmpty()
            if (call.isEmpty()) continue
            val stnClass = row[CLASS_COLUMN].orEmpty()
            if (stnClass.isEmpty()) continue
            val club = row[CLUB_COLUMN].orEmpty()
            val suffix = stnClass.last().uppercaseChar()

            if (suffix in HOME_SUFFIXES || suffix == PORTABLE_SUFFIX) {
                if (club.isEmpty()) {
                    // `ArrlFd.pas:262-276`: a home/portable station with no club is admitted one
                    // time in four; otherwise it is dropped for this session.
                    if (draw(seed, KEEP_DRAW, call) < KEEP_CHANCE) pool[call] = row
                } else {
                    // `ArrlFd.pas:295-303`: keep it for pass 2, grouped by club name.
                    pending.getOrPut(club) { mutableListOf() }.add(row)
                }
                continue
            }

            // `ArrlFd.pas:283-306`: a club station. Upstream's `else` covers every suffix that is
            // not home/portable, so the shipped `A`/`C`/`F` are not special-cased here either.
            if (club.isNotEmpty()) {
                val existing = clubStations[club]
                if (existing != null) {
                    if (transmitterCount(stnClass) > transmitterCount(existing[CLASS_COLUMN].orEmpty())) {
                        pool.remove(existing[CALL_COLUMN].orEmpty())
                        pool[call] = row
                        clubStations[club] = row
                    }
                    // A smaller (or equal) station of the same club is dropped.
                    continue
                }
                clubStations[club] = row
            }
            pool[call] = row
        }

        // --- Pass 2: one station per club that pass 1 did not occupy ---------------------
        for ((club, group) in pending) {
            if (club in clubStations) continue // `ArrlFd.pas:315-317`: the whole group is dropped.
            val picked = pickPending(group, draw(seed, PICK_DRAW, club))
            val pickedClass = picked[CLASS_COLUMN].orEmpty()
            if (pickedClass.endsWith("B", ignoreCase = true) ||
                draw(seed, KEEP_DRAW, picked[CALL_COLUMN].orEmpty()) < KEEP_CHANCE
            ) {
                val call = picked[CALL_COLUMN].orEmpty()
                pool[call] = picked
                clubStations[club] = picked
            }
        }

        // `ArrlFd.pas:349 FdCallList.Sort`: the pool's order is the call order, not the file order.
        return pool.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to it.value }
    }

    /**
     * `TPendingClubCalls.PickPendingCallIdx` (`ArrlFd.pas:130-160`): sort by transmitter count
     * descending, take the segment of the highest count, and pick uniformly inside it. Upstream's
     * `Random(EndIdx - BeginIdx)` is [draw] here, and the segment keeps the group's file order.
     */
    private fun pickPending(group: List<Map<String, String>>, draw: Double): Map<String, String> {
        val highest = group.maxOf { transmitterCount(it[CLASS_COLUMN].orEmpty()) }
        val segment = group.filter { transmitterCount(it[CLASS_COLUMN].orEmpty()) == highest }
        if (segment.size == 1) return segment.single()
        val index = (draw * segment.size).toInt().coerceIn(0, segment.size - 1)
        return segment[index]
    }

    /**
     * `TFdCallRec.GetTxCnt` (`ArrlFd.pas:631-639`): the leading digits of the class, so `3A` is
     * three transmitters and `12A` is twelve. A class without digits reads as zero — upstream
     * would raise on `ToInteger('')`, and no shipped row reaches that.
     */
    private fun transmitterCount(stnClass: String): Int {
        var digits = 0
        while (digits < stnClass.length && stnClass[digits] in '0'..'9') digits++
        return stnClass.take(digits).toIntOrNull() ?: 0
    }

    /**
     * One declared draw, as a pure function of the session [seed], a [purpose] tag and the
     * decision's [identity] (a callsign, or a club name for the pick).
     *
     * Upstream uses the process-global `Random`; ADR-0007 requires every random decision to be
     * declared and replayable. A hash of the identity into the seed gives both: the same seed is
     * the same pool, and the draw is stable under file edits, which a consumed stream would not
     * be. The SplitMix64 step is [SeededRandom]'s, so the distribution is the session's usual one.
     */
    internal fun draw(seed: Long, purpose: String, identity: String): Double {
        // FNV-1a over `purpose NUL identity`, so the two purposes can never collide.
        var hash = FNV_OFFSET_BASIS
        for (character in purpose + '\u0000' + identity) {
            hash = (hash xor character.code.toLong()) * FNV_PRIME
        }
        return SeededRandom(seed xor hash).nextDouble()
    }

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xCBF29CE484222325

    private const val FNV_PRIME: Long = 1099511628211L // 0x100000001B3
}
