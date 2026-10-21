unit Graphics;
{ Headless stand-in for the VCL Graphics unit: the contest logic only needs TColor and a handful of
  named colours (it paints the rate histogram into a TCanvas, which is stubbed in headless/Main.pas). }

{$mode delphi}{$H+}

interface

type
  TColor = LongInt;
  TFontStyle = (fsBold, fsItalic, fsUnderline, fsStrikeOut);
  TFontStyles = set of TFontStyle;

  { Only the attributes the logic assigns (status bar emphasis). }
  TFont = class
  public
    Color: TColor;
    Size: Integer;
    Name: string;
    Style: TFontStyles;
  end;

const
  clDefault = $20000000;  { Delphi's CLR_DEFAULT }
  clBlack   = $000000;
  clWhite   = $FFFFFF;
  clRed     = $0000FF;
  clGreen   = $008000;
  clBlue    = $FF0000;
  clYellow  = $00FFFF;
  clLime    = $00FF00;
  clGray    = $808080;
  clSilver  = $C0C0C0;
  clAqua    = $FFFF00;
  clFuchsia = $FF00FF;
  clTeal    = $808000;
  clNavy    = $800000;
  clOlive   = $008080;

implementation

end.
