unit Trace;
{ One line at a time into `mr-trace.log` in the data directory.

  Why the engine needs this: the board is an aarch64 Android library called from Java threads, so
  a crash there leaves either a tombstone with no symbols for the Pascal code or — for a raise on a
  thread the RTL did not start — nothing at all ("exited cleanly (255)"). A breadcrumb written
  *before* the call that dies is then the only evidence that survives. It is a diagnostic, not a
  log: the game loop writes at most a handful of lines per QSO (see `Contest.pas`'s post-QSO
  block), never per block.

  The path is the data directory the UI already hands the core (`mr_set_data_dir`), so the file
  lands next to DXCC.LIST in the app's files dir and `adb shell run-as` can read it. }

{$mode delphi}{$H+}

interface

{ Append `S`, timestamped, to `mr-trace.log`. Never raises: tracing must not be the reason a QSO
  fails. Silently does nothing until a data directory is known. }
procedure TraceLine(const S: string);

{ Install the fault handler (SIGSEGV/SIGBUS/SIGILL/SIGFPE). It appends one `FAULT` line naming the
  stage and the block, then re-raises so the tombstone is still produced. }
procedure InstallCrashHandler;

{ The audio thread's current stage, as a number (see `Contest.pas TContest.GetAudio`).

  A native crash there leaves no traceback for the Pascal frames, and a file write per stage would
  cost the audio thread far more than the diagnostic is worth — so this is one integer, and the UI
  samples it every poll and logs it when it changes. Whatever the last value in logcat is, that is
  the stage the thread died in. 0 means "outside a block". }
var
  LastSection: Integer = 0;
  LastBlock: Integer = 0;

procedure Mark(Section: Integer);
procedure MarkBlock(Block: Integer);

implementation

uses
  SysUtils, BaseUnix, DataDir;

var
  { Prepared by InstallCrashHandler: the handler must not build a path (allocating during a fault
    is how a diagnostic turns into a second crash). }
  FaultPath: AnsiString = '';
  FaultFd: cint = -1;

procedure WriteRaw(Fd: cint; const S: Ansistring);
var
  N: SizeInt;
begin
  N := Length(S);
  if (N > 0) and (fpWrite(Fd, S[1], N) < 0) then
    { Nothing sane left to do from a fault handler. };
end;

procedure AppendUInt(var Buf: Ansistring; Value: LongInt);
var
  Digits: array[0..11] of AnsiChar;
  N: Integer;
begin
  if Value < 0 then
  begin
    Buf := Buf + '-';
    Value := -Value;
  end;
  N := 0;
  repeat
    Digits[N] := AnsiChar(Ord('0') + (Value mod 10));
    Value := Value div 10;
    Inc(N);
  until Value = 0;
  while N > 0 do
  begin
    Dec(N);
    Buf := Buf + Digits[N];
  end;
end;

{ The whole point of the handler: a native fault on the audio thread has no Pascal traceback, and
  the RTL's own exception path is exactly what dies there. `LastSection` plus the block counter
  say WHERE, and they are read with plain moves — no heap, no strings, no locks. }
procedure OnFault(Sig: cint); cdecl;
var
  Buf: Ansistring;
begin
  FaultFd := -1;
  if FaultPath <> '' then
    FaultFd := fpOpen(FaultPath, O_WRONLY or O_APPEND or O_CREAT, &644);
  if FaultFd >= 0 then
  begin
    Buf := 'FAULT sig=';
    AppendUInt(Buf, Sig);
    Buf := Buf + ' stage=';
    AppendUInt(Buf, LastSection);
    Buf := Buf + ' block=';
    AppendUInt(Buf, LastBlock);
    Buf := Buf + #10;
    WriteRaw(FaultFd, Buf);
    fpClose(FaultFd);
  end;
  { Let the default action (and the tombstone) happen. }
  fpSignal(Sig, SignalHandler(SIG_DFL));
  fpKill(fpGetPid, Sig);
end;

procedure InstallCrashHandler;
begin
  { Also called again after the UI sets the data dir, because the path has to be ready before any
    fault: see mr_set_data_dir. }
  if DataDir.MRDataDir <> '' then
    FaultPath := AnsiString(DataDir.MRDataDir + 'mr-trace.log');
  fpSignal(SIGSEGV, @OnFault);
  fpSignal(SIGBUS, @OnFault);
  fpSignal(SIGILL, @OnFault);
  fpSignal(SIGFPE, @OnFault);
end;

procedure Mark(Section: Integer);
begin
  LastSection := Section;
end;

procedure MarkBlock(Block: Integer);
begin
  LastBlock := Block;
end;

procedure TraceLine(const S: string);
var
  F: TextFile;
  Path: string;
begin
  Path := DataDir.MRDataDir;
  if Path = '' then
    Exit;
  try
    {$I-}
    AssignFile(F, Path + 'mr-trace.log');
    if FileExists(Path + 'mr-trace.log') then
      Append(F)
    else
      Rewrite(F);
    {$I+}
    if IOResult <> 0 then
      Exit;
    try
      WriteLn(F, FormatDateTime('hh:nn:ss.zzz', Now), ' ', S);
    finally
      CloseFile(F);
    end;
  except
    { Swallowed on purpose — see the unit comment. }
  end;
end;

end.
