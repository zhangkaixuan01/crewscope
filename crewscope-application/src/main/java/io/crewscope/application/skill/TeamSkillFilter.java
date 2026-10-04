package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkillStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * Read-side filter for the Team skill catalog listing: a status set. Pagination and
 * ordering travel in the page request; other sort keys are deliberately absent from
 * the frozen contract.
 */
public record TeamSkillFilter(Set<TeamSkillStatus> statuses) {

    public TeamSkillFilter {
        if (statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("statuses must not be empty");
        }
        statuses = Set.copyOf(statuses);
    }

    public static TeamSkillFilter all() {
        return new TeamSkillFilter(EnumSet.allOf(TeamSkillStatus.class));
    }

    public static TeamSkillFilter effectivelyPublished() {
        return new TeamSkillFilter(Set.of(TeamSkillStatus.PUBLISHED));
    }
}
