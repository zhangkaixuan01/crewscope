package io.crewscope.domain.skill;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed Team Skill catalog identifier. */
public record TeamSkillId(UUID value) implements AggregateId {

    public TeamSkillId {
        value = AggregateId.requireValue(value, "TeamSkillId");
    }

    public static TeamSkillId generate() {
        return new TeamSkillId(AggregateId.generateValue());
    }

    public static TeamSkillId from(String value) {
        return new TeamSkillId(AggregateId.parseCanonical(value, "TeamSkillId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
