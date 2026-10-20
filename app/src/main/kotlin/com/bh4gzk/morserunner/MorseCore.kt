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

    /** Station panel settings: 0 = CW speed, 1 = pitch Hz, 2 = bandwidth Hz, 3 = QSK. */
    external fun setSetting(handle: Long, kind: Int, value: Int)

    external fun getSetting(kind: Int): Int

    /**
     * One of the three status captions the core's game loop writes into the desktop form:
     * 0 = the QSO rate (`n  qso/hr.`), 1 = the run clock (`hh:nn:ss`), 2 = the Pile-Up caption
     * (`Pile-Up:  n`). Null when the index is out of range.
     */
    external fun statusText(which: Int): String?

    /** The DX call the operator copied into the Call field. */
    external fun setHisCall(handle: Long, text: String)

    /** Directory holding the bundled data files (DXCC.LIST and the call histories). */
    external fun setDataDir(path: String)

    /** How many DX stations are calling right now (a count, not a decode). */
    external fun stationCount(handle: Long): Int

    /** The call of station [idx] as the simulation knows it (the probe drives QSOs with it). */
    external fun stationCall(handle: Long, idx: Int): String?

    /** One exchange field of station [idx]: 0 = Exch1, 1 = Exch2, 2 = its speed. Harness only. */
    external fun stationExch(handle: Long, idx: Int, which: Int): String?

    /** Monitor level 0..1, the Main.dfm VolumeSlider1 (default 0.75 = -15 dB). */
    external fun setMonitorLevel(handle: Long, value: Float)

    /** Type into the form's three edit boxes: call, exchange 1, exchange 2. */
    external fun setEntry(handle: Long, call: String, exch1: String, exch2: String)

    /** Press Enter on the form: append the QSO to the log. Returns the new log length. */
    external fun saveQso(handle: Long): Int

    /**
     * The last exception the core caught, as `where: ClassName: Message`, or "" when none.
     * A Pascal exception that reached a JNI frame would end the process instead, so the core
     * catches, returns a negative code and leaves the reason here.
     */
    external fun lastError(): String?

    /** Diagnostics for the harness: 0 stations, 1 log rows, 2 run mode, 3 operator state, 4 block,
     *  5 last message ordinal, 6 monitor level (x1000), 7 my envelope length, 8 my send position,
     *  9 completed operator messages. */
    external fun debugState(handle: Long, which: Int): Int
}
