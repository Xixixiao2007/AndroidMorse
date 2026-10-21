unit Dialogs;
{ Headless stand-in for VCL Dialogs: the contest logic only reaches for a modal message box on
  paths that cannot happen without a user (an invalid exchange, a missing file). }
{$mode delphi}{$H+}
interface
uses Classes;

type
  TMsgDlgType = (mtWarning, mtError, mtInformation, mtConfirmation, mtCustom);
  TMsgDlgBtn = (mbYes, mbNo, mbOK, mbCancel, mbAbort, mbRetry, mbIgnore, mbAll, mbNoToAll, mbYesToAll, mbHelp);
  TMsgDlgButtons = set of TMsgDlgBtn;

const
  mrNone = 0; mrOk = 1; mrCancel = 2; mrAbort = 3; mrRetry = 4;
  mrIgnore = 5; mrYes = 6; mrNo = 7; mrAll = 8; mrNoToAll = 9; mrYesToAll = 10;

function MessageDlg(const Msg: string; DlgType: TMsgDlgType; Buttons: TMsgDlgButtons; HelpCtx: LongInt): Integer;
procedure ShowMessage(const Msg: string);

implementation

function MessageDlg(const Msg: string; DlgType: TMsgDlgType; Buttons: TMsgDlgButtons; HelpCtx: LongInt): Integer;
begin
  Result := mrOk;   { headless: nobody to answer }
end;

procedure ShowMessage(const Msg: string);
begin
end;

end.
