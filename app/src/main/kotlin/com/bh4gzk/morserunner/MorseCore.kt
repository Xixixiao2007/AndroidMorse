package com.bh4gzk.morserunner

/**
 * Thin JNI binding to the original contest core.
 *
 * The core is the unmodified Morse Runner Community Edition Pascal logic, compiled by FPC for
 * aarch64 Android with the VCL/Windows/audio edges replaced by stubs (see docs/adr/0020). It
 * exposes a small C ABI (docs/abi.md); `libmorsejni.so` is the only code that knows about JNI.
 */
object MorseCore {
    init {
        System.loadLibrary("mrcore")
        System.loadLibrary("morsejni")
    }

    /** Version banner from the Pascal side; proves the library is loaded and callable. */
    external fun version(): String

    /** Number of contest definitions compiled into the core. */
    external fun contestCount(): Int

    /** Display name of contest [idx] (null when the index is out of range). */
    external fun contestName(idx: Int): String?

    /** Config key of contest [idx] (null when the index is out of range). */
    external fun contestKey(idx: Int): String?

    /**
     * Create a contest of kind [idx] in the core and return an opaque handle (0 on failure).
     * The object lives on the native heap until [contestFree]; this is the handle every later
     * call (tick, score, log) will carry.
     */
    external fun contestStart(idx: Int): Long

    /** Class name of a live contest object — the proof that the right subclass was built. */
    external fun contestClass(handle: Long): String?

    /** Release a contest created by [contestStart]. */
    external fun contestFree(handle: Long)

    /**
     * Start a run: instantiate the contest of kind [idx], set the operator callsign and clear the
     * log. Returns an opaque handle, or 0 when the core refused.
     */
    external fun runStart(idx: Int, myCall: String): Long

    /** Pull one audio block (mono float at [rate]) into [buf]; returns the sample count. */
    external fun block(handle: Long, buf: FloatArray): Int

    /** Send a canned message by its TStationMessage ordinal (the F1..F8 buttons). */
    external fun send(handle: Long, msg: Int)

    /** Sample rate the core produces audio at. */
    external fun rate(): Int

    /** Samples per block. */
    external fun blockSize(): Int

    /** QSOs logged so far in the current run. */
    external fun logCount(): Int

    /** One log cell (0 UTC, 1 Call, 2 Recv, 3 Sent, 4 Pref, 5 Chk, 6 Wpm). */
    external fun logCell(row: Int, col: Int): String?

    /** 0 raw points, 1 verified points, 2 raw multipliers, 3 verified multipliers. */
    external fun score(which: Int): Int

    /** The DX call the operator copied into the Call field. */
    external fun setHisCall(handle: Long, text: String)

    /** Directory holding the bundled data files (DXCC.LIST and the call histories). */
    external fun setDataDir(path: String)
}
