unit Main;
{ Headless stand-in for the VCL main form.
  The contest logic (Log.pas, Contest.pas, DxStn.pas, Station.pas) updates the UI directly; this unit
  supplies the same member names with no-op bodies so the logic can be compiled into libmrcore.so
  without any LCL/VCL/Windows dependency. Nothing here is meant to display anything. }

{$mode delphi}{$H+}

interface

uses
  SysUtils, Classes, Types, ExtCtrls, Graphics, Station, DXCC;  { Types: TRect; ExtCtrls: TPaintBox; Station: TExchTypes }

const
  { Scroll-bar message identifiers the logic passes to ListView.Perform. }
  WM_VSCROLL = $0115;
  SB_TOP = 6;
  SB_BOTTOM = 7;
  SB_LINEUP = 0;
  SB_LINEDOWN = 1;
  SB_PAGEUP = 2;
  SB_PAGEDOWN = 3;
  SB_THUMBPOSITION = 4;
  SB_THUMBTRACK = 5;

type
  { A single sub-item of a list row: the logic writes SubItems[0] := 'text'. }
  TStubSubItems = class
  private
    FItems: TStringList;
    function GetItem(Index: Integer): string;
    procedure SetItem(Index: Integer; const Value: string);
    function GetCount: Integer;
  public
    constructor Create;
    destructor Destroy; override;
    function Add(const S: string): Integer;
    procedure Clear;
    property Count: Integer read GetCount;
    property Items[Index: Integer]: string read GetItem write SetItem; default;
  end;

  { One row of a TListView. }
  TStubListItem = class
  private
    FSubItems: TStubSubItems;
  public
    Text: string;
    Selected: Boolean;
    property Caption: string read Text write Text;
    procedure Update;
    constructor Create;
    destructor Destroy; override;
    property SubItems: TStubSubItems read FSubItems;
  end;

  { TListView.Items }
  TStubListItems = class
  private
    FList: TList;
    function GetItem(Index: Integer): TStubListItem;
    function GetCount: Integer;
  public
    constructor Create;
    destructor Destroy; override;
    function Add: TStubListItem; overload;
    function Add(const S: string): TStubListItem; overload;
    procedure BeginUpdate;
    procedure EndUpdate;
    procedure Clear;
    property Count: Integer read GetCount;
    property Items[Index: Integer]: TStubListItem read GetItem; default;
  end;

  { TListView.Columns }
  TStubColumns = class
  private
    FCount: Integer;
  public
    procedure Add;
    procedure Delete(Index: Integer);
    property Count: Integer read FCount;
  end;

  TStubColumn = class
  public
    Width: Integer;
    Caption: string;
    Alignment: TAlignment;
  end;

  TStubListView = class
  public
    ItemIndex: Integer;
    SelCount: Integer;
    Sorted: Boolean;
    Caption: string;
  private
    FItems: TStubListItems;
    FColumns: TStubColumns;
    FColumnList: TList;
  public
    constructor Create;
    destructor Destroy; override;
    procedure Perform(Msg, WParam, LParam: Integer);
    procedure Clear;
    property Items: TStubListItems read FItems;
    property Columns: TStubColumns read FColumns;
    function GetColumn(Index: Integer): TStubColumn;
    property Column[Index: Integer]: TStubColumn read GetColumn;
  end;

  { TCanvas: the logic paints a 5-minute rate histogram. }



  TStubEdit = class
  public
    Text: string;
  end;

  { The rich edit's default font attributes; the logic sets the name. }
  TStubFontAttr = class
  public
    Name: string;
    Size: Integer;
    Color: LongInt;
  end;

  TStubRichEdit = class
  private
    FLines: TStringList;
    FDefAttributes: TStubFontAttr;
  public
    constructor Create;
    destructor Destroy; override;
    property Lines: TStringList read FLines;
    property DefAttributes: TStubFontAttr read FDefAttributes;
  end;

  { The recording component: the logic asks whether a WAV is open and writes captured audio into
    it. Headless there is no capture, so IsOpen stays False and nothing is written. }
  TStubWavFile = class
  public
    IsOpen: Boolean;
    procedure WriteFrom(Buf: Pointer; Dummy: Pointer; Size: Integer);
  end;

  { The monitor-level slider. Main.dfm:751 ships it at Value = 0.75 (-15 dB) and TContest.GetAudio
    turns that into the self-monitor gain; a bare zero-valued stub silently multiplied every block
    the operator sent to zero (noise included), so the CQ was inaudible. The default is therefore
    part of the contract, not a detail: headless builds must start where the desktop starts. }
  TStubSlider = class
  public
    Value: Double;
    Db: Double;
    constructor Create;
  end;

  TStubLabeledThing = class
  public
    Caption: string;
  end;

  TStubCombo = class
  public
    ItemIndex: Integer;
    Items: TStringList;
    constructor Create;
    destructor Destroy; override;
  end;

  TStubCheckBox = class
  public
    Checked: Boolean;
    Caption: string;
    Enabled: Boolean;
  end;

  TStubSpin = class
  public
    Value: Integer;
  end;

  TStubMenuItem = class
  public
    Checked: Boolean;
    Enabled: Boolean;
    Caption: string;
    Click: TNotifyEvent;
  end;

  TStubStatusBar = class
  public
    Caption: string;
    Font: TFont;
    Panels: TStubListItems;
    constructor Create;
    destructor Destroy; override;
  end;

  { The form itself: only the members the logic touches. Everything here exists to keep the
    contest logic compiling unchanged; nothing draws or responds. }
  TMainForm = class
  public
    ListView1: TStubListView;
    ListView2: TStubListView;
    PaintBox1: TPaintBox;
    RichEdit1: TStubRichEdit;
    VolumeSlider1: TStubSlider;
    AlWavFile1: TStubWavFile;
    Edit1, Edit2, Edit3: TStubEdit;
    Panel2, Panel4, Panel7, Panel11: TStubLabeledThing;
    Label3: TStubLabeledThing;
    CheckBox1, CheckBox2, CheckBox3, CheckBox4, CheckBox5, CheckBox6: TStubCheckBox;
    sbar: TStubStatusBar;
    ComboBox1, ComboBox2: TStubCombo;
    SpinEdit2, SpinEdit3: TStubSpin;
    mnuShowCallsignInfo: TStubMenuItem;
    ViewScoreBoardMNU: TStubMenuItem;
    { The contest's exchange-field types for the current contest, read all over the logic. }
    RecvExchTypes: TExchTypes;
    constructor Create;
    destructor Destroy; override;
    { Called by the logic when the run state changes; no-op headless. }
    procedure Run(Value: Integer);
    procedure WipeBoxes;
    procedure Advance;
    procedure PopupScore;
    procedure PopupScoreWpx;
    procedure PopupScoreHst;
    { Single-argument form methods: the untyped parameter keeps the call site compatible whatever
      the original signature was, because headless nothing is decided by the value. }
    procedure SetDefaultRunMode(const Value);
    procedure UpdSerialNR(const Value);
    procedure UpdSerialNRCustomRange(const Value);
    procedure UpdCWMinRxSpeed(const Value);
    procedure UpdCWMaxRxSpeed(const Value);
    procedure SetMyCall(const Value);
    procedure SetPitch(const Value);
    procedure SetBw(const Value);
    procedure SetMySerialNR;
    procedure ReadCheckBoxes;
    procedure PostHiScore(const Value);
    procedure SetContest(const Value);
    procedure SetWpm(const Value);
    procedure Operator1Click;
    procedure CWOPSNumberClick;
  end;

var
  MainForm: TMainForm;
  { Debug switches the contest logic reads; the original keeps them in this unit, driven by the
    config file. Headless they stay off. }
  BDebugExchSettings: Boolean = False;
  BDebugCwDecoder: Boolean = False;
  BDebugGhosting: Boolean = False;

implementation

{ TStubSubItems }

constructor TStubSubItems.Create;
begin
  inherited Create;
  FItems := TStringList.Create;
end;

destructor TStubSubItems.Destroy;
begin
  FItems.Free;
  inherited;
end;

function TStubSubItems.GetItem(Index: Integer): string;
begin
  while FItems.Count <= Index do FItems.Add('');
  Result := FItems[Index];
end;

procedure TStubSubItems.SetItem(Index: Integer; const Value: string);
begin
  while FItems.Count <= Index do FItems.Add('');
  FItems[Index] := Value;
end;

function TStubSubItems.Add(const S: string): Integer;
begin
  Result := FItems.Add(S);
end;

function TStubSubItems.GetCount: Integer;
begin
  Result := FItems.Count;
end;

procedure TStubSubItems.Clear;
begin
  FItems.Clear;
end;

{ TStubListItem }

constructor TStubListItem.Create;
begin
  inherited Create;
  FSubItems := TStubSubItems.Create;
end;

destructor TStubListItem.Destroy;
begin
  FSubItems.Free;
  inherited;
end;

procedure TStubListItem.Update;
begin
end;

{ TStubListItems }

constructor TStubListItems.Create;
begin
  inherited Create;
  FList := TList.Create;
end;

destructor TStubListItems.Destroy;
var
  I: Integer;
begin
  for I := 0 to FList.Count - 1 do TStubListItem(FList[I]).Free;
  FList.Free;
  inherited;
end;

function TStubListItems.GetItem(Index: Integer): TStubListItem;
begin
  Result := TStubListItem(FList[Index]);
end;

function TStubListItems.GetCount: Integer;
begin
  Result := FList.Count;
end;

function TStubListItems.Add: TStubListItem;
begin
  Result := TStubListItem.Create;
  FList.Add(Result);
end;

function TStubListItems.Add(const S: string): TStubListItem;
begin
  Result := Add;
  Result.Text := S;
end;

procedure TStubListItems.BeginUpdate;
begin
end;

procedure TStubListItems.EndUpdate;
begin
end;

procedure TStubListItems.Clear;
var
  I: Integer;
begin
  for I := 0 to FList.Count - 1 do TStubListItem(FList[I]).Free;
  FList.Clear;
end;

{ TStubColumns }

procedure TStubColumns.Add;
begin
  Inc(FCount);
end;

procedure TStubColumns.Delete(Index: Integer);
begin
  if FCount > 0 then Dec(FCount);
end;

{ TStubListView }

procedure TStubListView.Clear;
begin
  FItems.Clear;
  ItemIndex := -1;
  SelCount := 0;
end;

function TStubListView.GetColumn(Index: Integer): TStubColumn;
begin
  while FColumnList.Count <= Index do FColumnList.Add(TStubColumn.Create);
  Result := TStubColumn(FColumnList[Index]);
end;


constructor TStubListView.Create;
begin
  inherited Create;
  FColumnList := TList.Create;
  FItems := TStubListItems.Create;
  FColumns := TStubColumns.Create;
end;

destructor TStubListView.Destroy;
begin
  FItems.Free;
  FColumns.Free;
  inherited;
end;

procedure TStubListView.Perform(Msg, WParam, LParam: Integer);
begin
end;







{ TStubRichEdit }

constructor TStubRichEdit.Create;
begin
  inherited Create;
  FLines := TStringList.Create;
  FDefAttributes := TStubFontAttr.Create;
end;

destructor TStubRichEdit.Destroy;
begin
  FDefAttributes.Free;
  FLines.Free;
  inherited;
end;

{ TMainForm }

constructor TMainForm.Create;
var
  i: Integer;
  Row: TStubListItem;
begin
  inherited Create;
  gDXCCList := TDXCC.Create;
  ListView1 := TStubListView.Create;
  ListView2 := TStubListView.Create;
  { Main.dfm:329 — ListView1 is the score table and ships with its three rows (Pts, Mult, Score)
    and the two result columns (Raw, Verified). The scoring code writes Items[0..2].SubItems[0..1]
    directly, so an empty list is an index error the first time a QSO is saved. }
  for i := 1 to 3 do
  begin
    Row := ListView1.Items.Add;
    Row.SubItems.Add('');
    Row.SubItems.Add('');
  end;
  PaintBox1 := TPaintBox.Create(nil);
  RichEdit1 := TStubRichEdit.Create;
  VolumeSlider1 := TStubSlider.Create;
  AlWavFile1 := TStubWavFile.Create;
  Edit1 := TStubEdit.Create;
  Edit2 := TStubEdit.Create;
  Edit3 := TStubEdit.Create;
  Panel2 := TStubLabeledThing.Create;
  Panel4 := TStubLabeledThing.Create;
  Panel7 := TStubLabeledThing.Create;
  Panel11 := TStubLabeledThing.Create;
  Label3 := TStubLabeledThing.Create;
  CheckBox1 := TStubCheckBox.Create;
  CheckBox2 := TStubCheckBox.Create;
  CheckBox3 := TStubCheckBox.Create;
  CheckBox4 := TStubCheckBox.Create;
  CheckBox5 := TStubCheckBox.Create;
  CheckBox6 := TStubCheckBox.Create;
  sbar := TStubStatusBar.Create;
  ComboBox1 := TStubCombo.Create;
  ComboBox2 := TStubCombo.Create;
  SpinEdit2 := TStubSpin.Create;
  SpinEdit3 := TStubSpin.Create;
  mnuShowCallsignInfo := TStubMenuItem.Create;
  ViewScoreBoardMNU := TStubMenuItem.Create;
end;

destructor TMainForm.Destroy;
begin
  ListView1.Free; ListView2.Free; PaintBox1.Free; RichEdit1.Free;
  VolumeSlider1.Free; AlWavFile1.Free;
  Edit1.Free; Edit2.Free; Edit3.Free;
  Panel2.Free; Panel4.Free; Panel7.Free; Panel11.Free; Label3.Free; sbar.Free;
  ComboBox1.Free; ComboBox2.Free; SpinEdit2.Free; SpinEdit3.Free;
  mnuShowCallsignInfo.Free; ViewScoreBoardMNU.Free;
  gDXCCList.Free;
  inherited;
end;

procedure TMainForm.Run(Value: Integer);
begin
end;

procedure TMainForm.Advance;
begin
end;

procedure TMainForm.WipeBoxes;
begin
end;

procedure TMainForm.PopupScore;
begin
end;

procedure TMainForm.PopupScoreWpx;
begin
end;

procedure TMainForm.PopupScoreHst;
begin
end;

procedure TStubWavFile.WriteFrom(Buf: Pointer; Dummy: Pointer; Size: Integer);
begin
end;

{ TStubSlider }
constructor TStubSlider.Create;
begin
  inherited Create;
  { Main.dfm:751: Value = 0.75, Db = -15. TContest.GetAudio reads Value for the monitor gain. }
  Value := 0.75;
  Db := -15;
end;

{ TStubCombo }
constructor TStubCombo.Create;
begin
  inherited Create;
  Items := TStringList.Create;
  ItemIndex := -1;
end;

destructor TStubCombo.Destroy;
begin
  Items.Free;
  inherited;
end;

{ TStubStatusBar }
constructor TStubStatusBar.Create;
begin
  inherited Create;
  Font := TFont.Create;
  Panels := TStubListItems.Create;
end;

destructor TStubStatusBar.Destroy;
begin
  Font.Free;
  Panels.Free;
  inherited;
end;

procedure TMainForm.SetDefaultRunMode(const Value); begin end;
procedure TMainForm.UpdSerialNR(const Value); begin end;
procedure TMainForm.UpdSerialNRCustomRange(const Value); begin end;
procedure TMainForm.UpdCWMinRxSpeed(const Value); begin end;
procedure TMainForm.UpdCWMaxRxSpeed(const Value); begin end;
procedure TMainForm.SetMyCall(const Value); begin end;
procedure TMainForm.SetPitch(const Value); begin end;
procedure TMainForm.SetBw(const Value); begin end;
procedure TMainForm.SetMySerialNR; begin end;
procedure TMainForm.ReadCheckBoxes; begin end;
procedure TMainForm.PostHiScore(const Value); begin end;
procedure TMainForm.SetContest(const Value); begin end;
procedure TMainForm.SetWpm(const Value); begin end;
procedure TMainForm.Operator1Click; begin end;
procedure TMainForm.CWOPSNumberClick; begin end;

initialization
  MainForm := TMainForm.Create;

finalization
  MainForm.Free;

end.
