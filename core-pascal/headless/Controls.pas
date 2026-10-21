unit Controls;
{ Headless stand-in so `uses Controls` resolves; nothing in the logic needs real controls. }
{$mode delphi}{$H+}
interface
uses Classes;
type
  TControl = class(TComponent);
implementation
end.
