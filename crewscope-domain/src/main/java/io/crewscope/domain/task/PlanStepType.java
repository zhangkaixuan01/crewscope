package io.crewscope.domain.task;

/** Controlled durable step types accepted from a validated plan candidate. */
public enum PlanStepType {
    ANALYSIS,
    IMPLEMENTATION,
    VALIDATION,
    REVIEW,
    DELIVERY
}
