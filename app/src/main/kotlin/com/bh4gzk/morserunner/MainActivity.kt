package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.bh4gzk.morserunner.audio.CwAudioEngine
import com.bh4gzk.morserunner.audio.SessionAudio
import com.bh4gzk.morserunner.audio.StationAudio
import com.bh4gzk.morserunner.data.StationAssets
import com.bh4gzk.morserunner.session.SessionBootstrap
import com.bh4gzk.morserunner.session.SessionController
import com.bh4gzk.morserunner.ui.SessionRoot

/**
 * The one screen (spec §8): a contest session on a phone.
 *
 * The activity does three things and nothing else — parse the packaged data, build the session
 * controller (a pure Kotlin object, tested on the JVM), and hand the screen a controller plus
 * the audio output. All the game behaviour lives in [SessionController]; all the pixels live in
 * [SessionRoot].
 *
 * `android:configChanges="orientation|screenSize|keyboardHidden"` keeps the session alive
 * across rotation: recreating the activity would restart the contest, since the controller is
 * deliberately not a ViewModel (persistence is T9's).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = StationAssets.load(assets)
        val bootstrap = SessionBootstrap.create(
            assets = assets,
            data = data,
            seed = SESSION_SEED,
            startMs = System.currentTimeMillis(),
        )
        val audio = SessionAudio(
            engine = CwAudioEngine(),
            stationAudio = StationAudio(),
            myCall = SessionBootstrap.DEFAULT_MY_CALL,
            mySpeedWpm = SessionController.DEFAULT_SPEED_WPM,
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SessionRoot(bootstrap.controller, audio, bootstrap.note)
                }
            }
        }
    }

    private companion object {
        /**
         * Fixed so a session replays from its seed (spec §6, ADR-0004 decision 3). A per-run
         * seed arrives with the settings screen (T9).
         */
        const val SESSION_SEED: Long = 20_261_019L
    }
}
