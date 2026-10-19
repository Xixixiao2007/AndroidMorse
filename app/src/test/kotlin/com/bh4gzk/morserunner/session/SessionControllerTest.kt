package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.AssetCallFacts
import com.bh4gzk.morserunner.contest.BehaviourProfile
import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.QrmStation
import com.bh4gzk.morserunner.qso.QsoMessage
import com.bh4gzk.morserunner.qso.QsoState
import com.bh4gzk.morserunner.settings.SessionSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The session controller: what the Compose screen drives, with no Android in the way.
 *
 * The clock is passed in ([SessionController.tick]) and the audio is drained as
 * [AudioCue]s, so a whole contact — CQ, the station's answer, the copied exchange, `TU`,
 * the log — is reproducible on the JVM without a device or a sound card.
 */
class SessionControllerTest {

    // --- fixtures ------------------------------------------------------------------

    private val dxcc = DxccList.parse(
        """
        K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
        DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
        """.trimIndent(),
    )

    private val facts: CallFacts = AssetCallFacts(dxcc)

    private val cqww: ContestConfig = assertIs<LoadResult.Success>(ContestLibrary.load("cqww")).config

    /** One station on the air: the contact tests need no draw. */
    private val oneCall: CallHistoryFile = load("DL1ABC,14,\n")

    /** Three real calls, for the completion test. */
    private val threeCalls: CallHistoryFile = load("DL1ABC,14,\nDL2XYZ,14,\nK1ABC,5,\n")

    private fun load(text: String): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, cqww.callHistory!!)).history

    private fun session(
        history: CallHistoryFile = oneCall,
        seed: Long = 7L,
        flutter: Boolean = true,
        speedWpm: Int = SessionController.DEFAULT_SPEED_WPM,
        pitchHz: Int = SessionController.DEFAULT_PITCH_HZ,
        durationMinutes: Int = SessionSettings.DEFAULT_DURATION_MINUTES,
        qrm: Boolean = true,
    ): SessionController = SessionController.start(
        config = cqww,
        pool = history,
        history = history,
        autocompleteCalls = history.calls,
        calls = facts,
        myCall = "W1AW",
        myExchange = mapOf("rst" to "599", "zone" to "5"),
        seed = seed,
        startMs = 0L,
        speedWpm = speedWpm,
        pitchHz = pitchHz,
        durationMinutes = durationMinutes,
        qrm = qrm,
        flutter = flutter,
    )

    // --- entry and Enter -------------------------------------------------------------

    @Test
    fun `nothing is transmitted before the session starts`() {
        val controller = session()
        controller.tick(0)
        assertEquals(emptyList(), controller.drainCues())
        assertEquals(QsoState.Idle, controller.uiState.qsoState)
        assertEquals("DL1ABC", controller.uiState.stationCall, "a station is on the air")
        assertEquals("599 14", controller.uiState.stationExchange)
    }

    @Test
    fun `Enter with an empty call sends CQ`() {
        val controller = session()
        controller.tick(0)
        controller.enter()

        val cue = controller.drainCues().single()
        assertEquals("CQ W1AW TEST", cue.text)
        assertEquals(null, cue.station, "my own sending has no station")
        assertTrue(cue.durationMs > 0)
        assertEquals(QsoState.CallingCq, controller.uiState.qsoState)
        assertEquals(listOf("CQ W1AW TEST"), controller.uiState.pendingText)
    }

    @Test
    fun `Enter with the call sends his call and then my exchange, in that order`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()

        val first = controller.drainCues().single()
        assertEquals("DL1ABC", first.text)
        controller.tick(first.durationMs + 1)

        val second = controller.drainCues().single()
        assertEquals("599 5", second.text, "`<#>` is my exchange, not the station's")
        assertTrue(second.durationMs > 0)
        // The piece is current, so it is still in the queue until its keying completes.
        assertEquals(listOf("599 5"), controller.uiState.pendingText)
    }

    // --- the station answers ---------------------------------------------------------

    @Test
    fun `the station answers after my batch, and the panel shows what it sent`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()
        drainMyTransmission(controller)

        val reply = controller.drainCues().single()
        assertEquals("DL1ABC", reply.station?.call)
        assertTrue(reply.text.startsWith("R "), "expected `R <exchange>`, got '${reply.text}'")
        assertTrue(reply.text.contains("599 14"), "the reply carries the station exchange: '${reply.text}'")
        assertEquals(reply.text, controller.uiState.lastHeard)
    }

    @Test
    fun `an idle station calls CQ on its own`() {
        val controller = session()
        controller.tick(0)
        controller.tick(SessionController.CQ_LEAD_MS)

        val cq = controller.drainCues().single()
        assertEquals("CQ DL1ABC TEST", cq.text)
        assertEquals("DL1ABC", cq.station?.call)
        assertEquals(cq.text, controller.uiState.lastHeard)
    }

    // --- logging ---------------------------------------------------------------------

    @Test
    fun `a complete contact is logged, scored and shown in the status bar`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        // I copied the exchange the station sent.
        controller.editExchange1("599")
        controller.editExchange2("14")
        controller.enter()

        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text)
        assertEquals(QsoState.Logged, controller.uiState.qsoState)

        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(6, ui.rawScore, "3 points × 2 multipliers")
        assertEquals(6, ui.verifiedScore)
        assertEquals(2, ui.multipliers)
        assertTrue(ui.lastStatus.contains("DL1ABC"), "the status line names the logged call: '${ui.lastStatus}'")
        assertEquals(1, ui.rateLastFiveMinutes)
        assertTrue(ui.ratePerHour > 0.0)
    }

    @Test
    fun `the worked station is replaced once the TU is sent`() {
        val controller = session(threeCalls)
        val first = controller.uiState.stationCall
        controller.tick(0)
        controller.editCall(first)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()
        controller.editExchange1("599")
        controller.editExchange2(controller.uiState.stationExchange.substringAfter(' '))
        controller.enter()

        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text)
        controller.tick(controller.lastNowMs + tu.durationMs + 1)

        assertEquals(QsoState.Idle, controller.uiState.qsoState, "the contact is over")
        assertEquals(1, controller.uiState.qsos)
        assertNotEquals(first, controller.uiState.stationCall, "a new station takes the frequency")
        assertEquals("", controller.uiState.entry.call, "the boxes are wiped for the next contact")
    }

    // --- the other actions -----------------------------------------------------------

    @Test
    fun `autocomplete comes from the contest call history`() {
        val controller = session(threeCalls)
        controller.tick(0)
        controller.editCall("DL")
        assertEquals(listOf("DL1ABC", "DL2XYZ"), controller.uiState.suggestions)

        // `?` stays a wildcard: no completion while the call is half-copied.
        controller.editCall("DL?")
        assertEquals(emptyList(), controller.uiState.suggestions)
    }

    @Test
    fun `a message button queues what the key would`() {
        val controller = session()
        controller.tick(0)
        controller.perform(KeyAction.Send(QsoMessage.CQ))
        assertEquals("CQ W1AW TEST", controller.drainCues().single().text)

        controller.perform(KeyAction.Wipe)
        assertEquals("", controller.uiState.entry.call)
        assertEquals(emptyList(), controller.uiState.pendingText)
        assertEquals(QsoState.Idle, controller.uiState.qsoState)
    }

    @Test
    fun `Esc stops my sending`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()
        controller.drainCues()

        controller.perform(KeyAction.Abort)
        assertEquals(emptyList(), controller.uiState.pendingText, "the queue is dropped")
        // The keyer must not resume the cancelled pieces later either.
        controller.tick(controller.lastNowMs + 60_000)
        assertTrue(controller.drainCues().none { it.text == "599 5" })
    }

    @Test
    fun `the session ends when the contest duration elapses`() {
        val controller = session()
        controller.tick(0)
        assertEquals(30 * 60_000L, controller.uiState.durationMs)

        controller.tick(30 * 60_000L)
        assertTrue(controller.uiState.finished)
        assertEquals(0L, controller.uiState.remainingMs)

        controller.perform(KeyAction.Send(QsoMessage.CQ))
        assertEquals(emptyList(), controller.drainCues(), "a finished session transmits nothing")
    }

    @Test
    fun `the same seed replays the same session`() {
        fun run(): List<String> {
            val controller = session(threeCalls)
            controller.tick(0)
            controller.editCall(controller.uiState.stationCall)
            controller.enter()
            drainMyTransmission(controller)
            val reply = controller.drainCues().single().text
            return listOf(controller.uiState.stationCall, reply)
        }
        assertEquals(run(), run())
    }

    // --- the realism effects (T8) -----------------------------------------------------

    @Test
    fun `the session gives its stations flutter, and only when it is enabled`() {
        // Upstream `Ini.Flutter` decides whether 30 % of stations get `3 + Random*30`
        // instead of the QSB bandwidth (`DxStn.pas:77-79`). If the controller forgot to
        // pass the setting on, the effect would be compiled and never heard.
        val fluttering = (1L..40L).count { session(seed = it).onAirStation?.fade?.flutter == true }
        assertTrue(fluttering > 0, "no station fluttered over 40 seeds")

        val enabled = session(seed = 3)
        assertTrue(enabled.onAirStation != null, "no station on the air")
        for (seed in 1L..20L) {
            assertTrue(
                session(seed = seed, flutter = false).onAirStation?.fade?.flutter != true,
                "seed $seed: a station fluttered with flutter disabled",
            )
        }
    }

    @Test
    fun `a LID station gets a LID operator, and a normal station does not`() {
        var lids = 0
        var normal = 0
        for (seed in 1L..60L) {
            val controller = session(seed = seed)
            val station = controller.onAirStation ?: error("seed $seed: no station on the air")
            val operator = controller.onAirOperator ?: error("seed $seed: no operator for the station")
            assertEquals(
                station.behaviour == BehaviourProfile.LID,
                operator.lids,
                "seed $seed: station ${station.call} is ${station.behaviour}, operator.lids=${operator.lids}",
            )
            if (station.behaviour == BehaviourProfile.LID) lids++ else normal++
        }
        assertTrue(lids > 0, "no LID among 60 seeds: the 10 % draw is wired wrong")
        assertTrue(normal > 0, "every station was a LID")
    }

    @Test
    fun `the interfering station transmits off frequency and never enters the QSO`() {
        val controller = session()
        val interference = mutableListOf<QrmCue>()
        var now = 0L
        while (now <= 120_000L) {
            controller.tick(now)
            interference += controller.drainQrm()
            now += 100
        }

        assertTrue(interference.isNotEmpty(), "no QRM in two minutes of a QRN/QRM session")
        for (cue in interference) {
            val offset = abs(cue.pitchHz - SessionController.DEFAULT_PITCH_HZ)
            assertTrue(
                offset in QrmStation.MIN_OFFSET_HZ..QrmStation.MAX_OFFSET_HZ,
                "'${cue.text}' sits $offset Hz from the receiver: not off-frequency",
            )
            assertTrue(cue.speedWpm in 30..50, "'${cue.text}' at ${cue.speedWpm} WPM is not a QRM station")
            assertTrue(
                cue.text.startsWith("QRL") || cue.text.contains("CQ CQ TEST") || cue.text.endsWith("QSY QSY"),
                "unexpected QRM text '${cue.text}'",
            )
            assertTrue(cue.durationMs > 0)
        }

        // It is not one of the QSO's transmissions, and it does not become the station
        // being worked, the last thing heard, or a logged contact.
        val qsoTexts = controller.drainCues().map { it.text }
        assertTrue(interference.none { it.text in qsoTexts }, "the QRM leaked into the QSO cue stream")
        assertEquals(QsoState.Idle, controller.uiState.qsoState)
        assertEquals(0, controller.uiState.qsos)
        assertTrue(
            interference.none { it.text == controller.uiState.lastHeard },
            "the QRM was attributed to the worked station",
        )
    }

    @Test
    fun `the same seed replays the same interference`() {
        fun run(): List<String> {
            val controller = session(seed = 21)
            val texts = mutableListOf<String>()
            var now = 0L
            while (now <= 90_000L) {
                controller.tick(now)
                texts += controller.drainQrm().map { it.text }
                now += 250
            }
            return texts
        }
        assertTrue(run().isNotEmpty(), "the replay test saw no QRM to compare")
        assertEquals(run(), run())
    }

    // --- the settings (T9) ------------------------------------------------------------

    @Test
    fun `the duration setting decides when the contest ends, clamped by the contest`() {
        val short = session(durationMinutes = 5)
        short.tick(0)
        assertEquals(5 * 60_000L, short.uiState.durationMs)
        short.tick(5 * 60_000L)
        assertTrue(short.uiState.finished, "a five-minute contest is over after five minutes")

        // CQ WW accepts 1..60 minutes; a longer preference is the contest's call, not the user's.
        val long = session(durationMinutes = 240)
        assertEquals(60 * 60_000L, long.uiState.durationMs, "the setting was not clamped by `duration.max`")
    }

    @Test
    fun `the speed setting reaches my own keying`() {
        // The same message, keyed by two operators: the faster one takes less time. (The
        // station side reads the same setting through `station.speedRange`.)
        fun cqDuration(wpm: Int): Long {
            val controller = session(speedWpm = wpm)
            controller.tick(0)
            controller.enter()
            return controller.drainCues().single().durationMs
        }
        assertTrue(cqDuration(40) < cqDuration(15), "40 WPM did not key faster than 15 WPM")
    }

    @Test
    fun `the pitch setting is the centre the interfering station is measured from`() {
        val controller = session(pitchHz = 700)
        val interference = mutableListOf<QrmCue>()
        var now = 0L
        while (now <= 120_000L) {
            controller.tick(now)
            interference += controller.drainQrm()
            now += 100
        }
        assertTrue(interference.isNotEmpty(), "no QRM to measure")
        for (cue in interference) {
            val offset = abs(cue.pitchHz - 700)
            assertTrue(
                offset in QrmStation.MIN_OFFSET_HZ..QrmStation.MAX_OFFSET_HZ,
                "'${cue.text}' sits $offset Hz from a 700 Hz receiver: the setting was ignored",
            )
        }
    }

    @Test
    fun `the QRM switch turns the interference off and on`() {
        fun qrmTexts(enabled: Boolean): List<String> {
            val controller = session(qrm = enabled)
            val texts = mutableListOf<String>()
            var now = 0L
            while (now <= 180_000L) {
                controller.tick(now)
                texts += controller.drainQrm().map { it.text }
                now += 100
            }
            return texts
        }
        assertTrue(qrmTexts(enabled = true).isNotEmpty(), "QRM on produced no interference")
        assertEquals(emptyList(), qrmTexts(enabled = false), "QRM off still scheduled an episode")
    }

    @Test
    fun `RIT shifts the received stations and never the self-monitor`() {
        // The whole of RIT for this port: received cues move by the operator's offset, my own
        // sending does not (upstream mixes the operator's sidetone in after the station loop).
        val mine = AudioCue("CQ TEST", station = null, durationMs = 1_000)
        val theirs = AudioCue("R 599 14", station = onAir(seed = 1), durationMs = 1_000)
        assertEquals(0, mine.receiveOffsetHz(ritHz = 50))
        assertEquals(50, theirs.receiveOffsetHz(ritHz = 50))
        assertEquals(0, theirs.receiveOffsetHz(ritHz = 0))
    }

    @Test
    fun `the same settings and the same seed replay the same session`() {
        val custom = SessionSettings(speedWpm = 40, pitchHz = 700, durationMinutes = 10, qrm = false)
        fun run(): List<String> {
            val controller = session(
                seed = 21L,
                speedWpm = custom.speedWpm,
                pitchHz = custom.pitchHz,
                durationMinutes = custom.durationMinutes,
                qrm = custom.qrm,
            )
            controller.tick(0)
            controller.enter()
            val cq = controller.drainCues().single()
            controller.tick(cq.durationMs + 1)
            controller.tick(SessionController.CQ_LEAD_MS + 60_000)
            return listOf(controller.uiState.stationCall, cq.text) + controller.drainCues().map { it.text }
        }
        assertEquals(run(), run(), "settings changed the replay")
    }

    // --- helpers ---------------------------------------------------------------------

    /** A roster station for cue-shaped tests; it is only a carrier for the RIT rule. */
    private fun onAir(seed: Long) = session(seed = seed).onAirStation ?: error("no station on the air")

    /** Lets my own transmission finish, cue by cue, until the queue is empty. */
    private fun drainMyTransmission(controller: SessionController) {
        var guard = 0
        while (controller.uiState.pendingText.isNotEmpty() && guard++ < 10) {
            val cue = controller.drainCues().firstOrNull() ?: break
            controller.tick(controller.lastNowMs + cue.durationMs + 1)
        }
        // The station is told what it heard and schedules its reply on the same tick.
        controller.tick(controller.lastNowMs + 2_000)
    }
}
