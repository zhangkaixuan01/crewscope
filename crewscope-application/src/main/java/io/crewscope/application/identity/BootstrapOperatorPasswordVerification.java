package io.crewscope.application.identity;

/** Secret-safe comparison result for an existing Bootstrap Operator credential. */
public enum BootstrapOperatorPasswordVerification {
    MATCHED,
    MATCHED_REHASH_REQUIRED,
    MISMATCHED
}
