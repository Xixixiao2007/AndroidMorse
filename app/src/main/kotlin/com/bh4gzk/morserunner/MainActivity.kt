package com.bh4gzk.morserunner

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bh4gzk.morserunner.audio.CwAudioEngine
import com.bh4gzk.morserunner.audio.NoiseBed
import com.bh4gzk.morserunner.audio.Receiver
import com.bh4gzk.morserunner.audio.SessionAudio
import com.bh4gzk.morserunner.audio.StationAudio
import com.bh4gzk.morserunner.data.StationAssets
import com.bh4gzk.morserunner.data.StationData
import com.bh4gzk.morserunner.session.SessionBootstrap
import com.bh4gzk.morserunner.session.SessionPlan
import com.bh4gzk.morserunner.session.SessionPlanner
import com.bh4gzk.morserunner.settings.PrefsStringStore
import com.bh4gzk.morserunner.settings.SessionPersistence
import com.bh4gzk.morserunner.settings.SessionStore
import com.bh4gzk.morserunner.settings.SettingsStore
import com.bh4gzk.morserunner.ui.SessionRoot

/**
 * The one screen (spec §8): a contest session on a phone.
 *
 * The activity does four things and nothing else — decide which session to start (a stored one
 * or a fresh one), parse the packaged data, build the session controller (a pure Kotlin object,
 * tested on the JVM), and hand the screen a controller plus the audio output. All the game
 * behaviour lives in `SessionController`; all the pixels live in `SessionRoot`; all the encoding
 * lives in `com.bh4gzk.morserunner.settings` (JVM-tested, see ADR-0009).
 *
 * **A process death resumes the contest (F10).** The clock is the wall clock and the roster is a
 * function of the session seed (ADR-0007 §1, §6), so a stored snapshot of seed + start + log is
 * enough to put the operator back where they were: the elapsed time keeps running, the score is
 * intact, and only the station that was mid-transmission is regenerated. A contest whose clock
 * has run out, or a snapshot this build cannot read or cannot load, starts a fresh one.
 *
 * **Settings act in two places.** RIT is a receive control and is applied to the running audio
 * immediately; everything else is baked into the roster, the contest clock or the audio engine
 * when a session starts, so the settings screen's "重开一局" starts a new contest with them.
 *
 * `android:configChanges="orientation|screenSize|keyboardHidden"` keeps the session alive
 * across rotation: recreating the activity would restart the contest.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = PrefsStringStore(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        val settingsStore = SettingsStore(store)
        val sessionStore = SessionStore(store)
        val now = System.currentTimeMillis()
        val data = StationAssets.load(assets)

        var plan = SessionPlanner.plan(settingsStore, sessionStore, now)
        val bootstrap = try {
            build(plan, data)
        } catch (failure: RuntimeException) {
            // A snapshot naming a contest this build cannot load (an older or newer build's save)
            // must not crash-loop the app: drop it and start a fresh contest instead.
            sessionStore.clear()
            plan = SessionPlanner.fresh(settingsStore, now)
            build(plan, data)
        }

        // The boxes the operator was typing into are part of what a resume restores.
        plan.entry?.let { entry ->
            if (entry.call.isNotBlank()) bootstrap.controller.editCall(entry.call)
            if (entry.exch1.isNotBlank()) bootstrap.controller.editExchange1(entry.exch1)
            if (entry.exch2.isNotBlank()) bootstrap.controller.editExchange2(entry.exch2)
        }

        val settings = plan.settings
        val audio = SessionAudio(
            // QRN is a mixer-level bed (ADR-0008 §1): no bed at all is the "off" switch. Its
            // level follows the receiver's bandwidth and its seed follows the session's, so the
            // band a session hears is a function of the settings and the seed.
            engine = if (settings.qrn) {
                CwAudioEngine(
                    bed = NoiseBed(
                        bandPeak = Receiver.bandPeakFor(settings.bandwidthHz),
                        seed = plan.seed,
                    ),
                )
            } else {
                CwAudioEngine()
            },
            stationAudio = StationAudio(),
            myCall = settings.myCall,
            mySpeedWpm = settings.speedWpm,
            myPitchHz = settings.pitchHz,
            ritHz = settings.ritHz,
            rootSeed = plan.seed,
        )

        val persistence = SessionPersistence(
            store = sessionStore,
            contestId = plan.contestId,
            settings = settings,
            seed = plan.seed,
            startMs = plan.startMs,
        )

        setContent {
            // What the settings dialog shows: the session's settings until the operator saves a
            // change, then the saved ones (RIT has already been applied to the running audio).
            var shown by remember { mutableStateOf(settings) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SessionRoot(
                        controller = bootstrap.controller,
                        audio = audio,
                        note = bootstrap.note,
                        settings = shown,
                        onSaveSettings = { changed ->
                            settingsStore.save(changed)
                            // RIT is the one receive-side control: apply it to the running audio.
                            audio.setRit(changed.ritHz)
                            shown = changed
                        },
                        onRestartSession = { changed ->
                            settingsStore.save(changed)
                            // A new contest, not a resume: the snapshot would otherwise put the
                            // operator back into the old one with the old settings.
                            sessionStore.clear()
                            recreate()
                        },
                        onTick = { ui ->
                            persistence.saveIfChanged(ui, bootstrap.controller.loggedRecords())
                        },
                    )
                }
            }
        }
    }

    /** Builds the controller for [plan]; the plan itself is [SessionPlanner]'s (JVM-tested). */
    private fun build(plan: SessionPlan, data: StationData): SessionBootstrap.Result =
        SessionBootstrap.create(
            assets = assets,
            data = data,
            settings = plan.settings,
            seed = plan.seed,
            startMs = plan.startMs,
            contestId = plan.contestId,
            log = plan.log,
        )

    private companion object {
        /** The one preferences file; its two keys are owned by `SettingsStore`/`SessionStore`. */
        const val PREFS_NAME: String = "morserunner"
    }
}
