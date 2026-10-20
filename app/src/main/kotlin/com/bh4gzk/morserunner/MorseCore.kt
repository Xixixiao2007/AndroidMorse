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
}
