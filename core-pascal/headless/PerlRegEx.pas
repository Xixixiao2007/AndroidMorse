unit PerlRegEx;
{ Headless replacement for the bundled PerlRegEx (which links 5 Delphi .obj files and therefore
  cannot exist in an Android ARM64 library). It keeps the same public surface for the members the
  contest logic actually uses, implemented on top of FPC's own RegExpr engine.

  Named groups: FPC's engine has none, so `(?<name>...)` is rewritten to a plain group at compile
  time and the declaration order is remembered; NamedGroup(name) returns that index. }

{$mode delphi}{$H+}

interface

uses
  SysUtils, Classes, RegExpr;

type
  PCREString = string;

  TPerlRegExOption = (preCaseLess, preMultiLine, preSingleLine, preExtended,
                      preAnchored, preUnGreedy, preNoAutoCapture, preNotBOL, preNotEOL, preNotEmpty);
  TPerlRegExOptions = set of TPerlRegExOption;

  TPerlRegEx = class
  private
    FRegEx: PCREString;
    FSubject: PCREString;
    FCompiled: Boolean;
    FFoundMatch: Boolean;
    FMatchedText: PCREString;
    FMatchedOffset: Integer;
    FOptions: TPerlRegExOptions;
    FStartPos, FStopPos: Integer;
    FGroups: TStringList;
    FNames: TStringList;
    FExec: TRegExpr;
    procedure SetRegEx(const Value: PCREString);
    procedure SetSubject(const Value: PCREString);
    function GetGroupCount: Integer;
    function GetGroup(Index: Integer): PCREString;
    procedure TranslateNamedGroups;
    procedure CaptureGroups;
  public
    constructor Create;
    destructor Destroy; override;
    class function EscapeRegExChars(const S: string): string;
    procedure Compile;
    procedure Study;
    function Match: Boolean;
    function MatchAgain: Boolean;
    function NamedGroup(const Name: PCREString): Integer;
    procedure StoreGroups;
    procedure Clear;
    property Compiled: Boolean read FCompiled;
    property FoundMatch: Boolean read FFoundMatch;
    property Studied: Boolean read FCompiled;
    property MatchedText: PCREString read FMatchedText;
    { 0-based offset of the match, as upstream exposes it. }
    property MatchedOffset: Integer read FMatchedOffset;
    property Options: TPerlRegExOptions read FOptions write FOptions;
    { 1-based match boundaries, as the original PerlRegEx exposes them. }
    property Start: Integer read FStartPos write FStartPos;
    property Stop: Integer read FStopPos write FStopPos;
    property GroupCount: Integer read GetGroupCount;
    property Groups[Index: Integer]: PCREString read GetGroup;
    property RegEx: PCREString read FRegEx write SetRegEx;
    property Subject: PCREString read FSubject write SetSubject;
  end;

implementation

constructor TPerlRegEx.Create;
begin
  inherited Create;
  FExec := TRegExpr.Create;
  FGroups := TStringList.Create;
  FNames := TStringList.Create;
end;

destructor TPerlRegEx.Destroy;
begin
  FExec.Free;
  FGroups.Free;
  FNames.Free;
  inherited;
end;

class function TPerlRegEx.EscapeRegExChars(const S: string): string;
var
  I: Integer;
begin
  { FPC's RegExpr has no EscapeRegExChars; escape the metacharacters ourselves. }
  Result := '';
  for I := 1 to Length(S) do
  begin
    if S[I] in ['\', '^', '$', '.', '[', ']', '|', '(', ')', '*', '+', '?', '{', '}'] then
      Result := Result + '\';
    Result := Result + S[I];
  end;
end;

procedure TPerlRegEx.SetRegEx(const Value: PCREString);
begin
  FRegEx := Value;
  FCompiled := False;
end;

procedure TPerlRegEx.SetSubject(const Value: PCREString);
begin
  FSubject := Value;
  FFoundMatch := False;
end;

procedure TPerlRegEx.TranslateNamedGroups;
var
  S, Out_: string;
  I, J: Integer;
  Name: string;
begin
  FNames.Clear;
  S := FRegEx;
  SetLength(Out_, 0);
  I := 1;
  while I <= Length(S) do
  begin
    if (S[I] = '(') and (I + 2 <= Length(S)) and (S[I + 1] = '?') and (S[I + 2] = '<') then
    begin
      J := I + 3;
      Name := '';
      while (J <= Length(S)) and (S[J] <> '>') do
      begin
        Name := Name + S[J];
        Inc(J);
      end;
      if (J <= Length(S)) and (S[J] = '>') then
      begin
        FNames.Add(Name);
        Out_ := Out_ + '(';
        I := J + 1;
        Continue;
      end;
    end;
    Out_ := Out_ + S[I];
    Inc(I);
  end;
  FExec.Expression := Out_;
end;

procedure TPerlRegEx.Compile;
begin
  TranslateNamedGroups;
  { The subset of PCRE options the contest logic sets. }
  FExec.ModifierI := preCaseLess in FOptions;
  FExec.ModifierM := preMultiLine in FOptions;
  FCompiled := True;
end;

procedure TPerlRegEx.Study;
begin
  if not FCompiled then Compile;
end;

procedure TPerlRegEx.CaptureGroups;
var
  I: Integer;
begin
  FGroups.Clear;
  FMatchedText := FExec.Match[0];
  FMatchedOffset := FExec.MatchPos[0] - 1;
  FStartPos := FExec.MatchPos[0];
  FStopPos := FExec.MatchPos[0] + FExec.MatchLen[0] - 1;
  for I := 0 to FExec.SubExprMatchCount do
    FGroups.Add(FExec.Match[I]);
end;

function TPerlRegEx.Match: Boolean;
begin
  if not FCompiled then Compile;
  FFoundMatch := FExec.Exec(FSubject);
  { preAnchored: PCRE refuses a match that does not start at offset 0. }
  if FFoundMatch and (preAnchored in FOptions) and (FExec.MatchPos[0] <> 1) then
    FFoundMatch := False;
  if FFoundMatch then CaptureGroups;
  Result := FFoundMatch;
end;

function TPerlRegEx.MatchAgain: Boolean;
begin
  if not FCompiled then Compile;
  FFoundMatch := FExec.ExecNext;
  if FFoundMatch then CaptureGroups;
  Result := FFoundMatch;
end;

function TPerlRegEx.NamedGroup(const Name: PCREString): Integer;
begin
  { 1-based, like PCRE: group 0 is the whole match. }
  Result := FNames.IndexOf(Name);
  if Result >= 0 then Inc(Result) else Result := -1;
end;

function TPerlRegEx.GetGroupCount: Integer;
begin
  Result := FExec.SubExprMatchCount;
end;

function TPerlRegEx.GetGroup(Index: Integer): PCREString;
begin
  if (Index >= 0) and (Index < FGroups.Count) then
    Result := FGroups[Index]
  else
    Result := '';
end;

procedure TPerlRegEx.StoreGroups;
begin
end;

procedure TPerlRegEx.Clear;
begin
  FFoundMatch := False;
  FMatchedText := '';
  FMatchedOffset := -1;
  FGroups.Clear;
  FCompiled := False;
end;

end.
