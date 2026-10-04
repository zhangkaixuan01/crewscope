package io.crewscope.domain.skill;

/**
 * Lifecycle of one Team Skill head. Only {@code PUBLISHED} skills carry an effective
 * version for later executions to authorize against; {@code DISABLED} keeps its last
 * effective revision as historical evidence — there is no tombstone, a disabled skill
 * is revived by publishing a new revision (ADR-031 §4).
 */
public enum TeamSkillStatus {
    DRAFT,
    PUBLISHED,
    DISABLED
}
