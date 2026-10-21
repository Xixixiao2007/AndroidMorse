unit AudioFeed;
{ The game loop runs on a thread the RTL started.

  Why: `TContest.GetAudio` *is* the simulation (it filters, modulates, ticks every station and
  appends finished QSOs), and it used to run on the caller's thread — i.e. on a Java thread created
  by `AudioTrack`. FPC's exception machinery needs the thread state the RTL sets up when *it* starts
  a thread; on a foreign thread a raise dies inside the unwinder (measured: SIGSEGV in
  `fpc_popaddrstack`, or an RTL `exit 255` with no hook firing), and no `try..except` anywhere on
  that thread can catch it. That made every engine bug fatal on the phone.

  So the loop moved here and `mr_block` is a pure consumer of the blocks this thread produced.

  What it is deliberately NOT built from: `TThread`, `TSimpleEvent`, `RTLEvent` and every other FPC
  synchronisation object. On this target each one raised the first time it was created from a JNI
  frame (measured: `exit 217` at library load with an object in a unit's initialization, then
  SIGSEGV in `SYSTEM_$$_GET_CALLER_ADDR` when the probe called `mr_run_start` and `TThread.Create`
  ran). The producer is therefore a bare `BeginThread`, stopped by a flag nobody waits on (only
  polled), and the ring is guarded by the one primitive already proven here — a
  `TCriticalSection`, the same object the ABI has used since the beginning.

  Pacing is the ring: when every slot is full the producer sleeps and retries, so the simulation
  advances at the rate the device plays. A pull that finds the ring empty sleeps briefly and then
  returns 0 — "no block this call", never "end of run". }

{$mode delphi}{$H+}

interface

uses
  SyncObjs;

const
  { 3 blocks = ~0.14 s. This is a LATENCY knob, not a safety margin: everything sitting in the
    ring has already been generated, so whatever is buffered is heard after the key is pressed.
    A deep ring (8, then 16 while chasing the choppy audio) made every F-key feel sluggish —
    "press CQ, hear it a moment later". Keep it as short as the device tolerates. }
  FEED_SLOTS = 3;

{ Start the producer. Idempotent; the lock passed in is the ABI's engine lock, which the producer
  takes around `Tst.GetAudio` so the other entry points stay serialised against it. }
procedure StartFeed(ALock: TCriticalSection);

{ Stop the producer and poll (bounded) until its thread has left. }
procedure StopFeed;

{ Drop everything buffered. Called when the operator sends: the audio for the new message is
  generated now, and any older blocks in the ring would be heard first — that is the delay between
  pressing a button and hearing the first tone. }
procedure Flush;

{ Copy the oldest produced block into `OutBuf` (at most `Cap` samples). Returns the sample count,
  or 0 when nothing is ready — see the unit comment. }
function Pull(OutBuf: PSingle; Cap: Integer): Integer;

implementation

uses
  SysUtils, SndTypes, Contest, Trace;

var
  FeedLock: TCriticalSection;   // the ABI's engine lock, set by StartFeed
  RingLock: TCriticalSection;
  Slots: array[0..FEED_SLOTS - 1] of TSingleArray;
  SlotLen: array[0..FEED_SLOTS - 1] of Integer;
  Head, Tail, Count: Integer;
  { A block bigger than the caller's buffer is split, not truncated: the tail of the previous slot
    is served first on the next call. }
  Carry: TSingleArray;
  CarryLen: Integer;

  StopFlag: Boolean = False;   { shutdown: the worker leaves for good }
  Running: Boolean = False;    { armed by StartFeed, cleared by StopFeed }
  WorkerCreated: Boolean = False;
  WorkerThreadID: TThreadID;

{ The producer. A plain procedure on purpose: no object, no event, nothing that can raise on the way
  in. `Tst.GetAudio` runs under the engine lock exactly as the ABI's own calls do. }
function FeedProc(Param: Pointer): PtrInt;
var
  Block: TSingleArray;
  N: Integer;
begin
  Result := 0;
  TraceLine('feed: thread up');
  while not StopFlag do
  begin
    if not Running then
    begin
      Sleep(5);
      Continue;
    end;

    Block := nil;
    try
      if FeedLock <> nil then
        FeedLock.Enter;
      try
        if Tst <> nil then
          Block := Tst.GetAudio;
      finally
        if FeedLock <> nil then
          FeedLock.Leave;
      end;
    except
      on E: Exception do
      begin
        { On this thread the exception unwinds into this handler instead of ending the process:
          that is the entire point of the move. Skip the block and record why. }
        TraceLine('audio: ' + E.ClassName + ': ' + E.Message);
        Sleep(5);
        Continue;
      end;
    end;

    N := Length(Block);
    if N <= 0 then
    begin
      Sleep(1);
      Continue;
    end;

    { Wait for a free slot; bounded sleeps so StopFlag is noticed within a few ms. }
    while (not StopFlag) and (Count >= FEED_SLOTS) do
      Sleep(5);
    if StopFlag then
      Break;

    RingLock.Enter;
    try
      if Length(Slots[Tail]) < N then
        SetLength(Slots[Tail], N);
      Move(Block[0], Slots[Tail][0], N * SizeOf(Single));
      SlotLen[Tail] := N;
      Tail := (Tail + 1) mod FEED_SLOTS;
      Inc(Count);
    finally
      RingLock.Leave;
    end;
  end;
end;

{ Created from the library's initialization, never from a JNI call: creating a thread from a JNI
  frame is what killed the process in every earlier attempt (see the unit comment). At load the RTL
  context is the one it expects, and TCriticalSection.Create already works there. }
procedure EnsureInit; forward;

procedure StartWorker;
begin
  if WorkerCreated then
    Exit;
  EnsureInit;
  WorkerCreated := True;
  BeginThread(@FeedProc, nil, WorkerThreadID);
end;

procedure EnsureInit;
begin
  { `IsMultiThread` is NOT set here: it is set once in the library's initialization, next to
    EngineLock, because doing it from this call raised (measured: StartFeed entered, never reached
    the next trace marker). }
  if RingLock <> nil then
    Exit;
  TraceLine('feed: creating ring lock');
  try
    RingLock := TCriticalSection.Create;
    TraceLine('feed: ring lock ready');
  except
    on E: Exception do
      TraceLine('feed: lock create raised ' + E.ClassName + ': ' + E.Message);
  end;
end;

procedure StartFeed(ALock: TCriticalSection);
begin
  EnsureInit;
  FeedLock := ALock;
  if Running then
    Exit;
  RingLock.Enter;
  try
    Head := 0;
    Tail := 0;
    Count := 0;
    CarryLen := 0;
  finally
    RingLock.Leave;
  end;
  StopFlag := False;
  Running := True;
  TraceLine('run: feed armed');
end;

procedure Flush;
begin
  if RingLock = nil then
    Exit;
  RingLock.Enter;
  try
    Head := 0;
    Tail := 0;
    Count := 0;
    CarryLen := 0;
  finally
    RingLock.Leave;
  end;
end;

procedure StopFeed;
var
  i: Integer;
begin
  Running := False;
  if RingLock = nil then
    Exit;
  { The producer's longest sleep is 5 ms; it checks Running every iteration, so once the ring stops
    growing it has been observed idle. Nothing waits on the thread itself. }
  for i := 1 to 20 do
    Sleep(2);
end;

function Pull(OutBuf: PSingle; Cap: Integer): Integer;
var
  N: Integer;
  Tries: Integer;
begin
  Result := 0;
  if (OutBuf = nil) or (Cap <= 0) or (RingLock = nil) then
    Exit;
  { Wait for audio: about 80 ms, then give up and let the caller decide. The audio thread calls
    this in a loop, so returning 0 immediately means a broken-up stream — that is what happened
    with the first version (2 x 2 ms). }
  Tries := 0;
  while Tries < 40 do
  begin
    RingLock.Enter;
    try
      if CarryLen > 0 then
      begin
        N := CarryLen;
        if N > Cap then
          N := Cap;
        Move(Carry[0], OutBuf^, N * SizeOf(Single));
        if N < CarryLen then
        begin
          Move(Carry[N], Carry[0], (CarryLen - N) * SizeOf(Single));
          Dec(CarryLen, N);
        end
        else
          CarryLen := 0;
        Result := N;
        Exit;
      end;
      if Count > 0 then
      begin
        N := SlotLen[Head];
        if N > Cap then
        begin
          { The rest waits in Carry for the next call instead of being dropped mid-block. }
          SetLength(Carry, N - Cap);
          Move(Slots[Head][Cap], Carry[0], (N - Cap) * SizeOf(Single));
          CarryLen := N - Cap;
          N := Cap;
        end;
        Move(Slots[Head][0], OutBuf^, N * SizeOf(Single));
        Head := (Head + 1) mod FEED_SLOTS;
        Dec(Count);
        Result := N;
        Exit;
      end;
    finally
      RingLock.Leave;
    end;
    { Empty: give the producer a moment, but never block the audio device for long. }
    Sleep(2);
    Inc(Tries);
  end;
end;

initialization
  { The worker is created here, while the RTL is in the context it expects — see StartWorker. The
    ring lock is created here too; both only build objects and start a thread that idles until
    StartFeed arms it. Nothing here touches the engine. }
  StartWorker;

finalization
  if RingLock <> nil then
  begin
    StopFeed;
    RingLock.Free;
  end;

end.
