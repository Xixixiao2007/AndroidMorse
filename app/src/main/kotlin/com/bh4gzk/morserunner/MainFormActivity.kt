package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bh4gzk.morserunner.ui.MainFormScreen
import com.bh4gzk.morserunner.ui.MorseRunnerFormScreen

/**
 * The phone UI as a reproduction of the original Windows form.
 *
 * This activity is the launcher for the layout milestone: it hosts
 * [MainFormScreen], a faithful, scaled copy of
 * `upstream/MorseRunnerCE/Main.dfm` (form `TMainForm`, 729 x 506) and
 * `ScoreDlg.dfm`.  It starts no session, touches no audio and calls no engine:
 * this milestone is layout only.
 *
 * The earlier pixel-identical reproduction ([MorseRunnerFormScreen]) is kept
 * compiled for reference; [MainFormScreen] is the one on screen because it
 * stacks the landscape form's three bands for a portrait phone instead of
 * shrinking it to a 411 x 296 dp island.
 *
 * The previous session screen ([MainActivity]) is kept and still launchable by
 * name (`com.bh4gzk.morserunner/.MainActivity`); a later ticket wires the form's
 * controls to the session controller and retires the old entry point.
 */
class MainFormActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainFormScreen() }
    }
}
