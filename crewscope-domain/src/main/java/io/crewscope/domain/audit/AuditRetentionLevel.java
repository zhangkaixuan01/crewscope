package io.crewscope.domain.audit;

/** Policy label retained on an Audit fact; physical retention is implemented outside M6-D06. */
public enum AuditRetentionLevel {
    STANDARD,
    EXTENDED,
    LEGAL_HOLD
}
