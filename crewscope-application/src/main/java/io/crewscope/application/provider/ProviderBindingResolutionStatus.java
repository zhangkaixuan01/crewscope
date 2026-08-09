package io.crewscope.application.provider;

/** Closed result shape; execution consumers only accept RESOLVED. */
public enum ProviderBindingResolutionStatus {
    RESOLVED,
    NOT_FOUND,
    AMBIGUOUS
}
