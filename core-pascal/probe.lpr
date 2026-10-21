program probe;
{ Host-side reproduction harness.

  The Android library crashes while creating a contest; installing an APK to see a bare address
  is a two-minute loop with no line numbers. The same units compile for the Windows host, where
  a crash prints a real stack trace (build with -gl), so this program is the tight loop for
  diagnosing the core. It is NOT shipped: it exists only to exercise the contest lifecycle. }
{$mode delphi}{$H+}

uses
  SysUtils, RndFunc, Contest, Ini,
  ArrlDx, ArrlFd, ArrlSS, CqWW, CqWpx, NaQp, IaruHf, ACAG, ALLJA, CWOPS, CWSST, DualExchContest;

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

var
  Idx: Integer;
  C: TContest;
begin
  WriteLn('contests: ', Ord(High(TSimContest)) + 1);
  for Idx := 0 to Ord(High(TSimContest)) do
  begin
    Write('  ', Idx, ' ', string(ContestDefinitions[TSimContest(Idx)].Name), ' ... ');
    Flush(Output);
    C := nil;
    try
      C := CreateContestFor(TSimContest(Idx));
      if C = nil then
        WriteLn('nil')
      else
      begin
        WriteLn('ok ', C.ClassName);
        C.Free;
      end;
    except
      on E: Exception do
        WriteLn('EXCEPTION ', E.ClassName, ': ', E.Message);
    end;
  end;
  WriteLn('done');
end.
