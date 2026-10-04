package io.crewscope.domain.skill;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable published version of one Team Skill. Version rows are append-only and carry
 * no lifecycle of their own: whether a revision is currently effective is decided solely
 * by the head pointer. A rollback appends a new revision carrying historical content —
 * the digest stays equal to the historical revision's, which is legal because no unique
 * constraint sits on the digest (ADR-031 §4).
 */
public record TeamSkillVersion(
        TeamSkillId skillId,
        TeamScope scope,
        TeamSkillRevision revision,
        Optional<TeamSkillRevision> previousRevision,
        String content,
        SkillContentHash contentHash,
        AuditMetadata audit) {

    public TeamSkillVersion {
        skillId = Objects.requireNonNull(skillId, "skillId");
        scope = Objects.requireNonNull(scope, "scope");
        revision = Objects.requireNonNull(revision, "revision");
        previousRevision = requirePreviousRevision(revision, previousRevision);
        content = requireContent(content);
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
        if (!contentHash.equals(SkillContentHash.of(content))) {
            throw new DomainValidationException(
                    "teamSkillVersion.contentHash",
                    "must match the canonical digest of the document");
        }
        audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates the immutable fact produced by {@link TeamSkill#publish} and rollback. */
    public static TeamSkillVersion create(
            TeamSkillId skillId,
            TeamScope scope,
            TeamSkillRevision revision,
            Optional<TeamSkillRevision> previousRevision,
            String content,
            PrincipalId publishedBy,
            UtcTimestamp occurredAt) {
        return new TeamSkillVersion(
                skillId,
                scope,
                revision,
                previousRevision,
                content,
                SkillContentHash.of(content),
                AuditMetadata.createdBy(
                        Objects.requireNonNull(publishedBy, "publishedBy"),
                        Objects.requireNonNull(occurredAt, "occurredAt")));
    }

    /** Restores a persisted row; the stored hash must still match the stored document. */
    public static TeamSkillVersion reconstitute(
            TeamSkillId skillId,
            TeamScope scope,
            TeamSkillRevision revision,
            Optional<TeamSkillRevision> previousRevision,
            String content,
            SkillContentHash contentHash,
            AuditMetadata audit) {
        return new TeamSkillVersion(
                skillId, scope, revision, previousRevision, content, contentHash, audit);
    }

    static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new DomainValidationException("teamSkillVersion.content", "must not be blank");
        }
        if (content.length() > TeamSkillDraft.MAX_CONTENT_LENGTH) {
            throw new DomainValidationException(
                    "teamSkillVersion.content",
                    "must contain at most " + TeamSkillDraft.MAX_CONTENT_LENGTH
                            + " characters");
        }
        return content;
    }

    private static Optional<TeamSkillRevision> requirePreviousRevision(
            TeamSkillRevision revision,
            Optional<TeamSkillRevision> previousRevision) {
        Optional<TeamSkillRevision> required =
                Objects.requireNonNull(previousRevision, "previousRevision");
        if (revision.value() == 1) {
            if (required.isPresent()) {
                throw new DomainValidationException(
                        "teamSkillVersion.previousRevision",
                        "must be absent for the first revision");
            }
            return required;
        }
        if (required.isEmpty() || required.get().value() != revision.value() - 1) {
            throw new DomainValidationException(
                    "teamSkillVersion.previousRevision",
                    "must be the immediately preceding revision");
        }
        return required;
    }
}
