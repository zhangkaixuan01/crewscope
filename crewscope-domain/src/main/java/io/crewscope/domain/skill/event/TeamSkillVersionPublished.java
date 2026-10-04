package io.crewscope.domain.skill.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.skill.SkillContentHash;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillVersion;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 business payload emitted after one Team Skill revision is published and
 * becomes the effective version later executions authorize against. A rollback emits
 * the same fact with the source revision it re-activated — history never changes, the
 * head merely moves forward.
 */
public record TeamSkillVersionPublished(
        UUID skillId,
        String skillKey,
        long revision,
        String contentHash,
        Long rolledBackFromRevision)
        implements DomainEvent {

    public TeamSkillVersionPublished {
        skillId = AggregateId.requireValue(
                skillId, "TeamSkillVersionPublished.skillId");
        skillKey = new TeamSkillKey(skillKey).value();
        if (revision < 1) {
            throw new DomainValidationException(
                    "teamSkillVersionPublished.revision", "must be positive");
        }
        contentHash = new SkillContentHash(contentHash).value();
        if (rolledBackFromRevision != null
                && (rolledBackFromRevision < 1 || rolledBackFromRevision == revision)) {
            throw new DomainValidationException(
                    "teamSkillVersionPublished.rolledBackFromRevision",
                    "must be a different, positive revision or null");
        }
    }

    /** Creates the fact from an appended version published from a draft. */
    public static TeamSkillVersionPublished from(
            TeamSkillKey skillKey, TeamSkillVersion version) {
        TeamSkillVersion source = Objects.requireNonNull(version, "version");
        return new TeamSkillVersionPublished(
                source.skillId().value(),
                Objects.requireNonNull(skillKey, "skillKey").value(),
                source.revision().value(),
                source.contentHash().value(),
                null);
    }

    /** Creates the fact from an appended version that re-activated historical content. */
    public static TeamSkillVersionPublished fromRollback(
            TeamSkillKey skillKey, TeamSkillVersion version, long rolledBackFromRevision) {
        TeamSkillVersion source = Objects.requireNonNull(version, "version");
        return new TeamSkillVersionPublished(
                source.skillId().value(),
                Objects.requireNonNull(skillKey, "skillKey").value(),
                source.revision().value(),
                source.contentHash().value(),
                rolledBackFromRevision);
    }
}
