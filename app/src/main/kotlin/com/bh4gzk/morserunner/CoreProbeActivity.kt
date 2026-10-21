package com.bh4gzk.morserunner

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Proof-of-wiring screen for route C (docs/adr/0020).
 *
 * Two kinds of evidence live here:
 *  1. on load — the version banner and the full contest list come out of `libmrcore.so`, i.e. out of
 *     the ORIGINAL Pascal code; nothing is re-implemented in Kotlin;
 *  2. on TAP — the button below asks the core for one contest at a time and shows the answer, so a
 *     real user gesture (not just a screen load) is what drives the native call.
 *
 * This is the smallest thing that can honestly claim "a UI action calls the Pascal core and gets a
 * correct value back". The full Main.dfm layout replaces this screen; the binding stays.
 */

/**
 * The contest regression sweep: every contest the core offers, driven through one QSO, so a contest
 * whose exchange types, call pool or scoring were broken by the short-code work shows up as a line
 * of evidence instead of being found by the operator hours later.
 */
private fun sweepOneContest(idx: Int): String {
    val name = MorseCore.contestName(idx) ?: "?"
    val h = MorseCore.runStart(idx, "VE3NEA")
    if (h == 0L) return "idx=$idx name=[$name] START_FAILED"
    try {
        val buf = FloatArray(MorseCore.blockSize())
        // A contest whose audio thread dies would otherwise hold the sweep in its wait loop: every
        // one gets a hard 25 s budget, and a contest that hits it reports timedOut=true.
        val deadline = System.nanoTime() + 25_000_000_000L
        fun expired() = System.nanoTime() > deadline
        var timedOut = false
        fun pull(n: Int) {
            var i = 0
            while (i < n && !expired()) {
                MorseCore.block(h, buf)
                i++
            }
            if (expired()) timedOut = true
        }
        fun sending() = MorseCore.debugState(h, 3) == 3
        fun waitDone(limit: Int = 300) {
            var k = 0
            while (sending() && k < limit && !expired()) { pull(10); k += 10 }
        }
        pull(2)
        MorseCore.send(h, 1)          // CQ
        pull(120)
        waitDone()
        var calls = 1
        while (MorseCore.stationCount(h) == 0 && calls < 8 && !expired()) {
            MorseCore.send(h, 1); calls++; pull(40); waitDone(); pull(20)
        }
        val stations = MorseCore.stationCount(h)
        val call = if (stations > 0) MorseCore.stationCall(h, 0) else null
        val e1 = if (stations > 0) MorseCore.stationExch(h, 0, 0) else null
        val e2 = if (stations > 0) MorseCore.stationExch(h, 0, 1) else null
        var saved = -1
        if (call != null) {
            MorseCore.setEntry(h, call, e1 ?: "", e2 ?: "")
            MorseCore.send(h, 2)      // the exchange
            pull(40)
            saved = MorseCore.saveQso(h)
            pull(60)
        }
        var waited = 0
        while (waited < 400 && MorseCore.score(1) == 0 && !expired()) {
            if (MorseCore.block(h, buf) > 0) waited++ else Thread.sleep(2)
        }
        return "idx=$idx name=[$name] stations=$stations call=${call ?: "-"} " +
            "exch=${e1 ?: "-"}/${e2 ?: "-"} saved=$saved rows=${MorseCore.logCount()} " +
            "pts=${MorseCore.score(0)} vpts=${MorseCore.score(1)} " +
            "rate=[${MorseCore.statusText(0) ?: "-"}] clock=[${MorseCore.statusText(1) ?: "-"}] " +
            "pile=[${MorseCore.statusText(2) ?: "-"}] timedOut=$timedOut err=[${MorseCore.lastError() ?: ""}]"
    } finally {
        MorseCore.contestFree(h)
    }
}

private const val SWEEP_TAG = "MorseCoreSweep"

class CoreProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A run without the call history has no callsigns to put on the air: DXCC.LIST and the
        // contest histories are the same files the form installs.
        BundledData.install(this)
        // `adb shell am start -n <pkg>/.CoreProbeActivity --ez sweep true` runs every contest once
        // and logs a line per contest under the MorseCoreSweep tag.
        if (intent?.getBooleanExtra("sweep", false) == true) {
            Thread {
                val count = runCatching { MorseCore.contestCount() }.getOrDefault(0)
                val from = intent?.getIntExtra("from", 0) ?: 0
                val to = intent?.getIntExtra("to", count - 1) ?: (count - 1)
                Log.i(SWEEP_TAG, "sweep start contests=$count range=$from..$to")
                for (i in from..to) {
                    val line = runCatching { sweepOneContest(i) }
                        .getOrElse { "idx=$i name=[?] CRASH: ${it.message}" }
                    Log.i(SWEEP_TAG, line)
                }
                Log.i(SWEEP_TAG, "sweep done")
                finish()
            }.start()
            return
        }
        setContent { MaterialTheme { CoreProbeScreen() } }
    }
}

@Composable
private fun CoreProbeScreen() {
    var version by remember { mutableStateOf("(loading)") }
    var rows by remember { mutableStateOf(emptyList<String>()) }
    var tapped by remember { mutableStateOf("") }
    var tapCount by remember { mutableStateOf(0) }
    var started by remember { mutableStateOf("") }
    var proof by remember { mutableStateOf("(playing a CQ...)") }

    LaunchedEffect(Unit) {
        version = runCatching { MorseCore.version() }.getOrElse { "ERROR: ${it.message}" }
        rows = runCatching {
            (0 until MorseCore.contestCount()).map { i ->
                "${i + 1}. ${MorseCore.contestName(i)}  [${MorseCore.contestKey(i)}]"
            }
        }.getOrElse { listOf("ERROR: " + it.message) }

        // Proof that a real contest object can be built inside the core: the class name that
        // comes back is the Pascal subclass (TCqWpx and friends), not a Kotlin stand-in.
        started = runCatching {
            val h = MorseCore.contestStart(0)
            if (h == 0L) {
                "contestStart(0) FAILED (core returned nil)"
            } else {
                val cls = MorseCore.contestClass(h)
                MorseCore.contestFree(h)
                "contestStart(0) -> " + cls
            }
        }.getOrElse { "ERROR: " + it.message }
        android.util.Log.i("MorseCoreProbe", "started=" + started)
        // The probe plays a whole exchange (thousands of DSP blocks); keeping that on the main
        // thread gets the process ANR-killed long before it can report anything.
        val audio = withContext(Dispatchers.Default) { audioProof() }
        android.util.Log.i("MorseCoreProbe", "audio=" + audio)
        proof = audio
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pascal core: $version", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Contest definitions from the core: " + rows.size, style = MaterialTheme.typography.bodyMedium)
        if (started.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(started, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(proof, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                // Each tap crosses into libmrcore.so and asks for the next contest definition.
                val n = runCatching { MorseCore.contestCount() }.getOrDefault(0)
                if (n > 0) {
                    val idx = tapCount % n
                    val name = runCatching { MorseCore.contestName(idx) }.getOrNull()
                    val key = runCatching { MorseCore.contestKey(idx) }.getOrNull()
                    tapped = "tap #${tapCount + 1} -> [$idx] $name (key=$key)"
                } else {
                    tapped = "tap #${tapCount + 1} -> core reported no contests"
                }
                tapCount += 1
            },
            modifier = Modifier.testTag("askCore"),
        ) {
            Text("Ask the core for the next contest")
        }
        if (tapped.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tapped, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LazyColumn { items(rows) { Text(it) } }
        }
    }
}

/**
 * Play a CQ and report what the core actually produced: proof that the ORIGINAL send path
 * (keyer -> envelope -> mixer -> AGC) puts signal on the wire and that a station answers it.
 * Runs off the main thread because the core does real DSP per block.
 *
 * Diagnostics come from `mr_debug_state` rather than from guesses:
 *   0 stations, 1 log rows, 2 run mode, 3 operator state, 4 block, 5 last message ordinal,
 *   6 monitor level x1000, 7 my envelope length, 8 my send position, 9 completed messages.
 */
private fun audioProof(): String = runCatching {
    val h = MorseCore.runStart(0, "VE3NEA")
    if (h == 0L) return@runCatching "runStart FAILED"
    try {
        val n = MorseCore.blockSize()
        val buf = FloatArray(n)
        var cqPeak = 0f
        var cqSamples = 0

        fun pull(blocks: Int, measurePeak: Boolean = false) {
            repeat(blocks) {
                val got = MorseCore.block(h, buf)
                if (got > 0 && measurePeak) {
                    cqSamples += got
                    for (i in 0 until got) {
                        val v = if (buf[i] < 0f) -buf[i] else buf[i]
                        if (v > cqPeak) cqPeak = v
                    }
                }
            }
        }

        /** The operator is still transmitting (state 3 = stSending). */
        fun sending() = MorseCore.debugState(h, 3) == 3

        /** Listen until the operator's message has gone out; returns the blocks it took. */
        fun waitDone(limit: Int = 240): Int {
            var k = 0
            while (sending() && k < limit) {
                pull(10)
                k += 10
            }
            return k
        }

        // 0. Latency of a key press: blocks between the send and the first loud sample. Anything
        //    the feed has buffered is heard before the new message, so this number is the delay
        //    between pressing CQ and hearing it.
        var sendLag = -1
        MorseCore.send(h, 1)
        var seen = 0
        while (seen < 60 && sendLag < 0) {
            val got = MorseCore.block(h, buf)
            if (got > 0) {
                var mx = 0f
                for (i in 0 until got) {
                    val v = if (buf[i] < 0f) -buf[i] else buf[i]
                    if (v > mx) mx = v
                }
                if (mx > 5000f) sendLag = seen
                seen++
            } else {
                Thread.sleep(2)
            }
        }
        Thread.sleep(300)

        // 1. Call CQ, and listen to the operator's own signal (a 25 wpm CQ is ~4 s).
        pull(2)
        MorseCore.send(h, 1)
        pull(120, measurePeak = true)
        var cqBlocks = waitDone()

        // 2. Keep calling until a station answers; the original forces a caller after 3 dry CQs.
        var calls = 1
        while (MorseCore.stationCount(h) == 0 && calls < 6) {
            MorseCore.send(h, 1)
            calls++
            pull(40)
            cqBlocks += waitDone()
            pull(20)
        }
        val stations = MorseCore.stationCount(h)
        val call = if (stations > 0) MorseCore.stationCall(h, 0) else null
        val e1 = if (stations > 0) MorseCore.stationExch(h, 0, 0) else null
        val e2 = if (stations > 0) MorseCore.stationExch(h, 0, 1) else null

        // 3. Copy what was heard into the form and press Enter: answer, TU, append the QSO.
        var saved = -1
        if (call != null) {
            MorseCore.setEntry(h, call, e1 ?: "", e2 ?: "")
            MorseCore.send(h, 2)   // msgNR: the exchange, report included (F2 on the form)
            pull(60)
            saved = MorseCore.saveQso(h)
            pull(80)   // let the answer and TU go out
        }

        // 4. Keep the loop running and watch the verified score. The DX confirms a few seconds
        //    after the save; this is the window that used to kill the process. Count real blocks
        //    (the ring is empty when the engine has not advanced) so the wait is engine time.
        var waitedBlocks = 0
        while (waitedBlocks < 600 && MorseCore.score(1) == 0) {
            val got = MorseCore.block(h, buf)
            if (got > 0) waitedBlocks++ else Thread.sleep(2)
        }
        val vptsEnd = MorseCore.score(1)

        // 4b. A SECOND QSO. The pile-up is supposed to put a different caller on the air, so a
        //     second save must log a different call and must not be flagged DUP. Before the
        //     callsign pool loaded, every caller was the same fallback call and rows 2..n were
        //     dupes of row 1; this is the regression check for that.
        var call2: String? = null
        var saved2 = -1
        var calls2 = 0
        var sameCall = false
        while (MorseCore.stationCount(h) == 0 && calls2 < 8) {
            MorseCore.send(h, 1)
            calls2++
            pull(40)
            waitDone()
            pull(20)
        }
        val heard2 = if (MorseCore.stationCount(h) > 0) MorseCore.stationCall(h, 0) else null
        if (heard2 != null) {
            call2 = heard2
            val e12 = MorseCore.stationExch(h, 0, 0)
            val e22 = MorseCore.stationExch(h, 0, 1)
            MorseCore.setEntry(h, heard2, e12 ?: "", e22 ?: "")
            MorseCore.send(h, 2)
            pull(60)
            saved2 = MorseCore.saveQso(h)
            pull(80)
        }
        sameCall = call2 != null && call2 == call
        val rows = MorseCore.logCount()
        // scWpx log columns: 0 time, 1 call, 2 rst, 3 nr, 4 Err, 5 wpm.
        val row2Call = if (rows > 1) MorseCore.logCell(1, 1) else null
        val row2Err = if (rows > 1) MorseCore.logCell(1, 4) else null
        var waited2 = 0
        while (waited2 < 600 && MorseCore.score(1) < 2) {
            val got = MorseCore.block(h, buf)
            if (got > 0) waited2++ else Thread.sleep(2)
        }

        // 5. Envelope of what the core actually produces, one RMS value per block (~46 ms). A
        //    once-a-second dropout shows up here as a periodic hole in the series; it cannot be
        //    blamed on the device or the ring when the numbers are flat.
        val rms = StringBuilder()
        repeat(660) {
            val got = MorseCore.block(h, buf)
            if (got > 0) {
                var acc = 0.0
                for (i in 0 until got) acc += buf[i] * buf[i]
                val v = kotlin.math.sqrt(acc / got).toInt()
                if (rms.isNotEmpty()) rms.append(',')
                rms.append(v)
            } else {
                Thread.sleep(2)
            }
        }

        val dbg = (0..9).joinToString("/") { MorseCore.debugState(h, it).toString() }
        // The three captions the game loop writes (rate / clock / pile-up), read back the way the
        // form reads them. Before this call existed the rate panel showed a literal 0.
        val status = (0..2).joinToString(" ") { "\"${MorseCore.statusText(it) ?: "-"}\"" }
        "cqPeak=$cqPeak cqSamples=$cqSamples sendLag=$sendLag calls=$calls cqBlocks=$cqBlocks " +
            "stations=$stations " +
            "call=${call ?: "-"} exch=${e1 ?: "-"}/${e2 ?: "-"} saved=$saved log=${MorseCore.logCount()} " +
            "pts=${MorseCore.score(0)} vpts=${MorseCore.score(1)} vptsEnd=$vptsEnd waited=$waitedBlocks " +
            "mult=${MorseCore.score(2)} rms=$rms " +
            "call2=${call2 ?: "-"} sameCall=$sameCall saved2=$saved2 rows=$rows " +
            "row2Call=${row2Call ?: "-"} row2Err=[${row2Err ?: "-"}] vpts2=${MorseCore.score(1)} " +
            "waited2=$waited2 calls2=$calls2 " +
            "status=$status rate=${MorseCore.rate()} block=$n dbg=$dbg"
    } finally {
        MorseCore.contestFree(h)
    }
}.getOrElse { "ERROR: " + it.message }
