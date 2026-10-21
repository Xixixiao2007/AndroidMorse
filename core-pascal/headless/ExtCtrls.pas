unit ExtCtrls;
{ Headless stand-in for VCL ExtCtrls. Supplies what the contest logic actually touches:
  TTimer (the contest clock), and the paint surface the rate histogram draws into. }
{$mode delphi}{$H+}
interface
uses Classes, Types;

type
  TBrush = class(TComponent)
  public
    Color: LongInt;
  end;

  TCanvas = class(TComponent)
  private
    FBrush: TBrush;
  public
    Color: LongInt;
    constructor Create(AOwner: TComponent); override;
    destructor Destroy; override;
    procedure FillRect(const R: TRect);
    property Brush: TBrush read FBrush;
  end;

  TPaintBox = class(TComponent)
  private
    FCanvas: TCanvas;
  public
    ClientWidth: Integer;
    ClientHeight: Integer;
    Width: Integer;
    Height: Integer;
    constructor Create(AOwner: TComponent); override;
    destructor Destroy; override;
    procedure Invalidate;
    property Canvas: TCanvas read FCanvas;
  end;

  TTimer = class(TComponent)
  private
    FInterval: Integer;
    FEnabled: Boolean;
    FOnTimer: TNotifyEvent;
  public
    constructor Create(AOwner: TComponent); override;
    property Interval: Integer read FInterval write FInterval;
    property Enabled: Boolean read FEnabled write FEnabled;
    property OnTimer: TNotifyEvent read FOnTimer write FOnTimer;
  end;

implementation

constructor TCanvas.Create(AOwner: TComponent);
begin
  inherited Create(AOwner);
  FBrush := TBrush.Create(nil);
end;

destructor TCanvas.Destroy;
begin
  FBrush.Free;
  inherited;
end;

procedure TCanvas.FillRect(const R: TRect);
begin
  { headless: nothing is drawn }
end;

constructor TPaintBox.Create(AOwner: TComponent);
begin
  inherited Create(AOwner);
  FCanvas := TCanvas.Create(nil);
end;

destructor TPaintBox.Destroy;
begin
  FCanvas.Free;
  inherited;
end;

procedure TPaintBox.Invalidate;
begin
end;

constructor TTimer.Create(AOwner: TComponent);
begin
  inherited Create(AOwner);
  FInterval := 1000;
end;

end.
