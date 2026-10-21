unit DataDir;
{ Where the bundled data files live.

  The desktop app assumed the call-history tables and the DXCC prefix list sat next to the
  executable and read them with ParamStr(1). On Android there is no such thing: ParamStr(1) is
  empty, so every one of those loads failed silently (or raised). The core now takes an explicit
  directory from the UI (mr_set_data_dir), which points at the app's private files dir where the
  APK's assets have been copied.

  A missing file is not fatal: the contest still starts, it simply has no call history and no
  multipliers. That is much better than a SIGSEGV inside a constructor. }

{$mode delphi}{$H+}

interface

uses Classes;

var
  { Directory the data files are read from. Empty means "current directory", which is what the
    host builds and the tests want. Always stored WITH a trailing slash. }
  MRDataDir: string;

{ Load AName into L, or clear L when the file is absent. Never raises. }
procedure LoadDataFile(L: TStringList; const AName: string);

{ True when AName exists in MRDataDir. Case-insensitive on purpose: the contest units ask for
  'CQWWCW.TXT', 'FDGOTA.TXT', 'SSCW.TXT' while the APK ships .txt, and Android's filesystem is
  case-sensitive -- a plain FileExists said "missing", the list stayed empty and the contest raised
  "Argument out of range" on every audio block (the game loop died, so no station ever appeared). }
function DataFileExists(const AName: string): Boolean;

{ The actual on-disk path for AName, or '' when it is absent. }
function ResolveDataFile(const AName: string): string;

implementation

uses SysUtils;

function ResolveDataFile(const AName: string): string;
var
  SR: TSearchRec;
begin
  Result := MRDataDir + AName;
  if FileExists(Result) then
    Exit;
  Result := '';
  if FindFirst(MRDataDir + '*', faAnyFile, SR) = 0 then
  try
    repeat
      if (SR.Attr and faDirectory) = 0 then
        if SameText(SR.Name, AName) then
        begin
          Result := MRDataDir + SR.Name;
          Break;
        end;
    until FindNext(SR) <> 0;
  finally
    FindClose(SR);
  end;
end;

function DataFileExists(const AName: string): Boolean;
begin
  Result := ResolveDataFile(AName) <> '';
end;

procedure LoadDataFile(L: TStringList; const AName: string);
var
  Path: string;
begin
  if L = nil then
    Exit;
  Path := ResolveDataFile(AName);
  if Path <> '' then
    L.LoadFromFile(Path)
  else
    L.Clear;
end;

end.
