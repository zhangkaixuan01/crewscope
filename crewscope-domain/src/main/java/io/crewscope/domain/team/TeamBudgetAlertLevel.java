package io.crewscope.domain.team;

/** The softness of a budget alert: a reminder, never a quota (M10-F03). */
public enum TeamBudgetAlertLevel {

    /** Usage crossed the warning ratio of the budget. */
    WARNING,

    /** Usage reached or passed the budget itself. */
    EXCEEDED
}
