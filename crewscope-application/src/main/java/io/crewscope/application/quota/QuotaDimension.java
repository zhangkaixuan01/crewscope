package io.crewscope.application.quota;

/**
 * The two frozen quota dimensions (ADR-037 §2, S01 freeze 3.2). Each dimension picks its
 * arbitration form from the two accepted precedents: the intake dimension arbitrates on the
 * authoritative task rows (ClaimQuota form), the token budget dimension keeps a reservation
 * ledger (workspace write budget form).
 */
public enum QuotaDimension {
    MONTHLY_TASK_INTAKE,
    MONTHLY_TOKEN_BUDGET
}
