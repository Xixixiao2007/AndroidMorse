package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bh4gzk.morserunner.ui.MorseRunnerFormScreen

/**
 * The phone UI as a reproduction of the original Windows form.
 *
 * This activity is the launcher for the layout milestone: it hosts
 * [MorseRunnerFormScreen], a pixel-faithful, scaled copy of
 * `upstream/MorseRunnerCE/Main.dfm` (form `TMainForm`, 729 x 506) and
 * `ScoreDlg.dfm`.  It starts no session, touches no audio and calls no engine:
 * this milestone is layout only.
 *
 * The previous session screen ([MainActivity]) is kept and still launchable by
 * name (`com.bh4gzk.morserunner/.MainActivity`); a later ticket wires the form's
 * controls to the session controller and retires the old entry point.
 */
class MainFormActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MorseRunnerFormScreen() }
    }
}
