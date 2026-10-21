unit Session;
{ Starting a run, shared by the Android ABI library (mrcore.lpr) and the native harness.

  Both entry points must do exactly the same thing, otherwise the harness proves something about
  a different program than the one on the phone. The contest factory is copied verbatim from
  TMainForm.CreateContest (Main.pas): the mapping from the menu enum to a concrete subclass is
  original behaviour, and scWpx and scHst deliberately share TCqWpx. }

{$mode delphi}{$H+}

interface

uses
  Contest, Ini;

{ Instantiate the contest for a TSimContest index, or nil when the index has no subclass. }
function CreateContestFor(AContestId: TSimContest): TContest;

{ Begin a run: fresh log, Pile-Up mode (the upstream default), the operator call, and the
  unit-level Tst that Log.pas reads. Returns the running contest, or nil on a bad index. }
function StartRun(Idx: Integer; const MyCall: string): TContest;

{ One of the three status captions the game loop rewrites (Contest.pas:839-842):
  0 = the QSO rate (`n  qso/hr.`, Log.pas ShowRate), 1 = the run clock (`hh:nn:ss`),
  2 = the Pile-Up caption (`Pile-Up:  n`). Empty string for an unknown index.

  Lives here rather than in the ABI library so the native harness reads the same text the phone
  does: the point of this ticket is that the rate is COMPUTED and only the reading was missing. }
var
  { Set once the DXCC table has been loaded against a known data directory. }
  DxLoaded: Boolean = False;

function StatusText(Which: Integer): string;

implementation

uses
  SysUtils, Trace, Log, Main, ArrlDx, ArrlFd, ArrlSS, CqWW, CqWpx, NaQp, IaruHf, ACAG, ALLJA, CWOPS, DXCC,
  CWSST, DualExchContest;

function CreateContestFor(AContestId: TSimContest): TContest;
begin
  case AContestId of
    scWpx, scHst: Result := TCqWpx.Create;
    scCwt:        Result := TCWOPS.Create;
    scFieldDay:   Result := TArrlFieldDay.Create;
    scNaQp:       Result := TNcjNaQp.Create;
    scCQWW:       Result := TCqWW.Create;
    scArrlDx:     Result := TArrlDx.Create;
    scSst:        Result := TCWSST.Create;
    scAllJa:      Result := TALLJA.Create;
    scAcag:       Result := TACAG.Create;
    scIaruHf:     Result := TIaruHf.Create;
    scArrlSS:     Result := TSweepstakes.Create;
  else
    Result := nil;
  end;
end;

function StatusText(Which: Integer): string;
begin
  case Which of
    0: Result := MainForm.Panel7.Caption;
    1: Result := MainForm.Panel2.Caption;
    2: Result := MainForm.Panel4.Caption;
  else
    Result := '';
  end;
end;

function StartRun(Idx: Integer; const MyCall: string): TContest;
var
  SetCallError: string;
  DxProbe: TDXCCRec;
  DxProbeOk: Boolean;
  C: TContest;
begin
  Result := nil;
  if (Idx < 0) or (Idx > Ord(High(TSimContest))) then
    Exit;
  { The contest table pointer first: the constructor and every exchange-type query read it. }
  Ini.SetContestIndex(TSimContest(Idx));
  TraceLine(Format('start: idx=%d dxcc=%s exchtype=%d/%d', [Idx, BoolToStr(gDXCCList <> nil, True),
    Ord(Ini.ActiveContest^.ExchType1), Ord(Ini.ActiveContest^.ExchType2)]));
  if gDXCCList <> nil then
  begin
    { A direct probe of the table itself: if this line is missing from the trace, FindRec is what
      faults and the contest classes are innocent. }
    DxProbe := nil;
    DxProbeOk := gDXCCList.FindRec(DxProbe, 'W1AW');
    if DxProbeOk and (DxProbe <> nil) then
      TraceLine(Format('dxcc: W1AW found continent=%s entity=%s', [DxProbe.Continent, DxProbe.Entity]))
    else
      TraceLine(Format('dxcc: W1AW lookup returned %s', [BoolToStr(DxProbeOk, True)]));
  end;
  { FIRST, before the contest object exists: a contest constructor may already consult the DXCC
    table (NAQP does, through its call history), and a nil table there is a null-pointer fault. The
    data directory is set before any run starts, which is what makes DXCC.LIST readable. }
  if not DxLoaded then
  begin
    { Something creates the table before the data directory is known (a contest name lookup), and
      TDXCC.Create reads DXCC.LIST once: created too early it stays permanently empty, which is why
      every lookup returned False. Reload here, where MRDataDir is set for certain. }
    FreeAndNil(gDXCCList);
    gDXCCList := TDXCC.Create;
    DxLoaded := True;
    TraceLine(Format('dxcc: loaded, W1AW known=%s', [BoolToStr(gDXCCList.Search('W1AW') <> '', True)]));
  end;

  TraceLine('start: guard done');
  C := CreateContestFor(TSimContest(Idx));
  TraceLine('start: contest created');
  if C = nil then
    Exit;
  { Before OnSetMyCall, not after: a contest's OnSetMyCall runs its call history, and that code
    reaches for the global Tst (Log.pas and the station helpers read it). With Tst still nil -- or
    worse, the PREVIOUS contest -- those calls dereference a dead object. Measured: NAQP faulted in
    IsCallLocalToContest, 42 ms into the run, before the contest was ever assigned to Tst. }
  Tst := C;
  if MyCall <> '' then
  begin
    Ini.Call := MyCall;
    { OnSetMyCall is what assigns Me.SentExchTypes (Contest.pas:264) -- the types that decide whether
      the report goes out as 5NN. Writing Me.MyCall directly left them at ExchTypesUndef (255), so
      NrAsText's short-code branch never ran and the keyer spelled out 599. }
    C.OnSetMyCall(MyCall, SetCallError);
  end;
  { A fresh run starts from an empty log. Log.Clear itself is not usable here: it also clears
    Tst.Stations and repaints the VCL widgets, and those globals belong to the desktop app. }
  SetLength(QsoList, 0);
  { The desktop's Run handler cleared the QSO list view (Main.pas:2097-2103); the rendered rows
    are what the phone's log reads back, so they have to go with the records. }
  MainForm.ListView2.Items.Clear;
  RawPoints := 0;
  VerifiedPoints := 0;
  if RawMultList <> nil then
    RawMultList.Clear;
  if VerifiedMultList <> nil then
    VerifiedMultList.Clear;
  { The form's three edit boxes are the entry fields of the NEXT QSO, so they start empty like a
    freshly created form; Log.SaveQso reads them directly. }
  MainForm.Edit1.Text := '';
  MainForm.Edit2.Text := '';
  MainForm.Edit3.Text := '';
  { "My call / my exchange already went out" are per-QSO flags. Carried across a Run they tell the
    Enter handler to skip the DX call and make the first QSO of the new run look like a
    continuation of the old one. ResetQsoState only clears the call. }
  Log.CallSent := False;
  Log.NrSent := False;
  { The three captions the game loop rewrites (Contest.pas:839-842) are left over from the previous
    run until the first block arrives, so a Stop -> Run would show the old clock and the old rate
    for the first fraction of a second — and an old rate at all until a QSO lands. Reset them to
    the values the desktop form starts a run with. }
  MainForm.Panel2.Caption := '00:00:00';
  MainForm.Panel7.Caption := '0  qso/hr.';
  MainForm.Panel4.Caption := 'Pile-Up:  0';
  { Log.pas keeps a unit-level pointer to the running contest (the form assigned it in Run);
    ShowRate and the score updates read it. }
  { The desktop set this when the Run button was pressed; without it the contest stays in
    rmStop and never puts a station on the air. Pile-Up is the upstream default. }
  { HST is the one contest with its own run mode in the original menu (Speed Test): running it as
    Pile-Up scored the QSO as 0 points. Everything else starts in Pile-Up, the upstream default. }
  if Ini.SimContest = scHst then
    Ini.RunMode := rmHst
  else
    Ini.RunMode := rmPileUp;
  { The CW short codes live in NrAsText and are gated on the exchange types, so a wrong type there
    is silent: the report would go out as 599 and nothing would say why. One line at run start. }
  TraceLine(Format('exchtypes: sent1=%d sent2=%d recv1=%d recv2=%d mycall=%s ini=%s',
    [Ord(C.Me.SentExchTypes.Exch1), Ord(C.Me.SentExchTypes.Exch2),
     Ord(MainForm.RecvExchTypes.Exch1), Ord(MainForm.RecvExchTypes.Exch2),
     C.Me.MyCall, Ini.Call]));
  C.Init;
  { The desktop called this from the Run handler; without it LoadCallHistory never runs, the
    callsign pool stays empty and every caller is the same fallback call (Station.pas's P29SX).
    That is why the phone's callers never varied. }
  try
    C.OnContestPrepareToStart(C.Me.MyCall, '');
  except
    on E: Exception do
      TraceLine('prepare: ' + E.ClassName + ': ' + E.Message);
  end;
  Result := C;
end;

end.
