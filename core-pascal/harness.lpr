program harness;
{ Native harness for the contest core — the debug loop that does not need a phone.

  Why it exists: the Android build is a 40-second cross-compile plus install plus device run,
  and a crash there reports only a tombstone. The same Pascal units compile natively here, so
  `harness.exe` can drive CQs, print the state of the operator and the caller, the measured peak,
  and the three status captions, and surface a Pascal exception with its class and message.

  It uses headless/Session.pas and headless/Entry.pas — the same start-run, send-message and
  status-text code the ABI library uses — so a harness result is about the program on the phone,
  not a copy of it.

  Usage:  harness.exe [data-dir]
    data-dir defaults to ..\..\app\src\main\assets\data (DXCC.LIST and the call histories). }

{$mode delphi}{$H+}

uses
  SysUtils, RndFunc, Contest, Ini, Log, Station, SndTypes, DxStn, DxOper, Main, DXCC,
  DataDir, MorseKey, Session, Entry, StnColl;

var
  C: TContest;
  A: TSingleArray;
  Peak: Single;

function Dbg(Which: Integer): Integer;
begin
  case Which of
    0: Result := C.Stations.Count;
    1: Result := Length(QsoList);
    2: Result := Ord(Ini.RunMode);
    3: Result := Ord(C.Me.State);
    4: Result := C.BlockNumber;
    6: Result := Round(MainForm.VolumeSlider1.Value * 1000);
    7: Result := Length(C.Me.Envelope);
    9: Result := MeFinishedSendingCount;
  else
    Result := -1;
  end;
end;

procedure Dump(const Tag: string);
begin
  WriteLn(Tag, ': stations=', Dbg(0), ' log=', Dbg(1), ' runmode=', Dbg(2), ' state=', Dbg(3),
    ' block=', Dbg(4), ' vol=', Dbg(6), ' env=', Dbg(7), ' finished=', Dbg(9));
  // The three captions the game loop rewrites; the phone reads them through mr_status_text.
  WriteLn('  rate="', Session.StatusText(0), '" clock="', Session.StatusText(1),
    '" pile="', Session.StatusText(2), '"');
end;

{ Write N blocks of raw float32 audio to a file, then stop. Used to look at the sample stream
  itself (a click at every block boundary, a reset noise generator) instead of guessing from the
  phone's speakers. }
procedure DumpAudio(const Path: string; N: Integer);
var
  p, w: Integer;
  B: TSingleArray;
  F: file;
begin
  AssignFile(F, Path);
  Rewrite(F, 1);
  try
    for p := 1 to N do
    begin
      B := C.GetAudio;
      if Length(B) > 0 then
      begin
        BlockWrite(F, B[0], Length(B) * SizeOf(Single), w);
      end;
    end;
  finally
    CloseFile(F);
  end;
end;

{ Pull audio blocks; with AMeasure, keep the absolute peak across them. }
procedure Pull(N: Integer; AMeasure: Boolean);
var
  p, j: Integer;
begin
  for p := 1 to N do
  begin
    A := C.GetAudio;
    if AMeasure then
      for j := 0 to High(A) do
        if Abs(A[j]) > Peak then
          Peak := Abs(A[j]);
  end;
end;

{ Listen until the operator's message has gone out (state 3 = stSending). }
function WaitDone(ALimit: Integer): Integer;
begin
  Result := 0;
  while (Dbg(3) = 3) and (Result < ALimit) do
  begin
    Pull(10, False);
    Inc(Result, 10);
  end;
end;

{ Let the DX station finish what it is sending: DxStn.pas only copies our message when it is NOT
  transmitting, so a real operator waits for the other station before pressing the next key. }
procedure WaitStationIdle(ALimit: Integer);
var
  k: Integer;
begin
  k := 0;
  while (k < ALimit) and (C.Stations.Count > 0) and
        (TDxStation(C.Stations[0]).State <> stListening) do
  begin
    Pull(5, False);
    Inc(k, 5);
  end;
end;

{ What the one caller is doing: station state and operator state (0..7, DxOper.pas). -1 = no
  station. Printed because "the DX never confirmed" is otherwise invisible in this loop. }
function DxState: string;
begin
  if C.Stations.Count = 0 then
    Exit('none');
  Result := Format('stn=%d oper=%d', [Ord(TDxStation(C.Stations[0]).State),
    Ord(TDxStation(C.Stations[0]).Oper.State)]);
end;

{ Call CQ until a station answers, then work the QSO the way an operator does: his call (F5), our
  exchange (F2), and finally TU & save — the same body mr_save_qso runs. Sending the exchange is
  what lets the DX confirm; confirmation is what moves the Verified column. }
procedure WorkQso(const Tag: string);
var
  Calls, Saved, j, k: Integer;
  HisCall, E1, E2: string;
begin
  // Keep calling until a station answers (the original forces one after three dry CQs).
  Calls := 1;
  while (Dbg(0) = 0) and (Calls < 6) do
  begin
    Entry.FormSendMsg(C, msgCQ);
    Inc(Calls);
    Pull(40, False);
    WaitDone(240);
    Pull(20, False);
  end;
  WriteLn(Tag, ': calls=', Calls, ' stations=', Dbg(0));
  Dump(Tag + ' after calling');
  if Dbg(0) = 0 then
    Exit;

  HisCall := C.Stations[0].MyCall;
  E1 := C.Stations[0].Exch1;
  E2 := C.Stations[0].Exch2;
  WriteLn(Tag, ' caller: ', HisCall, ' exch=', E1, '/', E2);

  // Copy what was heard into the form, then answer in the order an operator works a QSO.
  MainForm.Edit1.Text := HisCall;
  MainForm.Edit2.Text := E1;
  MainForm.Edit3.Text := E2;
  WriteLn(Tag, ' dx at copy: ', DxState);
  WaitStationIdle(240);
  Entry.FormSendMsg(C, msgHisCall);
  WaitDone(240);
  WriteLn(Tag, ' dx after his call: ', DxState);
  WaitStationIdle(240);
  Entry.FormSendMsg(C, msgNR);
  WaitDone(240);
  WriteLn(Tag, ' dx after our exch: ', DxState);
  WaitStationIdle(240);
  WriteLn(Tag, ' dx before TU: ', DxState);

  // Same body as mr_save_qso.
  if not C.CheckEnteredCallLength(MainForm.Edit1.Text, E2) then
    WriteLn(Tag, ' call rejected: ', E2);
  C.OnExchangeEditComplete;
  if not Log.CallSent then
    Entry.FormSendMsg(C, msgHisCall);
  Entry.FormSendMsg(C, msgTU);
  Log.SaveQso;

  Saved := Length(QsoList);
  WriteLn(Tag, ' saved log rows=', Saved, ' raw pts=', RawPoints, ' raw mult=', RawMultList.Count,
    ' verified pts=', VerifiedPoints, ' verified mult=', VerifiedMultList.Count);
  // The rendered row is what mr_log_cell hands the UI; print it so the harness proves the
  // log columns too, not just the record count.
  if MainForm.ListView2.Items.Count > 0 then
  begin
    Write(Tag, ' log row ', MainForm.ListView2.Items.Count - 1, ': ',
      MainForm.ListView2.Items[MainForm.ListView2.Items.Count - 1].Caption);
    j := MainForm.ListView2.Items.Count - 1;
    for k := 0 to MainForm.ListView2.Items[j].SubItems.Count - 1 do
      Write(' | ', MainForm.ListView2.Items[j].SubItems[k]);
    WriteLn;
  end;
  Pull(80, False);
  Dump(Tag + ' after save');

  // Listen for the DX's confirmation: when its operator finishes the QSO the core copies the
  // true data into the last record and re-scores it as Verified (Contest.pas:800-815). A duped
  // call is logged but never verified (Log.UpdateStats counts only Error-free QSOs), and with the
  // call history unloaded every caller is the 'P29SX' fallback, so a second QSO is a dupe.
  if QsoList[High(QsoList)].Dupe then
    WriteLn(Tag, ' dupe: verified stays ', VerifiedPoints, ' (same call worked twice)')
  else
  begin
    j := 0;
    while (VerifiedPoints < Length(QsoList)) and (j < 1200) do
    begin
      Pull(10, False);
      Inc(j, 10);
    end;
    WriteLn(Tag, ' verified after ', j, ' more blocks: pts=', VerifiedPoints,
      ' mult=', VerifiedMultList.Count, ' (raw pts=', RawPoints,
      ' raw mult=', RawMultList.Count, ')');
  end;
  Dump(Tag + ' after confirmation');
end;

var
  DataDirArg: string;
  Blocks: Integer;

begin
  DataDirArg := ParamStr(1);
  if DataDirArg = '' then
    DataDirArg := '..\..\app\src\main\assets\data';
  if not DirectoryExists(DataDirArg) then
  begin
    WriteLn('data dir not found: ', DataDirArg);
    Halt(2);
  end;
  DataDir.MRDataDir := IncludeTrailingPathDelimiter(ExpandFileName(DataDirArg));

  MakeKeyer(DEFAULTRATE, Ini.BufSize);
  Histo := THisto.Create(MainForm.PaintBox1);
  try
    try
      C := Session.StartRun(0, 'VE3NEA');
      if C = nil then
      begin
        WriteLn('StartRun FAILED');
        Halt(3);
      end;
      WriteLn('run: ', C.ClassName, ' data=', DataDir.MRDataDir);
      Dump('start');
      if ParamStr(2) = 'dumpaudio' then
      begin
        DumpAudio('audio-dump.bin', 300);
        WriteLn('audio dumped to audio-dump.bin');
        Halt(0);
      end;

      // 1. One CQ, and listen to the operator's own signal.
      Pull(2, False);
      Entry.FormSendMsg(C, msgCQ);
      Dump('after FormSendMsg(msgCQ)');
      Pull(130, True);
      WriteLn('peak during CQ = ', Peak:0:3);
      Blocks := WaitDone(240);
      WriteLn('operator finished after ', Blocks, ' blocks');
      Dump('after CQ');

      // 2. The first QSO: rate 0 before it, non-zero after it.
      WriteLn('rate before any QSO: "', Session.StatusText(0), '"');
      WorkQso('qso 1');
      WriteLn('rate after QSO 1: "', Session.StatusText(0), '"');

      // 3. A second QSO: the rate has to move with it (it is a 5-minute window, not a counter).
      WorkQso('qso 2');
      WriteLn('rate after QSO 2: "', Session.StatusText(0), '"');

      // 4. Stop -> Run: a second StartRun must show the clean form, not the end of the first run.
      C := Session.StartRun(0, 'VE3NEA');
      WriteLn('restart: log=', Length(QsoList), ' raw pts=', RawPoints,
        ' verified pts=', VerifiedPoints, ' raw mult=', RawMultList.Count,
        ' verified mult=', VerifiedMultList.Count,
        ' rows=', MainForm.ListView2.Items.Count,
        ' edit1="', MainForm.Edit1.Text, '" callSent=', Log.CallSent);
      Dump('after restart');
    except
      on E: Exception do
        WriteLn('EXCEPTION: ', E.ClassName, ': ', E.Message);
    end;
  finally
    // The RTL frees the units at exit; nothing else to do here.
  end;
  WriteLn('harness done');
end.
