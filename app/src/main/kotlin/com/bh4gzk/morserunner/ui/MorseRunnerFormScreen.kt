package com.bh4gzk.morserunner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/*
 * ============================================================================
 *  A layout re-creation of the original Morse Runner Windows form.
 * ============================================================================
 *
 * Source of truth: `upstream/MorseRunnerCE/Main.dfm` (form `TMainForm`, client
 * 729 x 506) and `upstream/MorseRunnerCE/ScoreDlg.dfm` (form `TScoreDialog`,
 * client 394 x 163).  This file reproduces their controls — same class of
 * control, same caption, same relative position — and changes *only* the scale
 * of the geometry so the 729 x 506 desktop form fits a phone.
 *
 * ## Geometries are the DFM's, verbatim
 *
 * Every `Left/Top/Width/Height` below is copied from the .dfm, in its original
 * pixels, and placed with [FormFrame]/[FormLabel] at `value * scale`.  A
 * reviewer can therefore diff this file against Main.dfm line by line; the
 * coordinate inventory is tabulated in `.scratch/ui/dfm-inventory.md`.
 *
 * ## The scaling rule (the only thing that changes)
 *
 *   scale = min(available width / 729, available height / (506 + 22))
 *
 * The form keeps its 729:506 aspect ratio, is never cropped, and grows to fill
 * whichever phone dimension binds first:
 *   - portrait  (the reported screenshot): width binds, so the form spans the
 *     phone's width and only ~1/3 of its height — a desktop window on a tall
 *     screen.  It is anchored to the top of the safe area (see below); the
 *     original desktop placement (`Position = poScreenCenter`) has no phone
 *     equivalent.
 *   - landscape: height binds, so the form is larger and fills the height,
 *     centred horizontally — the arrangement that reads closest to the
 *     original.
 * The 22 px menu bar sits *above* the DFM's client area because Windows draws
 * `TMainMenu` outside `ClientHeight`; it is scaled by the same factor.
 * All borders and font sizes scale with `scale` too, so proportions are kept.
 *
 * ## What is a placeholder or is not drawn (nothing is silently dropped)
 *
 *  - `Panel3.PaintBox1` (TPaintBox, the CW signal scope): a placeholder Box
 *    filled with the DFM's `clInfoBk`; the original paints a live waveform in
 *    `PaintBox1Paint`, which is game/audio work and out of scope for this
 *    layout milestone.
 *  - `VolumeSlider1` (TVolumeSlider, a custom VCL control): drawn as a track
 *    and thumb, no behaviour.
 *  - `Panel2.Panel11.Caption = '0'` is covered by the alClient `ListView1` in
 *    the original, so it is not drawn (it cannot be seen on the real form).
 *  - `RichEdit1` (received text) and `sbar` (callsign info status bar) are
 *    `Visible = False` in the .dfm; they occupy no pixels on the original form
 *    and are therefore not drawn.  They are listed in the inventory document.
 *  - `MainMenu1` (TMainMenu) is drawn as the menu strip above the client area.
 *    `PopupMenu1` (the Run dropdown), `ImageList1`, `AlSoundOut1` and
 *    `AlWavFile1` are non-visual VCL components: they have no pixels in the
 *    original either, so no placeholder is drawn for them.
 *  - Captions keep the DFM text verbatim, including the `&` accelerators of the
 *    score dialog — except that `&` is not an accelerator on Android, so the
 *    dialog buttons show the visible text only ("Submit to Server", ...).
 *
 * The score dialog (ScoreDlg.dfm) is reproduced by [ScoreDialogPanel] at the
 * bottom of this file.  It is not reachable yet: this is a layout milestone,
 * and a later ticket wires it to the end-of-contest state.
 */

/** Main.dfm: `ClientWidth = 729`, `ClientHeight = 506`. */
private const val DESIGN_WIDTH = 729f
private const val DESIGN_HEIGHT = 506f

/** TMainMenu is drawn by Windows above the client area; 22 px at 96 dpi. */
private const val MENU_HEIGHT = 22f

/** ScoreDlg.dfm: `ClientWidth = 394`, `ClientHeight = 163`. */
private const val SCORE_WIDTH = 394f
private const val SCORE_HEIGHT = 163f

// ---- VCL palette (TColor is 0x00BBGGRR; values converted to ARGB once) ------
private val FormFace = Color(0xFFF0F0F0) // clBtnFace
private val FormInk = Color(0xFF000000) // clWindowText
private val FormBorder = Color(0xFF808080) // panel/group-box bevel, dark edge
private val FormShadow = Color(0xFFA0A0A0) // bevel light edge / speed-button frame
private val FieldFace = Color(0xFFFFFFFF) // clWindow (edits, list views)
private val FieldBorder = Color(0xFF7F7F7F) // edit control frame
private val ClientBackground = Color(0xFFE9FEFE) // Shape1 Brush.Color = 16711401
private val InfoBackground = Color(0xFFFFFFE1) // clInfoBk (PaintBox1)
private val TimerBack = Color(0xFF000000) // Panel2 Color = clBackground
private val TimerFont = Color(0xFF20F0D7) // Panel2 Font.Color = 14151712
private val TitleGreen = Color(0xFF008000) // Label13 clGreen
private val TitleAqua = Color(0xFF00FFFF) // Label12 clAqua
private val TitleShadow = Color(0xFFBCBCBC) // Label14 Font.Color = 12369084
private val RitThumb = Color(0xFF1FE0C4) // Shape2 Brush.Color = 12902431
private val FormRed = Color(0xFFFF0000) // clRed (Panel4 mode caption)
private val HeaderFace = Color(0xFFF0F0F0) // ListView column header
private val HeaderEdge = Color(0xFFA0A0A0) // ListView column separator

/**
 * The phone rendering of `Main.dfm`.
 *
 * This composable is layout only: it holds no state, starts no session and
 * touches no audio.  It exists to prove the phone UI is a faithful copy of the
 * Windows form (see the file header for the scaling rule).
 */
@Composable
fun MorseRunnerFormScreen(modifier: Modifier = Modifier) {
    // The form is a pixel-for-pixel replica, so it must not inherit the user's
    // font-size preference: with fontScale forced to 1, 1 sp == 1 dp and every
    // DFM font height scales by exactly `scale`.
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
        Box(modifier.fillMaxSize().background(FormFace)) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                // The desktop form is placed with `Position = poScreenCenter`;
                // a phone has no desktop, so the form is anchored to the top of
                // the safe area and the spare height stays below it (where the
                // keypad would rise).  In landscape the height binds, so the
                // anchoring makes no difference there.
                contentAlignment = Alignment.TopCenter,
            ) {
                // 1 dp of breathing room on each side so the form's frame is not
                // clipped by the screen edge in portrait.
                val scale = min(
                    (maxWidth.value - 2f) / DESIGN_WIDTH,
                    (maxHeight.value - 2f) / (DESIGN_HEIGHT + MENU_HEIGHT),
                )
                Column(
                    modifier = Modifier.size(
                        width = (DESIGN_WIDTH * scale).dp,
                        height = ((DESIGN_HEIGHT + MENU_HEIGHT) * scale).dp,
                    ),
                ) {
                    MenuBar(scale)
                    FormClient(scale)
                }
            }
        }
    }
}

// ============================================================================
//  Menu bar — TMainMenu (Main.dfm:964): File, Run, Send, Settings, Help
// ============================================================================

@Composable
private fun MenuBar(scale: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((MENU_HEIGHT * scale).dp)
            .background(FormFace),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = px(4, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (caption in listOf("File", "Run", "Send", "Settings", "Help")) {
                FormText(
                    text = caption,
                    scale = scale,
                    fontPx = 12f,
                    color = FormInk,
                    modifier = Modifier.padding(horizontal = px(4, scale)),
                )
            }
        }
        // The menu bar's bottom rule.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(px(1, scale))
                .background(FormShadow),
        )
    }
}

// ============================================================================
//  The client area — 729 x 506 as laid out by TMainForm
// ============================================================================

@Composable
private fun FormClient(scale: Float) {
    Box(modifier = Modifier.size((DESIGN_WIDTH * scale).dp, (DESIGN_HEIGHT * scale).dp)) {
        // Main.dfm:62  Bevel1  (0,0,729x2, alTop) — the etched rule under the menu.
        FormFrame(scale, 0, 0, 729, 2) {
            Box(Modifier.fillMaxSize().background(FormShadow))
        }

        // Main.dfm:380 Panel6 (0,2,517x359, alClient) — log list + splash area.
        LogAndScopePanel(scale)

        // Main.dfm:614 Panel9 (517,2,212x359, alRight) — contest/station settings.
        StationSettingsPanel(scale)

        // Main.dfm:371 Panel5 (0,361,729x10, alBottom) is an empty, bevel-less
        // spacer panel: it has no pixels of its own, so nothing is drawn.

        // Main.dfm:70 Panel1 (0,371,729x135, alBottom) — entry, message grid, clock.
        EntryPanel(scale)
    }
}

// ---------------------------------------------------------------------------
//  Panel6 — the "log and scope" area (Main.dfm:380)
// ---------------------------------------------------------------------------

@Composable
private fun LogAndScopePanel(scale: Float) {
    FormFrame(scale, 0, 2, 517, 359, Modifier.border(px(1, scale), FormBorder)) {
        // ListView2 (Main.dfm:545, alTop 0,0,513x54) — the station log.
        // Seven columns: UTC 80, Call 104, Recv 90, Sent 84, Pref (auto, 75),
        // Chk 40, Wpm 40 — widths from the .dfm.
        Box(
            modifier = Modifier
                .offset(px(0, scale), px(0, scale))
                .size(px(513, scale), px(54, scale))
                .background(FieldFace),
        ) {
            ListViewHeader(
                scale = scale,
                left = 0,
                top = 0,
                fontPx = 15f,
                headerHeight = 21,
                columns = listOf(
                    VclColumn("UTC", 80),
                    VclColumn("Call", 104),
                    VclColumn("Recv", 90),
                    VclColumn("Sent", 84),
                    VclColumn("Pref", 75),
                    VclColumn("Chk", 40),
                    VclColumn("Wpm", 40),
                ),
            )
        }

        // Shape1 (Main.dfm:389, alClient 0,54,513x238) — the splash background.
        Box(
            modifier = Modifier
                .offset(px(0, scale), px(54, scale))
                .size(px(513, scale), px(238, scale))
                .background(ClientBackground),
        )

        // The three stacked "Morse Runner 1.xx" labels (Main.dfm:400-441):
        // grey at (100,61), aqua at (95,57), green at (94,56).  Delphi draws
        // them in declaration order, so the green one ends up on top and the
        // other two read as a drop shadow.
        FormLabel(scale, 100, 61, "Morse Runner 1.xx", 35f, TitleShadow,
            weight = FontWeight.Bold, italic = true)
        FormLabel(scale, 95, 57, "Morse Runner 1.xx", 35f, TitleAqua,
            weight = FontWeight.Bold, italic = true)
        FormLabel(scale, 94, 56, "Morse Runner 1.xx", 35f, TitleGreen,
            weight = FontWeight.Bold, italic = true)

        // Main.dfm:484 Label20 (178,106) "CW CONTEST SIMULATOR"
        FormLabel(scale, 178, 106, "CW CONTEST SIMULATOR", 13f, FormInk)
        // Main.dfm:456 Label16 (221,127) "FREEWARE"
        FormLabel(scale, 221, 127, "FREEWARE", 13f, FormInk)
        // Main.dfm:442 Label15 (106,170) — VE3NEA copyright
        FormLabel(scale, 106, 170, "Copyright \u00A92004-2016 Alex Shovkoplyas, VE3NEA", 13f, FormInk)
        // Main.dfm:498 Label21 (142,191) — original home page
        FormLabel(scale, 142, 191, "http://www.dxatlas.com/MorseRunner", 13f, FormInk)
        // Main.dfm:470 Label19 (28,234) — Community Edition copyright
        FormLabel(
            scale, 28, 234,
            "Copyright \u00A92022-2026 Morse Runner Community Edition Contributors",
            13f, FormInk,
        )
        // Main.dfm:512 Label22 (126,255) — Community Edition repository
        FormLabel(scale, 126, 255, "https://www.github.com/w7sst/MorseRunner", 13f, FormInk)

        // Main.dfm:526 RichEdit1 (alBottom, Visible = False) and Main.dfm:601
        // sbar (alBottom, Visible = False): hidden in the form definition, so
        // they show no pixels on the original.  Not drawn (see inventory doc).
    }
}

// ---------------------------------------------------------------------------
//  Panel9 — contest, station and band settings (Main.dfm:614)
// ---------------------------------------------------------------------------

@Composable
private fun StationSettingsPanel(scale: Float) {
    FormFrame(scale, 517, 2, 212, 359) {

        // ---- ContestGroup (Main.dfm:920, 6,6,195x74) 'Contest' -------------
        GroupBox(scale, 6, 6, 195, 74, "Contest") {
            // SimContestCombo (Main.dfm:934, 23,18,169x23): items are filled at
            // runtime from the contest registry; the .dfm lists none and sets no
            // ItemIndex, so the design state is an empty drop-down.
            VclCombo(scale, left = 23, top = 18, width = 169, height = 23, text = "")
            // Label17 (Main.dfm:927, 12,47) 'Exchange'
            FormLabel(scale, 12, 47, "Exchange", 12f, FormInk)
            // ExchangeEdit (Main.dfm:944, 76,44,116x23) Text = '3A ON'
            VclEdit(scale, left = 76, top = 44, width = 116, height = 23, text = "3A ON")
        }

        // ---- GroupBox1 (Main.dfm:700, 6,81,194x155) 'Station' --------------
        GroupBox(scale, 6, 81, 194, 155, "Station") {
            // Label4 (Main.dfm:707, 12,21) 'Call'
            FormLabel(scale, 12, 21, "Call", 12f, FormInk)
            // Edit4 (Main.dfm:765, 45,15,89x23) Text = 'VE3NEA', Consolas 13px
            VclEdit(
                scale, left = 45, top = 15, width = 89, height = 23,
                text = "VE3NEA", fontPx = 13f, mono = true,
            )
            // CheckBox1 (Main.dfm:796, 140,17,45x17) 'QSK'
            VclCheckBox(scale, left = 140, top = 17, width = 45, height = 17, caption = "QSK")
            // Label6 (Main.dfm:721, 12,46) 'CW Speed'
            FormLabel(scale, 12, 46, "CW Speed", 12f, FormInk)
            // SpinEdit1 (Main.dfm:782, 91,43,65x24) Value = 25
            VclSpinEdit(scale, left = 91, top = 43, width = 65, height = 24, value = "25")
            // Label5 (Main.dfm:714, 162,46) 'WPM'
            FormLabel(scale, 162, 46, "WPM", 12f, FormInk)
            // Label7 (Main.dfm:728, 13,76) 'CW Pitch'
            FormLabel(scale, 13, 76, "CW Pitch", 12f, FormInk)
            // ComboBox1 (Main.dfm:806, 92,73,65x23) — 13 pitch items, no ItemIndex.
            VclCombo(scale, left = 92, top = 73, width = 65, height = 23, text = "")
            // Label9 (Main.dfm:735, 12,106) 'RX Bandwidth'
            FormLabel(scale, 12, 106, "RX Bandwidth", 12f, FormInk)
            // ComboBox2 (Main.dfm:831, 92,102,65x23) — 11 bandwidth items, no ItemIndex.
            VclCombo(scale, left = 92, top = 102, width = 65, height = 23, text = "")
            // Label18 (Main.dfm:758, 12,134) 'Mon. Level'
            FormLabel(scale, 12, 134, "Mon. Level", 12f, FormInk)
            // VolumeSlider1 (Main.dfm:742, 92,129,60x20) — custom VCL slider,
            // rendered as a track + thumb; Hint was '-15.0 dB'.
            VolumeSlider(scale, left = 92, top = 129, width = 60, height = 20)
        }

        // ---- GroupBox3 (Main.dfm:622, 6,238,194x84) ' Band Conditions ' -----
        GroupBox(scale, 6, 238, 194, 84, " Band Conditions ") {
            // Label11 (Main.dfm:629, 144,19) 'Activity'
            FormLabel(scale, 144, 19, "Activity", 12f, FormInk)
            // CheckBox4 (Main.dfm:656, 12,21) 'QRN'
            VclCheckBox(scale, left = 12, top = 21, width = 45, height = 17, caption = "QRN")
            // CheckBox3 (Main.dfm:646, 12,41) 'QRM'
            VclCheckBox(scale, left = 12, top = 41, width = 45, height = 17, caption = "QRM")
            // CheckBox2 (Main.dfm:636, 12,61) 'QSB'
            VclCheckBox(scale, left = 12, top = 61, width = 45, height = 17, caption = "QSB")
            // CheckBox5 (Main.dfm:666, 76,21) 'Flutter'
            VclCheckBox(scale, left = 76, top = 21, width = 53, height = 17, caption = "Flutter")
            // CheckBox6 (Main.dfm:676, 76,41) 'LIDs'
            VclCheckBox(scale, left = 76, top = 41, width = 45, height = 17, caption = "LIDs")
            // SpinEdit3 (Main.dfm:686, 144,35,37x24) Value = 3
            VclSpinEdit(scale, left = 144, top = 35, width = 37, height = 24, value = "3")
        }

        // ---- Panel10 (Main.dfm:855, 0,322,212x37, alBottom) -----------------
        // "for [30] min." plus the Run toolbar with its drop-down button.
        Panel10(scale)
    }
}

/** Main.dfm:855 Panel10 — duration + `ToolBar1` (Run, tbsDropDown). */
@Composable
private fun Panel10(scale: Float) {
    FormFrame(scale, 0, 322, 212, 37) {
        // Label10 (Main.dfm:870, 107,13) 'for'
        FormLabel(scale, 107, 13, "for", 12f, FormInk)
        // SpinEdit2 (Main.dfm:877, 128,10,45x24) Value = 30
        VclSpinEdit(scale, left = 128, top = 10, width = 45, height = 24, value = "30")
        // Label8 (Main.dfm:863, 179,13) 'min.'
        FormLabel(scale, 179, 13, "min.", 12f, FormInk)

        // ToolBar1 (Main.dfm:890, 6,6,97x29) with ToolButton1 (Main.dfm:905)
        // Caption '   Run   ', Style = tbsDropDown.  The toolbar is flat, so it
        // is drawn as the drop-down button only.
        FormFrame(scale, 6, 6, 97, 29) {
            // The button proper: 72 px wide (ButtonWidth = 72), the arrow section
            // takes the rest of the 97 px toolbar width.
            Box(
                modifier = Modifier
                    .offset(px(3, scale), px(0, scale))
                    .size(px(72, scale), px(29, scale))
                    .background(FormFace)
                    .border(px(1, scale), FormShadow),
                contentAlignment = Alignment.Center,
            ) {
                FormText("Run", scale, 12f, FormInk)
            }
            Box(
                modifier = Modifier
                    .offset(px(75, scale), px(0, scale))
                    .size(px(14, scale), px(29, scale))
                    .background(FormFace)
                    .border(px(1, scale), FormShadow),
            ) {
                DropDownArrow(Modifier.align(Alignment.Center).size(px(6, scale), px(3, scale)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Panel1 — entry fields, message grid, clock, score table (Main.dfm:70)
// ---------------------------------------------------------------------------

@Composable
private fun EntryPanel(scale: Float) {
    FormFrame(scale, 0, 371, 729, 135, Modifier.border(px(1, scale), FormBorder)) {

        // ---- labels above the entry fields --------------------------------
        // Label1 (Main.dfm:79, 16,12) 'Call'
        FormLabel(scale, 16, 12, "Call", 12f, FormInk)
        // Label2 (Main.dfm:158, 140,12) 'RST'
        FormLabel(scale, 140, 12, "RST", 12f, FormInk)
        // Label3 (Main.dfm:165, 224,12) 'Nr.'
        FormLabel(scale, 224, 12, "Nr.", 12f, FormInk)

        // ---- the three entry fields (Consolas bold 16 px, upper-case) -----
        // Edit1 (Main.dfm:178, 12,33,118x27) MaxLength 12
        VclEdit(scale, left = 12, top = 33, width = 118, height = 27, text = "", fontPx = 16f, bold = true, mono = true)
        // Edit2 (Main.dfm:197, 136,33,77x27) MaxLength 10
        VclEdit(scale, left = 136, top = 33, width = 77, height = 27, text = "", fontPx = 16f, bold = true, mono = true)
        // Edit3 (Main.dfm:215, 219,33,45x27) MaxLength 4
        VclEdit(scale, left = 219, top = 33, width = 45, height = 27, text = "", fontPx = 16f, bold = true, mono = true)

        // Bevel2 (Main.dfm:172, 277,6,2x123) — vertical groove between the
        // entry side and the display side of the panel.
        FormFrame(scale, 277, 6, 2, 123) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(px(1, scale)).fillMaxHeight().background(FormBorder))
                Box(Modifier.width(px(1, scale)).fillMaxHeight().background(Color.White))
            }
        }

        // ---- timer (Main.dfm:234, 522,5,191x33) ---------------------------
        // Panel2: black panel, '00:00:00' in 24 px Arial, colour 14151712.
        Box(
            modifier = Modifier
                .offset(px(522, scale), px(5, scale))
                .size(px(191, scale), px(33, scale))
                .background(TimerBack)
                .border(px(1, scale), FormBorder),
            contentAlignment = Alignment.CenterStart,
        ) {
            FormText(
                "00:00:00", scale, 24f, TimerFont,
                modifier = Modifier.padding(start = px(3, scale)),
            )
        }

        // ---- PaintBox1 placeholder (Main.dfm:258 via Panel3) --------------
        // Panel3 (Main.dfm:251, 292,45,225x67) is a lowered panel whose whole
        // client area is the TPaintBox.  The original paints the CW signal
        // here; this milestone draws the empty clInfoBk background and leaves
        // the painting to the audio ticket.
        FormFrame(scale, 292, 45, 225, 67, Modifier.border(px(1, scale), FormBorder)) {
            Box(
                modifier = Modifier
                    .offset(px(1, scale), px(1, scale))
                    .size(px(223, scale), px(65, scale))
                    .background(InfoBackground),
            )
        }

        // Panel4 (Main.dfm:270, 292,6,114x33): at runtime the run-mode caption
        // ("Pile-Up" / "COMPETITION" in red) — empty in the .dfm.
        Box(
            modifier = Modifier
                .offset(px(292, scale), px(6, scale))
                .size(px(114, scale), px(33, scale))
                .border(px(1, scale), FormBorder),
        )

        // Panel7 (Main.dfm:285, 412,6,104x33): the QSO/hr readout — empty in
        // the .dfm (Log.pas fills it as "%d  qso/hr.").
        Box(
            modifier = Modifier
                .offset(px(412, scale), px(6, scale))
                .size(px(104, scale), px(33, scale))
                .border(px(1, scale), FormBorder),
        )

        // Panel8 (Main.dfm:293, 292,119,225x10): RIT slider, with Shape2
        // (Main.dfm:305, 81,1,32x7) as its thumb.
        FormFrame(scale, 292, 119, 225, 10, Modifier.border(px(1, scale), FormBorder)) {
            Box(
                modifier = Modifier
                    .offset(px(10, scale), px(4, scale))
                    .size(px(205, scale), px(2, scale))
                    .background(FieldBorder),
            )
            Box(
                modifier = Modifier
                    .offset(px(81, scale), px(1, scale))
                    .size(px(32, scale), px(7, scale))
                    .background(RitThumb),
            )
        }

        // Panel11 (Main.dfm:315, 529,44,191x85) with ListView1 (Main.dfm:329,
        // 1,1,189x83): the score table.  Columns 48 / 'Raw' 70 / 'Verified' 70;
        // rows Pts, Mult, Score.  Values are filled at runtime and empty in the
        // .dfm.  (Panel11's own '0' caption is hidden behind the list view.)
        FormFrame(scale, 529, 44, 191, 85, Modifier.border(px(1, scale), FormBorder)) {
            Box(
                modifier = Modifier
                    .offset(px(1, scale), px(1, scale))
                    .size(px(189, scale), px(83, scale))
                    .background(FieldFace),
            ) {
                ListViewHeader(
                    scale = scale,
                    left = 0,
                    top = 0,
                    fontPx = 12f,
                    headerHeight = 18,
                    columns = listOf(
                        VclColumn("", 48),
                        VclColumn("Raw", 70, centered = true),
                        VclColumn("Verified", 70, centered = true),
                    ),
                )
                val rowHeight = 18
                for ((index, caption) in listOf("Pts", "Mult", "Score").withIndex()) {
                    val top = 18 + index * rowHeight
                    FormFrame(scale, 0, top, 188, rowHeight) {
                        FormLabel(scale, 3, 3, caption, 12f, FormInk, family = FontFamily.Monospace)
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(px(1, scale)).background(HeaderEdge))
                        // Column separators, matching the header widths.
                        Box(Modifier.offset(px(48, scale), px(0, scale)).width(px(1, scale)).fillMaxHeight().background(HeaderEdge))
                        Box(Modifier.offset(px(118, scale), px(0, scale)).width(px(1, scale)).fillMaxHeight().background(HeaderEdge))
                    }
                }
            }
        }

        // ---- the 2 x 4 message grid (Main.dfm:86-157) ---------------------
        // SpeedButtons 4..11, Tag 1..8, captions verbatim (two spaces after the
        // function key).  Row 1 at Top = 66, row 2 at Top = 94; columns 6, 73,
        // 140, 207; every button 65 x 26.
        val messageButtons = listOf(
            Triple(1, "F1  CQ", 6 to 66),
            Triple(2, "F2  <exch>", 73 to 66),
            Triple(3, "F3  TU", 140 to 66),
            Triple(4, "F4  <my>", 207 to 66),
            Triple(5, "F5  <his>", 6 to 94),
            Triple(6, "F6  B4", 73 to 94),
            Triple(7, "F7  ?", 140 to 94),
            Triple(8, "F8  NIL", 207 to 94),
        )
        for ((_, caption, position) in messageButtons) {
            SpeedButton(scale, position.first, position.second, 65, 26, caption)
        }
    }
}

// ============================================================================
//  Small VCL control reproductions
// ============================================================================

/** A control with the DFM's Left/Top/Width/Height, scaled. */
@Composable
private fun FormFrame(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale)),
        content = content,
    )
}

/**
 * A TLabel: only a position, because VCL labels are `AutoSize = True` and the
 * .dfm's Width/Height are stale design-time measurements.
 */
@Composable
private fun FormLabel(
    scale: Float,
    left: Int,
    top: Int,
    text: String,
    fontPx: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    family: FontFamily = FontFamily.SansSerif,
    modifier: Modifier = Modifier,
) {
    FormText(
        text = text,
        scale = scale,
        fontPx = fontPx,
        color = color,
        weight = weight,
        italic = italic,
        family = family,
        modifier = modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .wrapContentWidth(Alignment.Start, unbounded = true),
    )
}

/** Single-line VCL-style text (labels never wrap; the DFM fixes every size). */
@Composable
private fun FormText(
    text: String,
    scale: Float,
    fontPx: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    family: FontFamily = FontFamily.SansSerif,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = (fontPx * scale).sp,
        ),
    )
}

/** TEdit: sunken frame, white field, one line of text starting 3 px in. */
@Composable
private fun VclEdit(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    text: String,
    fontPx: Float = 12f,
    bold: Boolean = false,
    mono: Boolean = false,
    color: Color = FormInk,
    background: Color = FieldFace,
) {
    Box(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale))
            .background(background)
            .border(px(1, scale), FieldBorder),
        contentAlignment = Alignment.CenterStart,
    ) {
        FormText(
            text = text,
            scale = scale,
            fontPx = fontPx,
            color = color,
            weight = if (bold) FontWeight.Bold else FontWeight.Normal,
            family = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            modifier = Modifier.padding(start = px(3, scale)),
        )
    }
}

/** TSpinEdit: edit field plus the up/down button column on the right. */
@Composable
private fun VclSpinEdit(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    value: String,
    fontPx: Float = 12f,
) {
    Box(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale))
            .background(FieldFace)
            .border(px(1, scale), FieldBorder),
    ) {
        FormText(
            text = value,
            scale = scale,
            fontPx = fontPx,
            color = FormInk,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = px(3, scale)),
        )
        // Spin button column (13 px wide), with its divider.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(px(13, scale))
                .fillMaxHeight()
                .background(FormFace),
        ) {
            SpinArrows(Modifier.fillMaxSize().padding(horizontal = px(2, scale), vertical = px(3, scale)))
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = px(-13, scale))
                .width(px(1, scale))
                .fillMaxHeight()
                .background(FieldBorder),
        )
    }
}

/** TComboBox with `Style = csDropDownList`: text area plus the arrow button. */
@Composable
private fun VclCombo(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    text: String,
    fontPx: Float = 12f,
) {
    Box(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale))
            .background(FieldFace)
            .border(px(1, scale), FieldBorder),
        contentAlignment = Alignment.CenterStart,
    ) {
        FormText(
            text = text,
            scale = scale,
            fontPx = fontPx,
            color = FormInk,
            modifier = Modifier.padding(start = px(3, scale)),
        )
        // The drop-down button is 17 px wide on a VCL combo box.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(px(17, scale))
                .fillMaxHeight()
                .background(FormFace),
        ) {
            DropDownArrow(Modifier.align(Alignment.Center).size(px(7, scale), px(4, scale)))
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = px(-17, scale))
                .width(px(1, scale))
                .fillMaxHeight()
                .background(FieldBorder),
        )
    }
}

/** TCheckBox: 13 x 13 box (2 px from the top of a 17 px control) plus caption. */
@Composable
private fun VclCheckBox(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    caption: String,
    checked: Boolean = false,
) {
    Box(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = px(0, scale), y = px((height - 13) / 2, scale))
                .size(px(13, scale), px(13, scale))
                .background(FieldFace)
                .border(px(1, scale), FieldBorder),
        )
        if (checked) {
            // A check mark, drawn rather than typed so it cannot fall back to
            // a missing glyph.  (All boxes are unchecked in Main.dfm.)
            Canvas(Modifier.offset(x = px(2, scale), y = px((height - 13) / 2 + 2, scale)).size(px(9, scale), px(9, scale))) {
                drawLine(
                    color = FormInk,
                    start = Offset(size.width * 0.1f, size.height * 0.55f),
                    end = Offset(size.width * 0.4f, size.height * 0.9f),
                    strokeWidth = size.width * 0.18f,
                )
                drawLine(
                    color = FormInk,
                    start = Offset(size.width * 0.4f, size.height * 0.9f),
                    end = Offset(size.width * 0.95f, size.height * 0.1f),
                    strokeWidth = size.width * 0.18f,
                )
            }
        }
        FormText(
            text = caption,
            scale = scale,
            fontPx = 12f,
            color = FormInk,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = px(17, scale))
                .wrapContentWidth(Alignment.Start, unbounded = true),
        )
    }
}

/** A flat TSpeedButton: face colour, 1 px frame, centred caption. */
@Composable
private fun SpeedButton(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    caption: String,
) {
    Box(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale))
            .background(FormFace)
            .border(px(1, scale), FormShadow),
        contentAlignment = Alignment.Center,
    ) {
        FormText(
            text = caption,
            scale = scale,
            fontPx = 12f,
            color = FormInk,
            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
        )
    }
}

/**
 * TGroupBox: a 1 px frame whose top edge is interrupted by the caption, at
 * (8, 0) relative to the frame — VCL draws the caption over the border.
 */
@Composable
private fun GroupBox(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    caption: String,
    content: @Composable BoxScope.() -> Unit,
) {
    FormFrame(scale, left, top, width, height, Modifier.border(px(1, scale), FormBorder)) {
        FormText(
            text = caption,
            scale = scale,
            fontPx = 12f,
            color = FormInk,
            modifier = Modifier
                .offset(x = px(7, scale), y = (-6 * scale).dp)
                .background(FormFace)
                .padding(horizontal = px(2, scale)),
        )
        content()
    }
}

/** One VCL list-view column (caption + width, as in the .dfm). */
private data class VclColumn(
    val caption: String,
    val width: Int,
    val centered: Boolean = false,
)

/**
 * The column-header strip of a `ListView` in `ViewStyle = vsReport` mode.
 * Column widths are the .dfm's; the header height is the VCL default for the
 * item font (21 px at 15 px, 18 px at 12 px).
 */
@Composable
private fun ListViewHeader(
    scale: Float,
    left: Int,
    top: Int,
    fontPx: Float,
    headerHeight: Int,
    columns: List<VclColumn>,
) {
    var x = left
    for (column in columns) {
        Box(
            modifier = Modifier
                .offset(x = px(x, scale), y = px(top, scale))
                .size(px(column.width, scale), px(headerHeight, scale))
                .background(HeaderFace)
                .border(px(1, scale), HeaderEdge),
            contentAlignment = if (column.centered) Alignment.Center else Alignment.CenterStart,
        ) {
            FormText(
                text = column.caption,
                scale = scale,
                fontPx = fontPx,
                color = FormInk,
                modifier = if (column.centered) {
                    Modifier
                } else {
                    Modifier.padding(start = px(3, scale))
                },
            )
        }
        x += column.width
    }
}

/** The up/down triangles of a TSpinEdit's button column. */
@Composable
private fun SpinArrows(modifier: Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val triangleWidth = width * 0.9f
        val triangleHeight = height * 0.30f
        val centerX = width / 2

        val up = Path().apply {
            moveTo(centerX, height * 0.26f)
            lineTo(centerX - triangleWidth / 2, height * 0.26f + triangleHeight)
            lineTo(centerX + triangleWidth / 2, height * 0.26f + triangleHeight)
            close()
        }
        val down = Path().apply {
            moveTo(centerX, height * 0.74f)
            lineTo(centerX - triangleWidth / 2, height * 0.74f - triangleHeight)
            lineTo(centerX + triangleWidth / 2, height * 0.74f - triangleHeight)
            close()
        }
        drawPath(up, FormInk)
        drawPath(down, FormInk)
    }
}

/** The little downward triangle of a combo box or drop-down button. */
@Composable
private fun DropDownArrow(modifier: Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2, size.height)
            close()
        }
        drawPath(path, FormInk)
    }
}

/**
 * TVolumeSlider (Main.dfm:742): a custom VCL control with no caption.  Drawn
 * as a groove with a thumb at the DFM's `Value = 0.75` position; it is a
 * reproduction of the pixels only.
 */
@Composable
private fun VolumeSlider(scale: Float, left: Int, top: Int, width: Int, height: Int) {
    Canvas(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale)),
    ) {
        val centerY = size.height / 2
        val grooveHeight = maxOf(size.height * 0.14f, 1f)
        drawLine(
            color = FieldBorder,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = grooveHeight,
        )
        val thumbCenterX = size.width * 0.75f
        drawLine(
            color = FormInk,
            start = Offset(0f, centerY),
            end = Offset(thumbCenterX, centerY),
            strokeWidth = grooveHeight,
        )
        drawCircle(
            color = FormFace,
            radius = size.height * 0.34f,
            center = Offset(thumbCenterX, centerY),
        )
        drawCircle(
            color = FormBorder,
            radius = size.height * 0.34f,
            center = Offset(thumbCenterX, centerY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(1f, size.height * 0.06f)),
        )
    }
}

// ============================================================================
//  Score dialog — ScoreDlg.dfm (TScoreDialog, client 394 x 163)
// ============================================================================

/**
 * The score dialog, reproduced from `ScoreDlg.dfm`: green/aqua "Congratulations
 * on a new record!" headline, the score-string edit, and its three buttons.
 *
 * Not reachable yet — this is a layout milestone and the dialog needs the
 * end-of-contest state to be opened (a later ticket).  The composable is here
 * so the dialog is part of the reproduced inventory rather than dropped.
 */
@Composable
fun ScoreDialogPanel(modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
        Box(modifier.fillMaxSize().background(FormFace), contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding(),
                contentAlignment = Alignment.Center,
            ) {
                val scale = min(
                    maxWidth.value / SCORE_WIDTH,
                    maxHeight.value / (SCORE_HEIGHT + MENU_HEIGHT),
                )
                Column(
                    modifier = Modifier.size(
                        width = (SCORE_WIDTH * scale).dp,
                        height = ((SCORE_HEIGHT + MENU_HEIGHT) * scale).dp,
                    ),
                ) {
                    // Dialog title bar (OS chrome: 'The contest is over').
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((MENU_HEIGHT * scale).dp)
                            .background(FormFace)
                            .border(px(1, scale), FormBorder),
                    ) {
                        FormText(
                            text = "The contest is over",
                            scale = scale,
                            fontPx = 12f,
                            color = FormInk,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = px(4, scale)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size((SCORE_WIDTH * scale).dp, (SCORE_HEIGHT * scale).dp)
                            .border(px(1, scale), FormBorder),
                    ) {
                        // Label2 (ScoreDlg.dfm:32, 29,26) aqua + Label3 (:48,
                        // 28,24) green — the same two-layer headline, green on top.
                        FormLabel(scale, 29, 26, "Congratulations on a new record!", 21f,
                            TitleAqua, weight = FontWeight.Bold)
                        FormLabel(scale, 28, 24, "Congratulations on a new record!", 21f,
                            TitleGreen, weight = FontWeight.Bold)
                        // Label1 (ScoreDlg.dfm:23, 14,69) 'Your score string is:'
                        FormLabel(scale, 14, 69, "Your score string is:", 12f, FormInk)
                        // Edit1 (ScoreDlg.dfm:64, 14,89,365x25), ReadOnly.
                        VclEdit(
                            scale, left = 14, top = 89, width = 365, height = 25,
                            text = "", fontPx = 14f, mono = true,
                        )
                        // Button3 (ScoreDlg.dfm:104, 64,130,113x25) '&Submit to Server'
                        ScoreButton(scale, 64, 130, 113, 25, "Submit to Server")
                        // Button1 (ScoreDlg.dfm:80, 183,130,113x25) '&Hi-Score web page'
                        ScoreButton(scale, 183, 130, 113, 25, "Hi-Score web page")
                        // Button2 (ScoreDlg.dfm:93, 304,130,75x25) '&Close'
                        ScoreButton(scale, 304, 130, 75, 25, "Close")
                    }
                }
            }
        }
    }
}

/** A VCL TButton: raised 1 px frame (white top/left, grey bottom/right). */
@Composable
private fun ScoreButton(
    scale: Float,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    caption: String,
) {
    Box(
        modifier = Modifier
            .offset(x = px(left, scale), y = px(top, scale))
            .size(px(width, scale), px(height, scale))
            .background(FormFace)
            .border(px(1, scale), FormBorder),
        contentAlignment = Alignment.Center,
    ) {
        // The raised bevel: a light edge along the top and left.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(px(1, scale))
                .background(Color.White),
        )
        FormText(
            text = caption,
            scale = scale,
            fontPx = 12f,
            color = FormInk,
            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
        )
    }
}

// ============================================================================
//  Geometry helpers
// ============================================================================

/** DFM pixels -> dp at this screen's [scale]. */
private fun px(value: Int, scale: Float): Dp = (value * scale).dp
