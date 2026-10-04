package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainValidationException;

/** 1-based revision number of one Team Skill version; the chain is strictly contiguous. */
public record TeamSkillRevision(long value) implements Comparable<TeamSkillRevision> {

    public TeamSkillRevision {
        if (value < 1) {
            throw new DomainValidationException("teamSkillRevision", "must be positive");
        }
    }

    public TeamSkillRevision next() {
        return new TeamSkillRevision(value + 1);
    }

    @Override
    public int compareTo(TeamSkillRevision other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
