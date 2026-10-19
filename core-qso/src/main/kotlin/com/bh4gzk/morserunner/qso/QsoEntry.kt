package com.bh4gzk.morserunner.qso

/**
 * What the operator has typed into the two entry boxes.
 *
 * Spec §8 puts a callsign box and the exchange boxes on screen; the schema decides how many
 * *fields* the exchange has and how they are typed back together (`exchange.entryParse`), so
 * this type stays a pair of strings and [ContestRules] does the parsing.
 */
data class QsoEntry(
    val call: String = "",
    val exch1: String = "",
    val exch2: String = "",
) {
    val isEmpty: Boolean
        get() = call.isBlank() && exch1.isBlank() && exch2.isBlank()
}

/**
 * What pressing `Enter` did.
 *
 * The four parts are the four observable effects of upstream's `ProcessEnter`
 * (`Main.pas:952-1093`): messages went into the queue, the contact may have to be logged now,
 * the operator may need an error shown, and the cursor may advance to the next field.
 *
 * @param sentText the rendered CW text of [messages], in queue order — what the audio layer
 *   will actually transmit. Kept here so a caller never has to re-render to know.
 * @param logged true when the contact must be handed to the log now; upstream sends `TU` and
 *   calls `Log.SaveQso` in the same branch.
 * @param errors contest validation messages, ready to show next to the entry.
 * @param advance upstream's `MustAdvance`: move the cursor to the next exchange field.
 */
data class EnterOutcome(
    val messages: List<QsoMessage> = emptyList(),
    val sentText: List<String> = emptyList(),
    val logged: Boolean = false,
    val errors: List<String> = emptyList(),
    val advance: Boolean = false,
)
