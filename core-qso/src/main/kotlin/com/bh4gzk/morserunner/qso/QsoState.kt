package com.bh4gzk.morserunner.qso

/**
 * QSO state machine placeholder.
 *
 * T1 only fixes the vocabulary from the spec (§2); transitions, messages, log and scoring
 * arrive with T6.
 */
enum class QsoState {
    Idle,
    CallingCq,
    WaitingForCall,
    SendingExchange,
    WaitingForExchange,
    Logged
}
