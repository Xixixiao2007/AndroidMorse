package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.MasterDta
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A real-layout `MASTER.DTA` holding exactly [calls] — the same 5480-byte index
 * header plus NUL-terminated ASCII records that the real asset uses, so the
 * roster tests exercise `MasterDta.parse` rather than a bypass.
 */
internal fun masterDtaOf(vararg calls: String): MasterDta {
    val body = calls.joinToString(separator = "") { "$it\u0000" }.toByteArray(Charsets.US_ASCII)
    val size = MasterDta.INDEX_BYTES + body.size
    val bytes = ByteArray(size)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val index = IntArray(MasterDta.INDEX_ENTRIES)
    index[0] = MasterDta.INDEX_BYTES
    index[MasterDta.INDEX_ENTRIES - 1] = size
    index.forEach { buffer.putInt(it) }
    body.copyInto(bytes, MasterDta.INDEX_BYTES)
    return MasterDta.parse(bytes)
}
