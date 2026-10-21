unit CwCodes;

{ CW contest short codes for the OPERATOR's own transmission (TMyStation.SendText): a report is sent 5NN, not 599, and
  the exchange digits carry the abbreviations the DX stations in Station.pas already use
  (9 -> N, 0 -> T, and 1 -> A for a CQ zone). The stored exchange stays numeric -- Contest.pas
  parses it with StrToIntDef -- so this unit only rewrites the text handed to the keyer.

  It lives in its own unit because both Station.pas (which encodes the message) and Contest.pas
  (which knows when the operator, rather than a DX station, is transmitting) need to see it. }

{$mode objfpc}{$H+}

interface

{ '599' -> '5NN', '599 123' -> '5NN 123', 'KN9E' -> 'KN9E' (callsigns are never touched).
  ACutOne maps 1 -> A, which is only right for a CQ zone and is why the caller passes the exchange
  types in. }
function ShortCwCode(const S: string; ACutOne: Boolean): string;

implementation

uses SysUtils;

{ Shorten one word of the operator's message. Two shapes are touched and nothing else:

    '599001'  -- a report + serial straight out of NrAsText
    '5NN001'  -- the same after NrAsText already rewrote the report (Station.pas)

  Anything else, and in particular every callsign, is returned untouched: 9 -> N applied to N0NF
  would key NTNF, a different station. ACutOne maps 1 -> A, which is only right for a CQ zone. }
function ShortWord(const W: string; ACutOne: Boolean): string;
var
  k, Tail: Integer;
  AllDigits: Boolean;
begin
  Result := W;
  Tail := 1;

  AllDigits := W <> '';
  for k := 1 to Length(W) do
    if not (W[k] in ['0'..'9']) then
      AllDigits := False;

  if AllDigits then
  begin
    // A leading report: 599 -> 5NN (same length, so the digit tail still starts at 4).
    if (Length(Result) >= 3) and (Copy(Result, 1, 3) = '599') then
    begin
      Result := '5NN' + Copy(Result, 4, MaxInt);
      Tail := 4;
    end;
  end
  else if (Length(W) >= 4) and (W[1] in ['1'..'9']) and (W[2] = 'N') and (W[3] = 'N') then
    Tail := 4                    // NrAsText's finished report; only its numbers are left
  else
    Exit;                        // a callsign or plain text: never cut

  for k := Tail to Length(Result) do
    if Result[k] = '9' then Result[k] := 'N'
    else if Result[k] = '0' then Result[k] := 'T'
    else if ACutOne and (Result[k] = '1') then Result[k] := 'A';
end;

function ShortCwCode(const S: string; ACutOne: Boolean): string;
var
  i: Integer;
  W: string;
begin
  Result := '';
  W := '';
  for i := 1 to Length(S) do
    if S[i] = ' ' then
    begin
      Result := Result + ShortWord(W, ACutOne) + ' ';
      W := '';
    end
    else
      W := W + S[i];
  Result := Result + ShortWord(W, ACutOne);
end;

end.
