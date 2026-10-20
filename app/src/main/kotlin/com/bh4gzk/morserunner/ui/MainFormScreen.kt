package com.bh4gzk.morserunner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bh4gzk.morserunner.MorseCore
import kotlin.math.min

/*
 * ============================================================================
 *  MainFormScreen — the original Morse Runner form, reproduced in Compose.
 * ============================================================================
 *
 * Source of truth: `upstream/MorseRunnerCE/Main.dfm` (Delphi form `TMainForm`).
 * Design-time client area is **729 x 506 px** (`.dfm` line 7-8) — note that this
 * is *not* the 1024x768 quoted in the task brief; the brief's figure does not
 * exist anywhere in the .dfm and is not used here.  Every Left/Top/Width/Height
 * below is copied from that file (line numbers in the comments) and multiplied
 * by [SCALE].
 *
 * # The one scale factor
 *
 *     SCALE = 0.80   ( dp per design pixel )
 *
 * Why 0.80 and not `phoneWidth / 729`:
 *   - The form's main column (`Panel6`, 517 px) and its two list views are
 *     `alClient`; they stretch, so their source width does not constrain the
 *     scale.  The narrowest fixed row does: `Panel4` (114) + `Panel7` (104) +
 *     `Panel2` (191) = 409 px, plus gaps -> 417 design px.  This phone is
 *     720 x 1600 px @ 320 dpi = 360 x 800 dp, i.e. 352 dp of usable width with
 *     the 4 dp gutters; 417 * 0.80 = 334 dp fits, 417 * 0.85 = 354 dp would not.
 *   - 15 px captions become 12 sp, the 24 px clock 19.2 sp, the 35 px logo
 *     28 sp, the 16 px Consolas entry fields 12.8 sp.  That is the largest
 *     scale at which the form's own type sizes are still comfortable on a
 *     360 dp-wide screen.
 *   - On a wider phone the same constant is kept and the alClient rows simply
 *     stretch further; on a narrower one [FormScale] clamps the constant down
 *     (`min(SCALE, maxWidth / 424)`) so nothing is ever clipped.
 *
 * # What moved, and why
 *
 * The original is a **landscape** 729 x 506 desktop form with three vertical
 * bands side by side: the client area (517 wide), the right column `Panel9`
 * (212 wide) and, below both, `Panel1` (729 wide).  729 px at 0.80 is 583 dp —
 * wider than the phone.  Side by side is therefore impossible without a second,
 * smaller scale, so the three bands are **stacked vertically** in the order a
 * user reads them (client area -> right column -> bottom panel), and each band
 * is wrapped onto as few phone-width rows as its own geometry allows:
 *
 *   - `Panel9`'s four group boxes are 194-195 px wide each (156 dp at 0.80):
 *     `Contest` + `Run` share one row, `Station` + `Band Conditions` the next.
 *     Their internal absolute offsets are untouched.
 *   - `Panel1`'s left third (`Edit1..3`, the F1-F8 speed buttons), its middle
 *     (`Panel4` mode caption, `Panel7`, `Panel2` clock, `PaintBox1` histogram,
 *     `Panel8` RIT) and its right third (`Panel11` score grid) become three
 *     stacked rows; the F-key buttons and the three entry fields stretch to the
 *     phone width (they are `alNone` in the .dfm, but a 12 dp-wide `Nr.` box
 *     would be unusable, so their *proportions* are kept and only the
 *     horizontal extent grows).
 *   - The design-time logo block (`Shape1` + `Label12..22`, the idle screen)
 *     and the run-time CW pane (`RichEdit1`) both cover the same client area in
 *     the original (every one of them is `alClient`; `Main.pas:436` switches
 *     the pane to `alClient`).  They are stacked here — logo first, then the
 *     pane — because the phone has the vertical room and an overlay would hide
 *     the logo entirely.
 *
 * All absolute child positions inside the groups are the .dfm's, scaled; only
 * the band stacking and the stretched/alClient rows depart from it.  The
 * per-control audit trail is `.scratch/ui/dfm-inventory.md`.
 *
 * # Placeholder content
 *
 * This milestone is layout only: no engine, no audio, no session.  The CW pane
 * and the rate histogram carry fixed sample content (marked below); the QSO log
 * is empty exactly as in the .dfm (it is `ListView2.Clear` until a run starts);
 * the score grid has the .dfm's own Pts/Mult/Score rows with empty values.
 * The contest combo is real: its items come from [MorseCore.contestName].
 */

/**
 * The single geometry scale: dp per design pixel.  See the file header for why
 * 0.80 and for the phone geometry (360 x 800 dp) it was chosen against.
 */
private const val SCALE = 0.80f

/** Width of the widest fixed row (Panel4 + Panel7 + Panel2 + gaps), design px. */
private const val WIDEST_FIXED_ROW = 417f

// ---------------------------------------------------------------------------
// VCL colours, taken from the .dfm (TColor has R in the low byte)
// ---------------------------------------------------------------------------
private val FormFace = Color(0xFFF0F0F0)      // clBtnFace
private val FormInk = Color(0xFF000000)       // clWindowText
private val FormEdge = Color(0xFF808080)      // panel bevel, dark edge
private val FormHighlight = Color(0xFFFFFFFF) // panel bevel, light edge
private val GroupEdge = Color(0xFFA0A0A0)     // group-box / list-view frame
private val FieldFace = Color(0xFFFFFFFF)     // clWindow: edits, lists
private val FieldEdge = Color(0xFF7F7F7F)     // edit frame
private val ClientBackground = Color(0xFFE9FEFE) // Shape1.Brush.Color = 16711401 -> #E9FEFE
private val InfoBackground = Color(0xFFFFFFE1)   // clInfoBk: PaintBox1
private val TimerBack = Color(0xFF000000)     // Panel2.Color = clBackground
private val TimerFont = Color(0xFF20F0D7)     // Panel2.Font.Color = 14151712 -> #20F0D7
private val TitleGreen = Color(0xFF008000)    // Label13 clGreen
private val TitleAqua = Color(0xFF00FFFF)     // Label12 clAqua
private val TitleShadow = Color(0xFFBCBCBC)   // Label14.Font.Color = 12369084 -> #BCBCBC
private val RitThumb = Color(0xFF1FE0C4)      // Shape2.Brush.Color = 12902431 -> #1FE0C4
private val ModeGreen = Color(0xFF008000)     // Panel4 runtime colour when not COMPETITION
private val HeaderFace = Color(0xFFF0F0F0)    // list-view column header

// ---------------------------------------------------------------------------
// Scaling helpers.  A single CompositionLocal carries the (clamped) scale, so
// every geometric value below is written in design pixels and never in dp.
// ---------------------------------------------------------------------------
private val FormScale = staticCompositionLocalOf { SCALE }

/** Design pixels -> dp. */
private val Float.px: Dp
    @Composable get() = (this * FormScale.current).dp

/** Design pixels -> sp (the screen is composed with fontScale = 1, so 1 sp == 1 dp). */
private val Float.pt: TextUnit
    @Composable get() = (this * FormScale.current).sp

/**
 * The form.  Everything is on one vertical scroll: at SCALE the stacked bands
 * are ~810 dp tall against this phone's ~780 dp of usable height.
 */
@Composable
fun MainFormScreen(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // fontScale = 1 keeps `sp` == `dp`, so the .dfm's font pixel heights scale
    // by exactly SCALE and never by the user's system font preference.
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = 1f),
    ) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(FormFace)
                .safeDrawingPadding(),
        ) {
            val clamped = min(SCALE, maxWidth.value / WIDEST_FIXED_ROW)
            CompositionLocalProvider(FormScale provides clamped) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Main.dfm:62 Bevel1 (0,0,729x2, alTop) — the etched rule at the top.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2f.px)
                            .background(FormEdge),
                    )
                    Spacer(Modifier.height(4.dp))

                    QsoLogListView()

                    // Main.dfm:545 ListView2 is alTop directly against the logo block;
                    // Main.dfm:371 Panel5 (0,361,729x10) is an empty spacer band.
                    Spacer(Modifier.height(5f.px))

                    LogoBlock()

                    // Main.dfm:526 RichEdit1 (alBottom in design, alClient at runtime).
                    Spacer(Modifier.height(4.dp))
                    CwPane()

                    Spacer(Modifier.height(6.dp))

                    // Panel9 row 1: Main.dfm:920 ContestGroup + Main.dfm:855 Panel10.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ContestGroup()
                        RunControlsPanel()
                    }

                    Spacer(Modifier.height(6.dp))

                    // Panel9 row 2: Main.dfm:700 GroupBox1 + Main.dfm:622 GroupBox3.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StationGroup()
                        BandConditionsGroup()
                    }

                    Spacer(Modifier.height(6.dp))

                    // Panel1, left third: Main.dfm:178-233 Edit1..3 and their labels.
                    ExchangeRow()

                    Spacer(Modifier.height(4.dp))

                    // Panel1, F1-F8: Main.dfm:86-157 SpeedButton4..11.
                    SendButtons()

                    Spacer(Modifier.height(4.dp))

                    // Panel1, middle + clock: Main.dfm:270 Panel4, :285 Panel7, :234 Panel2.
                    ModeAndClockRow()

                    Spacer(Modifier.height(4.dp))

                    // Panel1, bottom right: Main.dfm:251 Panel3 (PaintBox1), :293 Panel8,
                    // :315 Panel11 (ListView1 score grid).
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column {
                            RateHistogramPanel()
                            Spacer(Modifier.height(2.dp))
                            RitBar()
                        }
                        ScoreGrid()
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:545 ListView2 — the QSO log (UTC/Call/Recv/Sent/Pref/Chk/Wpm).
// alClient in run mode; 54 px tall (header + 2 rows) in the idle design.
// ---------------------------------------------------------------------------
@Composable
private fun QsoLogListView() {
    // Column widths exactly as declared (80/104/90/84/(rest)/40/40 of 513 px).
    val columns = listOf(
        "UTC" to 80f,
        "Call" to 104f,
        "Recv" to 90f,
        "Sent" to 84f,
        "Pref" to 75f,
        "Chk" to 40f,
        "Wpm" to 40f,
    )
    val total = columns.sumOf { it.second.toDouble() }.toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .height(54f.px)
            .background(FieldFace)
            .border(1.dp, GroupEdge),
    ) {
        Row(Modifier.fillMaxWidth().height(15f.px).background(HeaderFace)) {
            columns.forEach { (name, w) ->
                Box(
                    Modifier
                        .weight(w / total)
                        .fillMaxSize()
                        .border(0.5.dp, GroupEdge),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // .dfm font is -15 px; 13 px here so the 40 px-wide Chk/Wpm
                    // columns still fit their captions at 344 dp of log width.
                    FormLabel(name, sizePx = 13f, modifier = Modifier.padding(start = 3f.px))
                }
            }
        }
        // ListView2 has no items in the .dfm (Main.pas:2107 ListView2.Clear) — the
        // idle log is an empty grid.  The body is left empty on purpose.
        Box(Modifier.fillMaxWidth().weight(1f).background(FieldFace))
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:389 Shape1 + :400-525 Label12..22 — the idle screen's logo block.
// Shape1 is alClient (513 x 238 design px) and is what the CW pane covers at
// run time; here the two are stacked instead of overlaid.
// ---------------------------------------------------------------------------
@Composable
private fun LogoBlock() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(238f.px)
            .background(ClientBackground)
            .border(1.dp, GroupEdge),
    ) {
        // The three title labels are the same string drawn three times: the
        // grey drop shadow (Label14, +6/+5), the aqua face (Label12, +1/+1)
        // and the green face (Label13, 0/0).  Horizontally centred because the
        // block is narrower than the .dfm's 513 px; the vertical offsets are
        // the .dfm's (label y minus Shape1 y = 54).
        FormLabel(
            "Morse Runner 1.xx",
            sizePx = 35f,
            color = TitleShadow,
            weight = FontWeight.Bold,
            italic = true,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = 6f.px, y = 7f.px),
        )
        FormLabel(
            "Morse Runner 1.xx",
            sizePx = 35f,
            color = TitleAqua,
            weight = FontWeight.Bold,
            italic = true,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = 1f.px, y = 3f.px),
        )
        FormLabel(
            "Morse Runner 1.xx",
            sizePx = 35f,
            color = TitleGreen,
            weight = FontWeight.Bold,
            italic = true,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 2f.px),
        )
        // Main.dfm:484 Label20 'CW CONTEST SIMULATOR' (178,106) -> y 52 inside Shape1.
        FormLabel(
            "CW CONTEST SIMULATOR",
            sizePx = 13f,
            family = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 52f.px),
        )
        // Main.dfm:456 Label16 'FREEWARE' (221,127) -> y 73.
        FormLabel(
            "FREEWARE",
            sizePx = 13f,
            family = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 73f.px),
        )
        // Main.dfm:442 Label15 copyright (106,170) -> y 116.
        FormLabel(
            "Copyright \u00A92004-2016 Alex Shovkoplyas, VE3NEA",
            sizePx = 13f,
            family = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 116f.px),
        )
        // Main.dfm:498 Label21 URL (142,191) -> y 137.
        FormLabel(
            "http://www.dxatlas.com/MorseRunner",
            sizePx = 13f,
            family = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 137f.px),
        )
        // Main.dfm:470 Label19 CE copyright (28,234) -> y 180.  66 characters do
        // not fit one 344 dp line at 10.4 sp, so this line wraps to two
        // (180..210 design px) and the GitHub URL moves from y 201 to y 214.
        FormLabel(
            "Copyright \u00A92022-2026 Morse Runner Community Edition Contributors",
            sizePx = 13f,
            family = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 180f.px),
        )
        // Main.dfm:512 Label22 URL (126,255) -> y 201, shifted to 214 for the wrap above.
        FormLabel(
            "https://www.github.com/w7sst/MorseRunner",
            sizePx = 13f,
            family = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 214f.px),
        )
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:526 RichEdit1 — the simulated CW stream (TRichEdit, Consolas -13).
// 41 px in the design, alClient at run time (Main.pas:436); 92 dp here so that
// four lines of the stream are readable.
// ---------------------------------------------------------------------------
@Composable
private fun CwPane() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(92.dp)
            .background(FieldFace)
            .border(1.dp, FieldEdge)
            .padding(horizontal = 4f.px, vertical = 3f.px),
    ) {
        // Placeholder: layout milestone only, no engine is running and no .lst
        // is loaded (Main.pas:2263 would load one into this pane).
        FormLabel(
            "K1ABC: CQ TEST K1ABC K\n" +
                "K1ABC: K1ABC\n" +
                "K1ABC: 5NN 05\n" +
                "K1ABC: TU K1ABC",
            sizePx = 13f,
            family = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:920 ContestGroup (6,6,195x74) — SimContestCombo, 'Exchange', ExchangeEdit.
// The combo is the one control with live data: MorseCore.contestName(0..count-1).
// ---------------------------------------------------------------------------
@Composable
private fun ContestGroup() {
    val contests = remember { contestNames() }
    var selected by remember { mutableStateOf(0) }
    VclGroupBox("Contest", widthPx = 195f, heightPx = 74f) {
        VclCombo(
            items = contests,
            selectedIndex = selected,
            onSelect = { selected = it },
            widthPx = 169f,
            xPx = 23f,
            yPx = 18f,
        )
        FormLabel(
            "Exchange",
            modifier = Modifier.offset(x = 12f.px, y = 47f.px),
        )
        VclEdit(
            "3A ON",
            widthPx = 116f,
            heightPx = 23f,
            xPx = 76f,
            yPx = 44f,
        )
    }
}

/** Contest names from the Pascal core; never hard-coded.  Empty if the .so is missing. */
private fun contestNames(): List<String> = runCatching {
    (0 until MorseCore.contestCount()).mapNotNull { MorseCore.contestName(it) }
}.getOrElse { emptyList() }

// ---------------------------------------------------------------------------
// Main.dfm:855 Panel10 (0,322,212x37) — ToolBar1 '   Run   ', 'for', SpinEdit2 (30), 'min.'.
// Panel10 is alBottom in Panel9; here it sits beside ContestGroup, same band.
// ---------------------------------------------------------------------------
@Composable
private fun RunControlsPanel() {
    Box(Modifier.size(212f.px, 74f.px)) {
        VclSpeedButton(
            "   Run   ",
            Modifier
                .offset(x = 6f.px, y = 6f.px)
                .size(97f.px, 29f.px),
        )
        // Drop-down arrow of the tbsDropDown tool button.
        Canvas(
            Modifier
                .offset(x = 92f.px, y = 18f.px)
                .size(8f.px, 6f.px),
        ) {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                },
                FormInk,
            )
        }
        FormLabel("for", modifier = Modifier.offset(x = 107f.px, y = 13f.px))
        VclSpinEdit(value = "30", widthPx = 45f, xPx = 128f, yPx = 10f)
        FormLabel("min.", modifier = Modifier.offset(x = 179f.px, y = 13f.px))
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:700 GroupBox1 ' Station ' (6,81,194x155).
// ---------------------------------------------------------------------------
@Composable
private fun StationGroup() {
    var qsk by remember { mutableStateOf(false) }
    VclGroupBox(" Station ", widthPx = 194f, heightPx = 155f) {
        FormLabel("Call", modifier = Modifier.offset(x = 12f.px, y = 21f.px))
        VclEdit("VE3NEA", widthPx = 89f, heightPx = 23f, xPx = 45f, yPx = 15f, fontPx = 13f)
        VclCheckBox(
            "QSK",
            xPx = 140f,
            yPx = 17f,
            checked = qsk,
            onToggle = { qsk = !qsk },
        )
        FormLabel("CW Speed", modifier = Modifier.offset(x = 12f.px, y = 46f.px))
        VclSpinEdit(value = "25", widthPx = 65f, xPx = 91f, yPx = 43f)
        FormLabel("WPM", modifier = Modifier.offset(x = 162f.px, y = 46f.px))
        FormLabel("CW Pitch", modifier = Modifier.offset(x = 13f.px, y = 76f.px))
        // Main.dfm:806 ComboBox1, items 300..900 Hz.  ItemIndex is not set in the
        // .dfm (the .ini picks it at run time), so the first item is displayed.
        VclCombo(
            items = listOf(
                "300 Hz", "350 Hz", "400 Hz", "450 Hz", "500 Hz", "550 Hz", "600 Hz",
                "650 Hz", "700 Hz", "750 Hz", "800 Hz", "850 Hz", "900 Hz",
            ),
            selectedIndex = 0,
            onSelect = {},
            widthPx = 65f,
            xPx = 92f,
            yPx = 73f,
        )
        FormLabel("RX Bandwidth", modifier = Modifier.offset(x = 12f.px, y = 106f.px))
        // Main.dfm:831 ComboBox2, items 100..600 Hz.
        VclCombo(
            items = listOf(
                "100 Hz", "150 Hz", "200 Hz", "250 Hz", "300 Hz", "350 Hz",
                "400 Hz", "450 Hz", "500 Hz", "550 Hz", "600 Hz",
            ),
            selectedIndex = 0,
            onSelect = {},
            widthPx = 65f,
            xPx = 92f,
            yPx = 102f,
        )
        FormLabel("Mon. Level", modifier = Modifier.offset(x = 12f.px, y = 134f.px))
        // Main.dfm:742 VolumeSlider1 (92,129,60x20), Value = 0.75, Db = -15.
        VclVolumeSlider(
            modifier = Modifier
                .offset(x = 92f.px, y = 129f.px)
                .size(60f.px, 20f.px),
        )
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:622 GroupBox3 ' Band Conditions ' (6,238,194x84).
// ---------------------------------------------------------------------------
@Composable
private fun BandConditionsGroup() {
    var qrn by remember { mutableStateOf(false) }
    var qrm by remember { mutableStateOf(false) }
    var qsb by remember { mutableStateOf(false) }
    var flutter by remember { mutableStateOf(false) }
    var lids by remember { mutableStateOf(false) }
    VclGroupBox(" Band Conditions ", widthPx = 194f, heightPx = 84f) {
        // 40 design px wide in the .dfm; Roboto at 12 px is wider, so 11 px keeps
        // it inside the 194 px group frame next to the x = 144 spin edit.
        FormLabel("Activity", sizePx = 11f, modifier = Modifier.offset(x = 144f.px, y = 19f.px))
        VclCheckBox("QRN", xPx = 12f, yPx = 21f, checked = qrn, onToggle = { qrn = !qrn })
        VclCheckBox("QRM", xPx = 12f, yPx = 41f, checked = qrm, onToggle = { qrm = !qrm })
        VclCheckBox("QSB", xPx = 12f, yPx = 61f, checked = qsb, onToggle = { qsb = !qsb })
        VclCheckBox("Flutter", xPx = 76f, yPx = 21f, checked = flutter, onToggle = { flutter = !flutter })
        VclCheckBox("LIDs", xPx = 76f, yPx = 41f, checked = lids, onToggle = { lids = !lids })
        VclSpinEdit(value = "3", widthPx = 37f, xPx = 144f, yPx = 35f)
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:79-233 Panel1 left third — Label1/2/3 above Edit1/Edit2/Edit3.
// The fields keep the .dfm's 118:77:45 proportions but stretch to the phone
// width (see the file header: 'Nr.' is 36 dp wide at SCALE, too small to read).
// ---------------------------------------------------------------------------
@Composable
private fun ExchangeRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FieldWithLabel("Call", "", weight = 118f)
        FieldWithLabel("RST", "", weight = 77f)
        FieldWithLabel("Nr.", "", weight = 45f)
    }
}

@Composable
private fun RowScope.FieldWithLabel(label: String, value: String, weight: Float) {
    Column(Modifier.weight(weight)) {
        FormLabel(label)
        Spacer(Modifier.height(2f.px))
        VclEdit(value, widthPx = 0f, heightPx = 27f, xPx = 0f, yPx = 0f, fontPx = 16f, fillWidth = true)
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:86-157 SpeedButton4..11 — F1 CQ / F2 <exch> / F3 TU / F4 <my> over
// F5 <his> / F6 B4 / F7 ? / F8 NIL (65 x 26 each, two rows of four).
// ---------------------------------------------------------------------------
@Composable
private fun SendButtons() {
    val captions = listOf(
        "F1  CQ", "F2  <exch>", "F3  TU", "F4  <my>",
        "F5  <his>", "F6  B4", "F7  ?", "F8  NIL",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        captions.chunked(4).forEach { rowCaptions ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowCaptions.forEach { caption ->
                    VclSpeedButton(caption, Modifier.weight(1f).height(26f.px))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:270 Panel4 (292,6,114x33) — the run-mode caption, red for
// COMPETITION (Main.pas:2091-2092), green otherwise; :285 Panel7 (412,6,104x33)
// an empty bevel panel; :234 Panel2 (522,5,191x33) the clock.
// ---------------------------------------------------------------------------
@Composable
private fun ModeAndClockRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Main.pas:1944 Mode array: 'Pile-Up' is the default (rmPileUp).
        Box(
            Modifier
                .size(114f.px, 33f.px)
                .background(FormFace)
                .vclBevel(raised = false),
            contentAlignment = Alignment.Center,
        ) {
            FormLabel("Pile-Up", sizePx = 11f, color = ModeGreen, weight = FontWeight.Bold)
        }
        Box(
            Modifier
                .size(104f.px, 33f.px)
                .background(FormFace)
                .vclBevel(raised = false),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(191f.px, 33f.px)
                .background(TimerBack)
                .vclBevel(raised = false),
            contentAlignment = Alignment.Center,
        ) {
            // Main.dfm:740 Panel2.Caption '00:00:00', Arial -24, colour #20F0D7.
            FormLabel("00:00:00", sizePx = 24f, color = TimerFont)
        }
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:251 Panel3 (292,45,225x67) with PaintBox1 (1,1,223x65) — the QSO
// rate histogram, painted by Histo.Repaint (Main.pas:1806).  Bars are fixed
// sample data: this milestone has no run to draw.
// ---------------------------------------------------------------------------
@Composable
private fun RateHistogramPanel() {
    Box(
        Modifier
            .size(225f.px, 67f.px)
            .background(FormFace)
            .vclBevel(raised = false),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(1.dp)
                .background(InfoBackground),
        ) {
            val bars = 24
            val barW = size.width / (bars * 1.6f)
            val gap = barW * 0.6f
            // Baseline.
            drawLine(FormEdge, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            for (i in 0 until bars) {
                val fraction = ((i * 37) % 11 + 1) / 12f // deterministic placeholder heights
                val barH = (size.height - 4f) * fraction
                drawRect(
                    color = RitThumb.copy(alpha = 0.75f),
                    topLeft = Offset(i * (barW + gap), size.height - 1f - barH),
                    size = Size(barW, barH),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:293 Panel8 (292,119,225x10) — the RIT slider, with Shape2
// (81,1,32x7) as the thumb.  Its Hint is a tooltip and is not drawn.
// ---------------------------------------------------------------------------
@Composable
private fun RitBar() {
    Box(
        Modifier
            .size(225f.px, 10f.px)
            .background(FormFace)
            .vclBevel(raised = false),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val thumb = RitThumb
            val x = size.width * (81f / 225f)
            val w = size.width * (32f / 225f)
            drawRect(thumb, topLeft = Offset(x, 1f), size = Size(w, size.height - 2f))
        }
    }
}

// ---------------------------------------------------------------------------
// Main.dfm:315 Panel11 (529,44,191x85) with ListView1 (189x83) — the score /
// correction grid.  Columns: '' (48), 'Raw' (70), 'Verified' (70); rows
// Pts/Mult/Score (from ListView1.Items.ItemData in the .dfm); values empty.
// Panel11's own Caption '0' is covered by the alClient grid, as in the .dfm.
// ---------------------------------------------------------------------------
@Composable
private fun ScoreGrid() {
    Column(
        Modifier
            .size(191f.px, 85f.px)
            .background(FieldFace)
            .vclBevel(raised = false),
    ) {
        Row(Modifier.fillMaxWidth().height(15f.px).background(HeaderFace)) {
            ScoreCell("", 48f, header = true)
            ScoreCell("Raw", 70f, header = true)
            ScoreCell("Verified", 70f, header = true)
        }
        listOf("Pts", "Mult", "Score").forEach { row ->
            Row(Modifier.fillMaxWidth().height(16f.px)) {
                ScoreCell(row, 48f)
                ScoreCell("", 70f)
                ScoreCell("", 70f)
            }
        }
    }
}

@Composable
private fun ScoreCell(text: String, widthPx: Float, header: Boolean = false) {
    Box(
        Modifier
            .width(widthPx.px)
            .fillMaxHeight()
            .border(0.5.dp, GroupEdge),
        contentAlignment = if (header) Alignment.Center else Alignment.CenterStart,
    ) {
        FormLabel(
            text,
            sizePx = 12f,
            modifier = Modifier.padding(start = if (header) 0.dp else 2f.px),
        )
    }
}

// ===========================================================================
// VCL primitives
// ===========================================================================

@Composable
private fun FormLabel(
    text: String,
    sizePx: Float = 12f,
    color: Color = FormInk,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
    italic: Boolean = false,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = sizePx.pt,
        lineHeight = (sizePx * 1.15f).pt,
        fontFamily = family,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Visible,
        textAlign = textAlign,
    )
}

/** 1 px 3D bevel: light top/left, dark bottom/right (bvRaised), reversed when lowered. */
private fun Modifier.vclBevel(raised: Boolean): Modifier = drawBehind {
    val light = if (raised) FormHighlight else FormEdge
    val dark = if (raised) FormEdge else FormHighlight
    val w = 1f
    drawLine(light, Offset(0f, 0f), Offset(size.width, 0f), w)
    drawLine(light, Offset(0f, 0f), Offset(0f, size.height), w)
    drawLine(dark, Offset(0f, size.height), Offset(size.width, size.height), w)
    drawLine(dark, Offset(size.width, 0f), Offset(size.width, size.height), w)
}

/**
 * A VCL `TGroupBox`: a one-pixel frame whose top-left segment is interrupted by
 * the caption.  Children of the box are positioned in design pixels by their own
 * `Modifier.offset(x = .. .px, y = .. .px)`, exactly as in the .dfm.
 */
@Composable
private fun VclGroupBox(
    caption: String,
    widthPx: Float,
    heightPx: Float,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.size(widthPx.px, heightPx.px)) {
        Box(
            Modifier
                .offset(y = 6f.px)
                .width(widthPx.px)
                .height(heightPx.px - 6f.px)
                .border(1.dp, GroupEdge),
        )
        FormLabel(
            caption,
            sizePx = 13f,
            modifier = Modifier
                .offset(x = 7f.px)
                .background(FormFace)
                .padding(horizontal = 2f.px),
        )
        content()
    }
}

@Composable
private fun VclEdit(
    text: String,
    widthPx: Float,
    heightPx: Float,
    xPx: Float,
    yPx: Float,
    fontPx: Float = 13f,
    fillWidth: Boolean = false,
) {
    Box(
        Modifier
            .offset(x = xPx.px, y = yPx.px)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(widthPx.px))
            .height(heightPx.px)
            .background(FieldFace)
            .border(1.dp, FieldEdge),
        contentAlignment = Alignment.CenterStart,
    ) {
        FormLabel(
            text,
            sizePx = fontPx,
            family = FontFamily.Monospace,
            weight = FontWeight.Bold,
            modifier = Modifier.padding(start = 3f.px),
        )
    }
}

@Composable
private fun VclCheckBox(
    caption: String,
    xPx: Float,
    yPx: Float,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .offset(x = xPx.px, y = yPx.px)
            .height(17f.px)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12f.px)
                .background(FieldFace)
                .border(1.dp, FieldEdge),
        ) {
            if (checked) {
                Canvas(Modifier.fillMaxSize().padding(1.dp)) {
                    drawLine(FormInk, Offset(0f, size.height * 0.55f), Offset(size.width * 0.4f, size.height), 1.5f)
                    drawLine(FormInk, Offset(size.width * 0.4f, size.height), Offset(size.width, 0f), 1.5f)
                }
            }
        }
        Spacer(Modifier.width(4f.px))
        FormLabel(caption)
    }
}

/** `TSpinEdit`: a sunken edit field with an up/down arrow pair on the right. */
@Composable
private fun VclSpinEdit(value: String, widthPx: Float, xPx: Float, yPx: Float) {
    Box(
        Modifier
            .offset(x = xPx.px, y = yPx.px)
            .size(widthPx.px, 24f.px)
            .background(FieldFace)
            .border(1.dp, FieldEdge),
    ) {
        FormLabel(
            value,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 3f.px),
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(11f.px)
                .height(20f.px),
        ) {
            Canvas(Modifier.fillMaxWidth().weight(1f)) { drawArrow(up = true) }
            Canvas(Modifier.fillMaxWidth().weight(1f)) { drawArrow(up = false) }
        }
    }
}

private fun DrawScope.drawArrow(up: Boolean) {
    val path = Path().apply {
        if (up) {
            moveTo(0f, size.height); lineTo(size.width, size.height); lineTo(size.width / 2f, 0f)
        } else {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
        }
        close()
    }
    drawPath(path, FormEdge)
}

/**
 * `TComboBox` (csDropDownList): the closed field plus a Material dropdown.
 * Used for the 12 contests (live data) and for the two fixed Hz lists.
 */
@Composable
private fun VclCombo(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    widthPx: Float,
    xPx: Float,
    yPx: Float,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        Modifier
            .offset(x = xPx.px, y = yPx.px)
            .width(widthPx.px)
            .height(23f.px),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FieldFace)
                .border(1.dp, FieldEdge)
                .clickable { expanded = true },
        ) {
            FormLabel(
                items.getOrElse(selectedIndex) { "" },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 3f.px),
            )
            Canvas(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3f.px)
                    .size(9f.px, 6f.px),
            ) {
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                    },
                    FormInk,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item, fontSize = 13.sp) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** `TSpeedButton`: a raised flat-faced button (F1..F8, Run). */
@Composable
private fun VclSpeedButton(caption: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(FormFace)
            .vclBevel(raised = true)
            .clickable { },
        contentAlignment = Alignment.Center,
    ) {
        FormLabel(caption, sizePx = 12f, modifier = Modifier.padding(horizontal = 2f.px))
    }
}

/** `TVolumeSlider` (60 x 20, Value 0.75): groove plus a raised thumb. */
@Composable
private fun VclVolumeSlider(modifier: Modifier = Modifier) {
    Canvas(modifier.background(FormFace).vclBevel(raised = false)) {
        val y = size.height / 2f
        drawLine(FormEdge, Offset(3f, y), Offset(size.width - 3f, y), 1f)
        val thumbX = 3f + (size.width - 12f) * 0.75f
        drawRect(FormHighlight, topLeft = Offset(thumbX, 2f), size = Size(8f, size.height - 4f))
        drawRect(FormEdge, topLeft = Offset(thumbX, 2f), size = Size(8f, size.height - 4f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}
