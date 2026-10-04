package io.crewscope.application.agent;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Set;

/**
 * Caller-proven widening of the immutable template approved-Skill ceiling (M10-A03b).
 * The Coding template's ceiling is static and hash-pinned, so the Team's published
 * Team Skill keys join the ceiling explicitly at the save boundary: the agent layer
 * owns the seam and the skill catalog implements the proof. An absent contributor
 * means the platform runs without the dynamic Skill domain (the A03a state).
 */
@FunctionalInterface
public interface ApprovedSkillCeilingContributor {

    /** The currently published Team Skill keys of one Team's catalog. */
    Set<String> publishedSkillKeys(OrganizationId organizationId, TeamId teamId);
}
