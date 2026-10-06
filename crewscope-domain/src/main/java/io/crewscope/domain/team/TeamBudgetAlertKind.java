package io.crewscope.domain.team;

/** The budget dimension a soft alert is measured in (M10-F03). */
public enum TeamBudgetAlertKind {

    /** Total monthly tokens across every source; never carries a currency. */
    TOKEN,

    /** A monthly amount in exactly one currency; always carries that currency. */
    AMOUNT
}
