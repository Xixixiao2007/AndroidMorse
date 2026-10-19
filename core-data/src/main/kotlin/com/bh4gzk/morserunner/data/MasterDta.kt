package com.bh4gzk.morserunner.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The callsign pool, parsed from `MASTER.DTA` (spec §9).
 *
 * Layout transcribed from upstream `CallLst.pas:40-105`, which is not a text file:
 *
 *  - the first `INDEX_BYTES` bytes are `ALPHABET.length² + 1` little-endian 32-bit
 *    offsets (a two-character prefix index: 37 letters/digits/`/`, squared, plus a
 *    sentinel). Upstream uses it only as an integrity header and to binary-search
 *    by prefix;
 *  - the rest is a pool of NUL-terminated ASCII callsigns;
 *  - a record starting with `VER2` is a version banner, not a callsign;
 *  - upstream sorts the pool and drops duplicates (`CallLst.pas:93-96`).
 *
 * Integrity is checked the way upstream checks it (`CallLst.pas:69-70`):
 * `index[0]` must equal the index size, and the last entry must equal the file
 * size. Upstream *silently* produces an empty pool on mismatch; [parse] throws
 * instead, because a broken asset should fail the build rather than look like a
 * contest with no stations. [parseOrNull] keeps upstream's forgiving behaviour for
 * callers that need it.
 */
class MasterDta private constructor(

    /** Callsigns, ordinal-sorted and deduplicated, exactly as upstream leaves them. */
    val calls: List<String>,
) : CallPool {

    override val size: Int get() = calls.size

    val isEmpty: Boolean get() = calls.isEmpty()

    /** True when [call] is in the pool. Ordinal, case-sensitive, like upstream `StrComp`. */
    fun contains(call: String): Boolean = calls.binarySearch(call) >= 0

    /** The callsign at [index]; lets a caller pick with its own seeded random source. */
    override fun callAt(index: Int): String = calls[index]

    override fun toString(): String = "MasterDta(${calls.size} calls)"

    companion object {

        /** Characters the prefix index is built from (`CallLst.pas:42`). */
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/"

        /** `Sqr(CHRCOUNT) + 1` (`CallLst.pas:44`). */
        const val INDEX_ENTRIES = ALPHABET.length * ALPHABET.length + 1

        /** `INDEXSIZE * SizeOf(Integer)` (`CallLst.pas:45`) = 5480 bytes. */
        const val INDEX_BYTES = INDEX_ENTRIES * 4

        /** Version banner prefix (`CallLst.pas:88`). */
        private const val VERSION_PREFIX = "VER2"

        /**
         * Parses the whole file.
         *
         * @throws IllegalArgumentException when the file is too short or the index
         *   integrity check fails, naming the observed and expected values.
         */
        fun parse(bytes: ByteArray): MasterDta {
            require(bytes.size >= INDEX_BYTES) {
                "MASTER.DTA is too short: ${bytes.size} bytes, need at least $INDEX_BYTES"
            }
            val index = ByteBuffer.wrap(bytes, 0, INDEX_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer()
            val firstEntry = index.get(0)
            val lastEntry = index.get(INDEX_ENTRIES - 1)
            require(firstEntry == INDEX_BYTES) {
                "MASTER.DTA index[0] is $firstEntry, expected $INDEX_BYTES"
            }
            require(lastEntry == bytes.size) {
                "MASTER.DTA last index entry is $lastEntry, expected the file size ${bytes.size}"
            }

            val pool = ArrayList<String>(200_000)
            var start = INDEX_BYTES
            var cursor = INDEX_BYTES
            while (cursor < bytes.size) {
                if (bytes[cursor].toInt() == 0) {
                    if (cursor > start) {
                        val record = String(bytes, start, cursor - start, Charsets.US_ASCII)
                        if (!record.startsWith(VERSION_PREFIX)) pool.add(record)
                    }
                    start = cursor + 1
                }
                cursor++
            }
            // A trailing record without a terminator is discarded, as upstream does
            // with PChar walking that stops at StrLen.

            pool.sort()
            val unique = ArrayList<String>(pool.size)
            var previous: String? = null
            for (call in pool) {
                if (call != previous) {
                    unique.add(call)
                    previous = call
                }
            }
            return MasterDta(unique)
        }

        /** Like [parse] but returns `null` where upstream would silently give up. */
        fun parseOrNull(bytes: ByteArray): MasterDta? = try {
            parse(bytes)
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }
}
