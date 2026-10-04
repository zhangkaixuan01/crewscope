package io.crewscope.application.retrieval;

/**
 * The closed set of reference feedback semantics. S01 §3.7 froze exactly one —
 * "not applicable" — and the freeze forbids implicit global retirement, so any
 * further kind is a contract change, not a silent extension.
 */
public enum InjectionFeedbackKind {
    NOT_APPLICABLE
}
