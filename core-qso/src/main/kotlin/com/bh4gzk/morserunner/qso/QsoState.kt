package com.bh4gzk.morserunner.qso

/**
 * The steps of one contact, in upstream's terms (spec §2 "QSO state").
 *
 * **Not** a station's audio state (`Station.pas:26-36`: `stListening`, `stCopying`,
 * `stPreparingToSend`, `stSending`) — that describes one simulated operator's pipeline, while
 * this describes what *I* am doing. Confusing the two is how logging bugs start, which is why
 * CONTEXT.md lists both senses.
 *
 * `QsoMachine` drives the transitions; upstream keeps no such enum (its state is implicit in
 * `Main.pas`'s `CallSent`/`NrSent` flags), so the mapping was chosen in T6 and is documented on
 * the machine.
 */
enum class QsoState {
    /** Nothing in progress: no CQ, no station being worked. */
    Idle,

    /** CQ is queued or being sent. */
    CallingCq,

    /** CQ is out; listening for a caller. */
    WaitingForCall,

    /** My turn to send: his call and my exchange. */
    SendingExchange,

    /** My exchange is out; waiting for his. */
    WaitingForExchange,

    /** The contact was handed to the log. */
    Logged,
}
