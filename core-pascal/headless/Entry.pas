unit Entry;
{ The form's message entry point, shared by the ABI library (mrcore.lpr) and the native harness.

  Upstream this lives in TMainForm.SendMsg (Main.pas:563), i.e. in the VCL form. It is not a
  convenience wrapper: TStation.SendMsg is what records the message into Me.Msg, and Me.Msg is
  what the contest reads when the message ends ("was that a CQ? then put callers on the air").
  Calling TContest.SendMsg directly puts the CW on the air but leaves Me.Msg empty, so nobody
  ever answers — that was the "CQ is silent and nobody calls" defect. }

{$mode delphi}{$H+}

interface

uses
  Contest, Station;

{ Send one canned message (F1..F8 / the Enter path) exactly as the desktop form did. }
procedure FormSendMsg(C: TContest; AMsg: TStationMessage);

implementation

uses
  Main, Log;

procedure FormSendMsg(C: TContest; AMsg: TStationMessage);
begin
  if AMsg = msgHisCall then
  begin
    { The form kept the copied call in Edit1 and pushed it into the operator station here. }
    C.SetHisCall(MainForm.Edit1.Text);
    MainForm.RecvExchTypes := C.GetRecvExchTypes(skMyStation, C.Me.MyCall, C.Me.HisCall);
  end;
  if AMsg = msgNR then
    Log.NrSent := true;
  C.Me.SendMsg(AMsg);
end;

end.
