unit StdCtrls;
{ Headless stand-in for VCL StdCtrls. The widgets the logic touches live in headless/Main.pas;
  this unit exists so `uses StdCtrls` resolves. }
{$mode delphi}{$H+}
interface
uses Classes;
type
  TCustomEdit = class(TComponent)
  public
    Text: string;
  end;
implementation
end.
