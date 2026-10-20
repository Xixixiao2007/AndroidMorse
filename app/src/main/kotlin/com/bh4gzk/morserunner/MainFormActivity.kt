package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bh4gzk.morserunner.ui.MainFormScreen

/**
 * The phone UI, built as a reproduction of the original Windows form.
 *
 * It hosts [MainFormScreen], a scaled copy of `upstream/MorseRunnerCE/Main.dfm` (form `TMainForm`,
 * 729 x 506) and `ScoreDlg.dfm`: every visible design-time control with the original captions and
 * the original relative arrangement, scaled by one constant and stacked where a portrait phone
 * cannot hold the landscape bands side by side.
 *
 * Deliberately absent: any decoded "received" text. Morse Runner makes the operator copy by ear;
 * the only decoder upstream (`DebugCwDecoder`) is off by default and writes to the status bar.
 * The observable state at rest is the original one — splash page, empty log, empty score table,
 * clock at 00:00:00 — and the controls are what a later ticket wires to the contest core.
 */
class MainFormActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainFormScreen() }
    }
}
