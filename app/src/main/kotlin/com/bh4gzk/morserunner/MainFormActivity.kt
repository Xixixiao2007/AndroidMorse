package com.bh4gzk.morserunner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bh4gzk.morserunner.audio.ContestAudio
import com.bh4gzk.morserunner.ui.MainFormScreen

/**
 * The phone UI, built as a reproduction of the original Windows form — now wired to the Pascal
 * contest core.
 *
 * It hosts [MainFormScreen], a scaled copy of `upstream/MorseRunnerCE/Main.dfm` (form
 * `TMainForm`, 729 x 506) and `ScoreDlg.dfm`: every visible design-time control with the original
 * captions and the original relative arrangement, scaled by one constant and stacked where a
 * portrait phone cannot hold the landscape bands side by side.
 *
 * The wiring itself lives in [MainFormController]: the Run button starts a run in the core and the
 * audio thread, the F1..F8 buttons send `TStationMessage` ordinals, the clock comes from the
 * blocks the audio thread has pulled, and the log / score are read back from the core. The
 * activity owns the parts that belong to the hosting process — copying the bundled data files
 * into `filesDir`, pointing the core at them, the main-thread poll, and stopping/resuming the
 * audio with `onStart`/`onStop` so a backgrounded app does not keep playing.
 *
 * Deliberately absent: any decoded "received" text. Morse Runner makes the operator copy by ear;
 * the only decoder upstream (`DebugCwDecoder`) is off by default and writes to the status bar.
 */
class MainFormActivity : ComponentActivity() {

    private lateinit var controller: MainFormController

    private val poller = Handler(Looper.getMainLooper())

    private val pollTask = object : Runnable {
        override fun run() {
            controller.poll()
            poller.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BundledData.install(this)
        controller = MainFormController()
        setContent { MainFormScreen(controller) }
    }

    override fun onStart() {
        super.onStart()
        if (!::controller.isInitialized) return
        // A run that was live when the app went to the background picks up where it stopped.
        controller.onHostStarted()
        poller.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    override fun onStop() {
        poller.removeCallbacks(pollTask)
        if (::controller.isInitialized) controller.onHostStopped()
        super.onStop()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.dispose()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainForm"

        /** Five times a second: the brief's "a few times per second", and cheap (see [MainFormController.poll]). */
        const val POLL_INTERVAL_MS = 200L
    }
}

/**
 * Everything the form needs from the contest core, in one place.
 *
 * The core is a process-wide singleton (the original's `Tst` global): there is one contest at a
 * time, so this object owns the one handle. `MorseCore.block` must only be called from the audio
 * thread inside [ContestAudio]; every method here is called from the main thread and either
 * starts/stops that thread or calls one of the core's cheap query functions.
 *
 * State is Compose state, so the screen recomposes only the cells that changed: the clock when a
 * second passes, the log when the core logs a QSO, the score when it moves.
 */
class MainFormController(private val audio: ContestAudio = ContestAudio()) {

    /** The live contest handle; 0 means "no run". */
    var handle by mutableLongStateOf(0L)
        private set

    /** True while the audio thread is pulling blocks for [handle]. */
    var running by mutableStateOf(false)
        private set

    /** True from the first Run on: the form then uses its run-time geometry (the tall QSO log). */
    var started by mutableStateOf(false)
        private set

    /** Run clock, derived from the samples the core has produced (`BlocksToSeconds`). */
    var elapsedSeconds by mutableLongStateOf(0L)
        private set

    /**
     * The three status captions the core's game loop writes into the desktop form
     * (`Contest.pas:839-842`, `Log.pas ShowRate`): the QSO rate (`n  qso/hr.`), the run clock
     * (`hh:nn:ss`) and the Pile-Up caption (`Pile-Up:  n`). Read back verbatim through
     * [MorseCore.statusText] — the rate is computed by the original `ShowRate`, not re-derived
     * here, and the clock is the core's `BlockNumber`, not the player's sample count.
     */
    var rateText by mutableStateOf("")
        private set
    var clockText by mutableStateOf("")
        private set
    var pileUpText by mutableStateOf("")
        private set

    /** The QSO log, one list of the seven columns per row, oldest first (ListView2 order). */
    var logRows by mutableStateOf(emptyList<List<String>>())
        private set

    var rawPoints by mutableIntStateOf(0)
        private set
    var verifiedPoints by mutableIntStateOf(0)
        private set
    var rawMultipliers by mutableIntStateOf(0)
        private set
    var verifiedMultipliers by mutableIntStateOf(0)
        private set

    /** Run length in minutes (the TSpinEdit2 next to Run; Main.dfm MinValue 1, MaxValue 240). */
    var runMinutes by mutableIntStateOf(DEFAULT_RUN_MINUTES)
        private set

    /** Log rows already copied out of the core; the poll only rebuilds the list when this moves. */
    private var loggedRows = 0

    /** The last core failure already logged, so one exception is not repeated five times a second. */
    private var reportedCoreError = ""

    /** The last audio-thread stage logged; see [MainFormController.poll]. */
    private var reportedSection = Int.MIN_VALUE

    fun updateRunMinutes(minutes: Int) {
        runMinutes = minutes.coerceIn(MIN_RUN_MINUTES, MAX_RUN_MINUTES)
    }

    /** The Run button: start a run, or stop the live one. */
    fun toggleRun(contestIndex: Int, myCall: String) {
        if (running) stopRun() else startRun(contestIndex, myCall)
    }

    /**
     * Starts a fresh run: the core builds the contest, the log and clock reset (`Tst.BlockNumber
     * := 0`, `ListView2.Clear` in the original, Main.pas:2097-2103), then the audio thread starts
     * pulling blocks — which is also what makes the simulation advance.
     */
    fun startRun(contestIndex: Int, myCall: String) {
        if (running) return
        val call = myCall.trim().ifEmpty { DEFAULT_MY_CALL }
        val fresh = runCatching { MorseCore.runStart(contestIndex, call) }.getOrDefault(0L)
        if (fresh == 0L) {
            Log.w(TAG, "runStart($contestIndex) refused by the core")
            return
        }
        handle = fresh
        running = true
        started = true
        elapsedSeconds = 0L
        rateText = ""
        clockText = ""
        pileUpText = ""
        loggedRows = 0
        logRows = emptyList()
        rawPoints = 0
        verifiedPoints = 0
        rawMultipliers = 0
        verifiedMultipliers = 0
        audio.start(fresh, resetClock = true)
    }

    /**
     * Stops the live run. The log and score stay on screen (the original's stop branch does not
     * clear them); the next Run starts from an empty log because `mr_run_start` clears it.
     */
    fun stopRun() {
        if (!running) return
        running = false
        val deviceStopped = audio.stop()
        val current = handle
        handle = 0L
        if (deviceStopped && current != 0L) {
            runCatching { MorseCore.contestFree(current) }
        } else if (!deviceStopped) {
            // Freeing now would be a use-after-free under a live audio thread: leak the handle.
            Log.w(TAG, "audio thread did not stop; contest handle $current left allocated")
        }
    }

    /** Sends one canned message (F1..F8). The core validates the ordinal; 0 is never sent. */
    fun send(message: Int) {
        val current = handle
        if (!running || current == 0L) return
        if (message !in MIN_MESSAGE..MAX_MESSAGE) return
        runCatching { MorseCore.send(current, message) }
    }

    /** The operator typed the DX call into the Call field: hand it to the core's station. */
    fun setHisCall(text: String) {
        val current = handle
        if (current == 0L) return
        runCatching { MorseCore.setHisCall(current, text) }
    }

    /**
     * The operator typed the three entry fields (Main.dfm Edit1..3: the copied call, the report
     * and the exchange). `Log.SaveQso` reads exactly these, so the core has to have them.
     */
    fun setEntry(call: String, exch1: String, exch2: String) {
        val current = handle
        if (current == 0L) return
        runCatching { MorseCore.setEntry(current, call, exch1, exch2) }
    }

    /**
     * The Mon. Level slider (Main.dfm VolumeSlider1, 0.75 = -15 dB): the self-monitor gain of the
     * operator's own signal. It is a contest setting, not the phone's media volume.
     */
    /**
     * Station panel: 0 = CW speed, 1 = pitch, 2 = bandwidth, 3 = QSK. Returns what the core kept
     * (`mr_get_setting`), so a control that does nothing shows up in logcat as value -> same value.
     */
    fun setSetting(kind: Int, value: Int): Int {
        // A zero handle is fine: speed/pitch/QSK live in the core's Ini and bandwidth is re-derived
        // when the next run is created. Refusing here is what made the panel look dead before a run.
        runCatching { MorseCore.setSetting(handle, kind, value) }
        val kept = runCatching { MorseCore.getSetting(kind) }.getOrDefault(-1)
        Log.i("MainForm", "setting $kind=$value -> $kept")
        return kept
    }

    fun setMonitorLevel(value: Float) {
        val current = handle
        if (current == 0L) return
        runCatching { MorseCore.setMonitorLevel(current, value.coerceIn(0f, 1f)) }
    }

    /**
     * The Enter key: verify the copied call, answer with the DX call when it has not gone out,
     * send TU and append the QSO to the log (`Main.pas` FormKeyPress '.' / ProcessEnter, and
     * `mr_save_qso` on the core side). Returns the new log length, or a negative value when the
     * core refused the call or there is no run.
     */
    fun saveQso(): Int {
        val current = handle
        if (current == 0L) return -1
        val rows = runCatching { MorseCore.saveQso(current) }.getOrDefault(-1)
        if (rows > 0) Log.i(TAG, "QSO saved, log rows=$rows") else reportCoreError()
        return rows
    }

    /**
     * The core catches its own exceptions (a Pascal one that reached this frame would end the
     * process), reports them through [MorseCore.lastError] and returns a negative code. Log the
     * message once per distinct failure so a bad save is diagnosable instead of silent.
     */
    private fun reportCoreError() {
        val error = runCatching { MorseCore.lastError() }.getOrNull().orEmpty()
        if (error.isNotEmpty() && error != reportedCoreError) {
            reportedCoreError = error
            Log.e(TAG, "core reported: $error")
        }
    }

    /**
     * One main-thread poll: clock, rate, pile-up, log and score, a few times a second (they are
     * cheap reads).
     *
     * The log is re-read only when `logCount` changes; the score and the three captions only when
     * a value moves. [elapsedSeconds] counts the samples the audio thread has pulled and is used
     * for one thing only — the run-length auto-stop; the clock on screen is the core's own
     * `Panel2` (`Tst.BlockNumber`), so the two never disagree by more than the five one-sample
     * blocks `GetAudio` produces before it returns full blocks.
     */
    fun poll() {
        if (!running) return
        val elapsed = audio.samplesConsumed / audio.sampleRate
        if (elapsed != elapsedSeconds) elapsedSeconds = elapsed

        val rate = readStatus(0)
        val clock = readStatus(1)
        val pileUp = readStatus(2)
        if (rate != rateText) rateText = rate
        if (clock != clockText) clockText = clock
        if (pileUp != pileUpText) pileUpText = pileUp

        val rows = runCatching { MorseCore.logCount() }.getOrDefault(0)
        if (rows != loggedRows) {
            loggedRows = rows
            logRows = (0 until rows).map { row ->
                (0 until LOG_COLUMNS).map { column ->
                    runCatching { MorseCore.logCell(row, column) }.getOrNull().orEmpty()
                }
            }
        }

        val points = readScore(0)
        val verified = readScore(1)
        val multipliers = readScore(2)
        val verifiedMultipliersValue = readScore(3)
        if (points != rawPoints) rawPoints = points
        if (verified != verifiedPoints) verifiedPoints = verified
        if (multipliers != rawMultipliers) rawMultipliers = multipliers
        if (verifiedMultipliersValue != verifiedMultipliers) verifiedMultipliers = verifiedMultipliersValue

        if (elapsedSeconds >= runMinutes.toLong() * 60L) stopRun()

        // A failing audio block returns 0 samples (silence) rather than killing the process; this
        // is where the reason reaches logcat.
        reportCoreError()

        // The audio thread's stage, logged only when it changes: if that thread dies natively the
        // last value here is the stage it died in (there is no traceback for the Pascal frames).
        val section = runCatching { MorseCore.debugState(handle, 20) }.getOrDefault(-1)
        if (section != reportedSection) {
            reportedSection = section
            Log.i(TAG, "audio stage=$section")
        }
    }

    /** The activity became visible: resume pulling blocks if a run is still live. */
    fun onHostStarted() {
        if (running) audio.start(handle, resetClock = false)
    }

    /** The activity went away: silence the device; the run itself is not ended. */
    fun onHostStopped() {
        audio.stop()
    }

    /** The activity is going away for good: stop and release the core's contest. */
    fun dispose() {
        if (running) stopRun() else audio.stop()
    }

    private fun readScore(which: Int): Int = runCatching { MorseCore.score(which) }.getOrDefault(0)

    /** One of the core's three status captions; "" when the core has nothing to say. */
    private fun readStatus(which: Int): String =
        runCatching { MorseCore.statusText(which) }.getOrNull().orEmpty()

    private companion object {
        const val TAG = "MainForm"

        /** `TStationMessage` spans msgNone = 0 .. msgAgn = 24 (Station.pas:16-19). */
        const val MIN_MESSAGE = 1
        const val MAX_MESSAGE = 24

        const val DEFAULT_MY_CALL = "VE3NEA"
        const val DEFAULT_RUN_MINUTES = 30
        const val MIN_RUN_MINUTES = 1
        const val MAX_RUN_MINUTES = 240

        /** 0 UTC, 1 Call, 2 Recv, 3 Sent, 4 Pref, 5 Chk, 6 Wpm (`docs/abi.md`). */
        const val LOG_COLUMNS = 7
    }
}
