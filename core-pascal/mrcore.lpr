library mrcore;
{ Headless Morse Runner contest core for Android (ADR-0020).

  The original contest logic (Contest.pas, DxStn.pas, Log.pas, Station.pas, ...) is compiled
  unchanged except for the GUI/Windows/audio edges, which live in headless/. The UI is Kotlin
  and reaches this library through the small C ABI in docs/abi.md: plain C types only, the
  caller supplies the buffer, the return value is the number of characters written (-1 on a
  bad argument). }
{$mode delphi}{$H+}

uses
  { `cthreads` MUST be first on Linux. Without it FPC links the RTL in single-threaded mode: there is
    no thread manager, so (a) creating a thread aborts the process (measured: exit 232 the moment
    BeginThread or IsMultiThread ran) and (b) a raise on a thread the RTL did not create dies in
    fpc_popaddrstack instead of unwinding — which is the SIGSEGV that started this whole hunt. }
  cthreads,
  SysUtils, Math, SyncObjs, RndFunc, Contest, Ini, Log, Station, SndTypes, DxStn, DxOper, Main, DXCC,
  DataDir, MorseKey,
  ArrlDx, ArrlFd, ArrlSS, CqWW, CqWpx, NaQp, IaruHf, ACAG, ALLJA, CWOPS, CWSST, DualExchContest,
  Entry, Session, Trace, AudioFeed;

{ The desktop app hit the engine from the sound-card thread only, and marshalled UI actions 
  onto it with TThread.Synchronize. Here the audio thread pulls blocks while the UI thread 
  sends messages and types calls, so every entry point takes this lock. }
var
  EngineLock: TCriticalSection;

{ The last exception an entry point caught, as `where: ClassName: Message`. ABI rule 5 on the
  Kotlin side is "nothing throws across the boundary", and a Pascal exception that reaches a JNI
  frame cannot be caught there at all: the RTL ends the process (measured on the ZTE 7534N as
  "exited cleanly (255)" with no tombstone). Every entry point therefore catches, records here and
  returns a negative code, and the UI reads this back to say WHAT failed instead of dying. }
var
  LastError: string = '';

procedure RecordError(const Where: string; E: Exception); 
begin
  LastError := Where + ': ' + E.ClassName + ': ' + E.Message;
end;

function CopyToBuf(const S: string; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
var
  N: Integer;
  U: UTF8String;
begin
  if (Buf = nil) or (Cap <= 0) then
    Exit(-1);
  { Kotlin strings are UTF-8 on the JNI boundary; encode before copying. }
  U := UTF8String(S);
  N := Length(U);
  if N > Cap - 1 then
    N := Cap - 1;
  if N > 0 then
    Move(U[1], Buf^, N);
  Buf[N] := #0;
  Result := N;
end;

{ The contest factory, copied verbatim from TMainForm.CreateContest (Main.pas): the mapping
  from the menu enum to a concrete subclass is part of the original behaviour, and scWpx and
  scHst deliberately share TCqWpx. It lives in headless/Session.pas so the native harness starts
  a run through exactly the same code as the phone. }
function CreateContestFor(AContestId: TSimContest): TContest;
begin
  Result := Session.CreateContestFor(AContestId);
end;

function mr_version: PAnsiChar; cdecl;
begin
  Result := 'MorseRunner core 0.4 (FPC aarch64, 12 contests, audio blocks, log + scoring, no VCL)';
end;

function mr_contest_count: Integer; cdecl;
begin
  Result := Ord(High(TSimContest)) + 1;
end;

function mr_contest_name(Idx: Integer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
begin
  if (Idx < 0) or (Idx > Ord(High(TSimContest))) then
    Exit(-1);
  Result := CopyToBuf(string(ContestDefinitions[TSimContest(Idx)].Name), Buf, Cap);
end;

function mr_contest_key(Idx: Integer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
begin
  if (Idx < 0) or (Idx > Ord(High(TSimContest))) then
    Exit(-1);
  Result := CopyToBuf(string(ContestDefinitions[TSimContest(Idx)].Key), Buf, Cap);
end;

{ Instantiate a contest. Returns an opaque handle (nil when the index is out of range or the
  subclass cannot be created). The caller must hand it back to mr_contest_free. }
function mr_contest_start(Idx: Integer): Pointer; cdecl;
begin
  Result := nil;
  if (Idx < 0) or (Idx > Ord(High(TSimContest))) then
    Exit;
  try
    Result := CreateContestFor(TSimContest(Idx));
  except
    Result := nil;
  end;
end;

{ Class name of a live contest object: the proof that the right subclass was instantiated. }
function mr_contest_class(H: Pointer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
begin
  if H = nil then
    Exit(-1);
  Result := CopyToBuf(TContest(H).ClassName, Buf, Cap);
end;

procedure mr_contest_free(H: Pointer); cdecl;
begin
  { The producer must be gone before its contest is (it dereferences Tst every block). }
  AudioFeed.StopFeed;
  if (H <> nil) and (Tst = TContest(H)) then
    Tst := nil;
  if H <> nil then
    TContest(H).Free;
end;

{ ---- running a contest ---------------------------------------------------
  The VCL application drove the simulation from its sound card callback: every time the device
  needed a block it called TContest.GetAudio, which filters, modulates, runs the AGC, ticks the
  operator and every station, and appends finished QSOs to the log. Nothing else advances time,
  so that same call is the whole game loop here: the UI pulls blocks at the audio rate and reads
  the score and log back. }

function mr_run_start(Idx: Integer; MyCall: PAnsiChar): Pointer; cdecl;
begin
  { The body lives in headless/Session.pas: the native harness starts runs through the same code,
    so a harness result is about this program and not about a copy of it. }
  Result := nil;
  try
    Result := Session.StartRun(Idx, string(AnsiString(MyCall)));
    if Result <> nil then
      { The game loop runs on the feed's own thread from here on; see headless/AudioFeed.pas. }
      AudioFeed.StartFeed(EngineLock);
  except
    on E: Exception do
      RecordError('run_start', E);
  end;
end;

{ One audio block: mono single-precision samples at DEFAULTRATE, copied out of the ring the
  engine's own thread fills (headless/AudioFeed.pas). THIS CALL NO LONGER RUNS THE GAME LOOP: the
  simulation advances on an RTL thread, where a raise can be caught. Returns 0 when no block is
  ready yet — the caller keeps its device paced and asks again, it is not the end of the run. }
function mr_block(H: Pointer; OutBuf: PSingle; Cap: Integer): Integer; cdecl;
begin
  Result := 0;
  if H = nil then
    Exit;
  try
    Result := AudioFeed.Pull(OutBuf, Cap);
  except
    on E: Exception do
      RecordError('block', E);
  end;
end;

{ The form's SendMsg (Main.pas:563) lives in the headless Entry unit so that the native harness
  drives exactly the same entry point as the phone. See Entry.pas for why it matters. }

{ Send one canned message (the F1..F8 buttons). Msg is the TStationMessage ordinal. }
procedure mr_send(H: Pointer; Msg: Integer); cdecl;
begin
  if (H = nil) or (Msg < 0) or (Msg > Ord(High(TStationMessage))) then
    Exit;
  EngineLock.Enter;
  try
    try
      FormSendMsg(TContest(H), TStationMessage(Msg));
      { Whatever the device has not played yet was generated before this key press. Drop it so the
        message starts on the next block instead of after up to FEED_SLOTS blocks of stale audio. }
      AudioFeed.Flush;
    except
      on E: Exception do
        RecordError('send', E);
    end;
  finally
    EngineLock.Leave;
  end;
end;

{ The operator is typing: hand the text to the operator station. }
procedure mr_set_my_call(H: Pointer; Text: PAnsiChar); cdecl;
begin
  if (H = nil) or (Text = nil) then
    Exit;
  EngineLock.Enter;
  try
    TContest(H).Me.MyCall := string(AnsiString(Text));
  finally
    EngineLock.Leave;
  end;
end;
procedure mr_set_his_call(H: Pointer; Text: PAnsiChar); cdecl;
begin
  if (H = nil) or (Text = nil) then
    Exit;
  EngineLock.Enter;
  try
    TContest(H).Me.HisCall := string(AnsiString(Text));
  finally
    EngineLock.Leave;
  end;
end;

{ Monitor level: the desktop form's VolumeSlider1 (Main.dfm:751, default 0.75). GetAudio maps it to
  the self-monitor gain of the operator's own signal, so zero means "my CQ is inaudible". }
procedure mr_set_monitor_level(H: Pointer; Value: Single); cdecl;
begin
  if H = nil then
    Exit;
  if Value < 0 then Value := 0;
  if Value > 1 then Value := 1;
  EngineLock.Enter;
  try
    MainForm.VolumeSlider1.Value := Value;
    MainForm.VolumeSlider1.Db := (Value - 1) * 60;
  finally
    EngineLock.Leave;
  end;
end;

{ Station-panel settings: the desktop form's CW speed, pitch, bandwidth and QSK controls (one
  generic entry point because each is a single value assigned into Ini; the bandwidth also re-derives
  the two filter widths exactly as Main.pas SetBw does). }
procedure mr_set_setting(H: Pointer; Kind, Value: Integer); cdecl;
var
  C: TContest;
begin
  { The panel is usable before a run exists: speed, pitch and QSK live in Ini, and bandwidth only
    needs the filters when there is a contest to re-tune (a later StartRun reads Ini anyway). }
  C := TContest(H);
  EngineLock.Enter;
  try
    try
      case Kind of
        0: begin                                    // CW speed (Main.dfm TSpinEdit1, 10..60)
             if Value < 10 then Value := 10;
             if Value > 60 then Value := 60;
             Ini.Wpm := Value;
           end;
        1: begin                                    // pitch (ComboBox1, 300..900 step 50)
             if Value < 300 then Value := 300;
             if Value > 900 then Value := 900;
             Ini.Pitch := Value;
             if C <> nil then
               C.Modul.CarrierFreq := Value;
           end;
        2: begin                                    // bandwidth (ComboBox2, 100..600 step 50)
             if Value < 100 then Value := 100;
             if Value > 600 then Value := 600;
             Ini.BandWidth := Value;
             if C <> nil then
             begin
               C.Filt.Points := Round(0.7 * DEFAULTRATE / Ini.BandWidth);
               C.Filt.GainDb := 10 * Log10(500 / Ini.BandWidth);
               C.Filt2.Points := C.Filt.Points;
               C.Filt2.GainDb := C.Filt.GainDb;
             end;
           end;
        3: Ini.Qsk := Value <> 0;                   // QSK
      end;
    except
      on E: Exception do RecordError('setting', E);
    end;
  finally
    EngineLock.Leave;
  end;
end;

{ Read back what the core actually uses: the UI shows the value, but the evidence for a control that
  "does nothing" is what the engine holds. }
function mr_get_setting(Kind: Integer): Integer; cdecl;
begin
  case Kind of
    0: Result := Ini.Wpm;
    1: Result := Ini.Pitch;
    2: Result := Ini.BandWidth;
    3: if Ini.Qsk then Result := 1 else Result := 0;
  else
    Result := -1;
  end;
end;

{ The operator's typed entry: the three Main.dfm edit boxes (call, exchange 1, exchange 2).
  Log.SaveQso reads exactly these, so this is the typing surface of the form, not a shortcut. }
procedure mr_set_entry(H: Pointer; Call, Exch1, Exch2: PAnsiChar); cdecl;
begin
  if H = nil then
    Exit;
  EngineLock.Enter;
  try
    try
      if Call <> nil then MainForm.Edit1.Text := string(AnsiString(Call));
      if Exch1 <> nil then MainForm.Edit2.Text := string(AnsiString(Exch1));
      if Exch2 <> nil then MainForm.Edit3.Text := string(AnsiString(Exch2));
    except
      on E: Exception do
        RecordError('set_entry', E);
    end;
  finally
    EngineLock.Leave;
  end;
end;

{ The Enter key, the way the desktop form's "TU & save" key did it (Main.pas FormKeyPress
  '.'/'+'/'['/',' and Enter): verify the copied call, let the contest adjust the exchange
  fields, answer with the DX call when it has not gone out yet, send TU, then append the QSO.
  Returns the new log length, -1 on a bad handle, -2 when the copied call is not acceptable,
  -3 when the save raised (the message is then in mr_last_error). }
function mr_save_qso(H: Pointer): Integer; cdecl;
var
  ExchError: string;
  C: TContest;
begin
  Result := -1;
  if H = nil then
    Exit;
  C := TContest(H);
  EngineLock.Enter;
  try
    try
      ExchError := '';
      if not C.CheckEnteredCallLength(MainForm.Edit1.Text, ExchError) then
      begin
        TraceLine('save rejected (call): ' + ExchError);
        Exit(-2);
      end;
      { ValidateEnteredExchange is NOT called here. ARRL Sweepstakes needs it -- its
        SaveEnteredExchToQso reads state that its parser fills -- but that parser does not terminate
        on the exchange text the probe produces, and asking the other contests' generic parser
        rejected valid entries ("Missing/Invalid Serial Number" for CWops CWT). Until the SS parser
        is fixed it must not be on the save path: a hang in the audio thread is worse than a QSO
        that fails verification. Recorded in .scratch/progress.md section 60. }
      { ARRL Sweepstakes stores its QSO exchange through a parser (its SaveEnteredExchToQso reads the
        validator's NR/Precedence/Check/Section), and the upstream validate pass does not terminate
        on the phone yet. Fill the validator from the typed text here instead: bounded, one pass. }
      if C is TSweepstakes then
        TSweepstakes(C).SetEnteredExchange(
          Trim(MainForm.Edit2.Text + ' ' + MainForm.Edit3.Text));
      C.OnExchangeEditComplete;
      { The desktop's Enter (Main.pas:804-806): answer with the DX call when it has not gone out yet,
        thank with TU, then append the QSO. TU is not decoration -- the DX operator only reaches
        osDone (and therefore the verified score, Contest.pas:811) after it hears ours; with the two
        sends removed the log grew but the Verified column stayed at zero. }
      TraceLine(Format('save: callsent=%s ed1=[%s] ed2=[%s] ed3=[%s] recv=%d/%d sent=%d/%d',
        [BoolToStr(Log.CallSent, True), MainForm.Edit1.Text, MainForm.Edit2.Text, MainForm.Edit3.Text,
         Ord(MainForm.RecvExchTypes.Exch1), Ord(MainForm.RecvExchTypes.Exch2),
         Ord(C.Me.SentExchTypes.Exch1), Ord(C.Me.SentExchTypes.Exch2)]));
      if not Log.CallSent then
      begin
        FormSendMsg(C, msgHisCall);
        TraceLine('save: his call sent');
      end;
      FormSendMsg(C, msgTU);
      TraceLine('save: TU sent');
      Log.SaveQso;
      Result := Length(QsoList);
    except
      on E: Exception do
      begin
        RecordError('save', E);
        Result := -3;
      end;
    end;
  finally
    EngineLock.Leave;
  end;
end;

{ The last caught exception, or an empty string. Reading it clears nothing: the UI logs a value
  when it changes, so one failure is reported once even if several calls run before the next poll. }
function mr_last_error(Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
begin
  EngineLock.Enter;
  try
    Result := CopyToBuf(LastError, Buf, Cap);
  finally
    EngineLock.Leave;
  end;
end;

{ Where the bundled data files (DXCC.LIST, call histories) live. }
procedure mr_set_data_dir(Path: PAnsiChar); cdecl;
begin
  if Path = nil then
    Exit;
  DataDir.MRDataDir := string(AnsiString(Path));
  { Threads: the producer in headless/AudioFeed.pas allocates from this heap. It has to be set from
    a live library call — setting it in the unit's initialization ended every start before the first
    log line (exit 232), and setting it inside mr_run_start killed the caller. This is the first
    call the app makes after the library is live. }
  IsMultiThread := True;
  { Keep exactly one separator; #92 is the backslash, written this way so the literal
    never has to carry an escape that Pascal does not have. }
  if (DataDir.MRDataDir <> '') and
     (DataDir.MRDataDir[Length(DataDir.MRDataDir)] <> '/') and
     (DataDir.MRDataDir[Length(DataDir.MRDataDir)] <> #92) then
    DataDir.MRDataDir := DataDir.MRDataDir + '/';
end;
function mr_contest_rate: Integer; cdecl;
begin
  Result := DEFAULTRATE;
end;

function mr_block_size: Integer; cdecl;
begin
  Result := Ini.BufSize;
end;

function mr_log_count: Integer; cdecl;
begin
  { Rows as rendered (LastQsoToScreen appends one per saved QSO). }
  Result := MainForm.ListView2.Items.Count;
end;

{ One cell of the QSO log: the row the ORIGINAL form already rendered.
  Log.pas LastQsoToScreen formats each saved QSO per contest (CQ WPX shows RST + serial, ARRL SS
  shows precedence + check + section, ...) and appends it to MainForm.ListView2 through
  ScoreTableInsert. Reading those rows back keeps the phone's log identical to the desktop's
  instead of re-deriving the column meanings here — column 0 is the row caption (UTC), 1..6 are
  the sub-items in the order the contest put them in. }
function mr_log_cell(Row, Col: Integer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
var
  S: string;
  Row2: TStubListItem;
begin
  Result := -1;
  if (Col < 0) or (Col > 7) then
    Exit;
  { Same hazard as mr_status_text: the rows are appended from the game loop (a QSO saved on the
    audio thread) while the UI reads them, and the cell is a refcounted string. }
  EngineLock.Enter;
  try
    if (Row < 0) or (Row >= MainForm.ListView2.Items.Count) then
      Exit;
    Row2 := MainForm.ListView2.Items[Row];
    if Col = 0 then
      S := Row2.Caption
    else if Col - 1 < Row2.SubItems.Count then
      S := Row2.SubItems[Col - 1]
    else
      S := '';
    Result := CopyToBuf(S, Buf, Cap);
  finally
    EngineLock.Leave;
  end;
end;

{ 0 raw points, 1 verified points, 2 raw multipliers, 3 verified multipliers.

  HST has no multipliers and scores each QSO by the speed of the call it copied, so it keeps its own
  two totals (Log.HstRawScore/HstVerifiedScore, published by UpdateStatsHst). Reporting 0 there was
  what made the HST contest show an empty scoreboard on the phone. }
function mr_score(Which: Integer): Integer; cdecl;
begin
  Result := 0;
  if Ini.SimContest = scHst then
  begin
    case Which of
      0: Result := HstRawScore;
      1: Result := HstVerifiedScore;
      2, 3: Result := 1;   // one "multiplier", so the Score column equals the points
    end;
    Exit;
  end;
  case Which of
    0: Result := RawPoints;
    1: Result := VerifiedPoints;
    2: if RawMultList <> nil then Result := RawMultList.Count;
    3: if VerifiedMultList <> nil then Result := VerifiedMultList.Count;
  end;
end;

{ 0 = the QSO rate (`Panel7`: `n  qso/hr.`), 1 = the run clock (`Panel2`: `hh:nn:ss`),
  2 = the Pile-Up caption (`Panel4`: `Pile-Up:  n`). These are the captions the game loop itself
  writes (Log.pas ShowRate, Contest.pas:839-842), read back verbatim: the phone shows the rate and
  the clock the ORIGINAL computes instead of re-deriving either from the sample count. Anything
  with an argument beyond a panel index is a new call, not another index here. }
{ 0 = the QSO rate (`Panel7`: `n  qso/hr.`), 1 = the run clock (`Panel2`: `hh:nn:ss`),
  2 = the Pile-Up caption (`Panel4`: `Pile-Up:  n`). These are the captions the game loop itself
  writes (Log.pas ShowRate, Contest.pas:839-842), read back verbatim: the phone shows the rate and
  the clock the ORIGINAL computes instead of re-deriving either from the sample count.

  The lock is NOT optional: the audio thread reassigns these captions on every block, and a Pascal
  string is a refcounted heap object, so reading one without the lock hands the caller a reference
  the writer is about to free. That is a native SIGSEGV on the audio thread, not a stale reading —
  measured on the ZTE 7534N: idle 36 s after Run, `fpc_popaddrstack` in libmrcore.so. }
function mr_status_text(Which: Integer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
begin
  { The body lives in headless/Session.pas for the same reason StartRun does: the native harness
    reads the identically implemented text, so its evidence is about this program. }
  Result := -1;
  if (Which < 0) or (Which > 2) then
    Exit;
  EngineLock.Enter;
  try
    Result := CopyToBuf(Session.StatusText(Which), Buf, Cap);
  finally
    EngineLock.Leave;
  end;
end;

{ How many DX stations are currently calling. The original showed this through the panel
  captions; it is a count, not a decode, so it reveals nothing about what they are sending. }
function mr_station_count(H: Pointer): Integer; cdecl;
begin
  if H = nil then
    Exit(0);
  Result := TContest(H).Stations.Count;
end;

{ Diagnostics for the headless harness only; the UI does not use it. }
function mr_debug_state(H: Pointer; Which: Integer): Integer; cdecl;
begin
  Result := -1;
  if H = nil then Exit;
  case Which of
    0: Result := TContest(H).Stations.Count;
    1: Result := Length(QsoList);
    2: Result := Ord(Ini.RunMode);
    3: Result := Ord(TContest(H).Me.State);
    4: Result := TContest(H).BlockNumber;
    5: Result := Ord(High(TStationMessage));
    { Monitor level as the mixer reads it (the desktop form's slider, default 0.75 = -15 dB).
      Zero here means every "my audio" block is multiplied to silence. }
    6: Result := Round(MainForm.VolumeSlider1.Value * 1000);
    7: Result := Length(TContest(H).Me.Envelope);
    8: Result := TContest(H).Me.SendPosValue;
    { Operator messages completed -> OnMeFinishedSending -> callers arrive. }
    9: Result := MeFinishedSendingCount;
    20: Result := Trace.LastSection; // the audio thread's stage; see headless/Trace.pas
  end;
end;

{ The call of station Idx as the simulation knows it (DX stations carry their own callsign in
  MyCall). The probe drives a whole QSO with it; the app never displays it. }
function mr_station_call(H: Pointer; Idx: Integer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
begin
  Result := -1;
  if H = nil then Exit;
  if (Idx < 0) or (Idx >= TContest(H).Stations.Count) then Exit;
  Result := CopyToBuf(string(TContest(H).Stations[Idx].MyCall), Buf, Cap);
end;

{ One exchange field of station Idx (0 = Exch1, 1 = Exch2, 2 = its WPM). Harness only: the probe
  needs the exchange the way the operator would have copied it by ear, and the app never asks. }
function mr_station_exch(H: Pointer; Idx, Which: Integer; Buf: PAnsiChar; Cap: Integer): Integer; cdecl;
var
  S: string;
begin
  Result := -1;
  if H = nil then Exit;
  if (Idx < 0) or (Idx >= TContest(H).Stations.Count) then Exit;
  case Which of
    0: S := TContest(H).Stations[Idx].Exch1;
    1: S := TContest(H).Stations[Idx].Exch2;
    2: S := IntToStr(TContest(H).Stations[Idx].WpmS);
  else
    Exit;
  end;
  Result := CopyToBuf(S, Buf, Cap);
end;

exports
  mr_version name 'mr_version',
  mr_contest_count name 'mr_contest_count',
  mr_contest_name name 'mr_contest_name',
  mr_contest_key name 'mr_contest_key',
  mr_contest_start name 'mr_contest_start',
  mr_contest_class name 'mr_contest_class',
  mr_contest_free name 'mr_contest_free',
  mr_run_start name 'mr_run_start',
  mr_block name 'mr_block',
  mr_send name 'mr_send',
  mr_set_my_call name 'mr_set_my_call',
  mr_contest_rate name 'mr_contest_rate',
  mr_block_size name 'mr_block_size',
  mr_log_count name 'mr_log_count',
  mr_log_cell name 'mr_log_cell',
  mr_score name 'mr_score',
  mr_status_text name 'mr_status_text',
  mr_set_his_call name 'mr_set_his_call',
  mr_set_data_dir name 'mr_set_data_dir',
  mr_station_count name 'mr_station_count',
  mr_station_call name 'mr_station_call',
  mr_station_exch name 'mr_station_exch',
  mr_set_monitor_level name 'mr_set_monitor_level',
  mr_set_setting name 'mr_set_setting',
  mr_get_setting name 'mr_get_setting',
  mr_set_entry name 'mr_set_entry',
  mr_save_qso name 'mr_save_qso',
  mr_last_error name 'mr_last_error',
  mr_debug_state name 'mr_debug_state';

{ `mr-trace.log` in the data dir: what a failed run left behind. The RTL ends the process for an
  unhandled exception or a runtime error (measured here as a bare "exited cleanly (255)" with no
  tombstone and no logcat line), which is unauditable from Kotlin — so both RTL hooks append a line
  with the class, the message and the error code. Appending (not rewriting) keeps the first failure. }
{ Delegates to the shared unit so the engine's own breadcrumbs (Contest.pas) and these hooks land
  in one file, in order. }
procedure Trace(const S: string);
begin
  TraceLine(S);
end;

procedure MrErrorProc(ErrorCode: LongInt);
begin
  Trace('RunError ' + IntToStr(ErrorCode));
end;

procedure MrExceptProc(Obj: TObject; Addr: Pointer; FrameCount: LongInt; Frame: PPointer);
begin
  if Obj is Exception then
    Trace('Unhandled ' + Obj.ClassName + ': ' + Exception(Obj).Message +
      ' pc=' + IntToHex(QWord(Addr), 16))
  else
    Trace('Unhandled object pc=' + IntToHex(QWord(Addr), 16));
end;

initialization
  EngineLock := TCriticalSection.Create;
  { NOTE: IsMultiThread is deliberately NOT set here. Setting it during library initialization made
    every start die before the first log line (exit 232, no tombstone), and setting it from
    StartFeed killed the caller instead. It is set at the top of mr_set_data_dir, which is the first
    call the app makes after the library is live — see the comment there. }
  { No signal handler and no other native object is created here: anything constructed during
    System.loadLibrary must be cheap and deadlock-free, and the app hung on its splash screen when
    it was not (2026-10-20). The handler is armed by mr_set_data_dir, once the app is running. }
  { Both hooks run on the thread that raised, so the audio thread's failure is recorded too. }
  ErrorProc := @MrErrorProc;
  ExceptProc := @MrExceptProc;
  { The desktop form created the CW keyer in FormCreate (upstream Main.pas:482); without it
    the first SendText dereferences a nil keyer. }
  MakeKeyer(DEFAULTRATE, Ini.BufSize);
  { Two globals the VCL form used to create (upstream Main.pas:450/452). Without them the first
    log update walks a nil pointer, so they belong to the core lifetime. }
  Histo := THisto.Create(MainForm.PaintBox1);

finalization
  DestroyKeyer;
  Histo.Free;
  EngineLock.Free;
end.

