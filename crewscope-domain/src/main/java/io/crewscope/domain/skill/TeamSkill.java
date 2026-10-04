package io.crewscope.domain.skill;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Head of one Team Skill catalog entry: optimistic-locked state, the effective-version
 * pointer later executions authorize against, and the mutable draft. Publishing appends
 * an immutable {@link TeamSkillVersion} and atomically moves the pointer (ADR-031 §1).
 * There is no tombstone — disabling keeps the last effective revision as historical
 * evidence, and a disabled skill is revived by publishing a new revision. A rollback
 * activates historical content as a new revision; history itself never changes.
 */
public final class TeamSkill {

    /**
     * Source-to-target status transitions. Any non-DRAFT head may publish a new revision;
     * disabling is reachable from DRAFT (scrapping a draft) and PUBLISHED (retiring a
     * live skill); DISABLED heads may only come back through a new revision. Kept as a
     * declarative map so the generated state-machine catalogue can publish this lifecycle.
     */
    private static final Map<TeamSkillStatus, Set<TeamSkillStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    TeamSkillStatus.DRAFT,
                    Set.of(TeamSkillStatus.PUBLISHED, TeamSkillStatus.DISABLED),
                    TeamSkillStatus.PUBLISHED,
                    Set.of(TeamSkillStatus.PUBLISHED, TeamSkillStatus.DISABLED),
                    TeamSkillStatus.DISABLED,
                    Set.of(TeamSkillStatus.PUBLISHED));

    public static final int MAX_DISABLE_REASON_LENGTH = 200;

    private final TeamSkillId id;
    private final TeamScope scope;
    private final TeamSkillKey skillKey;
    private final Optional<TeamSkillOrigin> origin;
    private final TeamSkillStatus status;
    private final Optional<TeamSkillRevision> effectiveRevision;
    private final long latestRevision;
    private final Optional<TeamSkillDraft> draft;
    private final Optional<String> disableReason;
    private final long version;
    private final AuditMetadata audit;

    private TeamSkill(
            TeamSkillId id,
            TeamScope scope,
            TeamSkillKey skillKey,
            Optional<TeamSkillOrigin> origin,
            TeamSkillStatus status,
            Optional<TeamSkillRevision> effectiveRevision,
            long latestRevision,
            Optional<TeamSkillDraft> draft,
            Optional<String> disableReason,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.skillKey = Objects.requireNonNull(skillKey, "skillKey");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.status = Objects.requireNonNull(status, "status");
        this.effectiveRevision = Objects.requireNonNull(effectiveRevision, "effectiveRevision");
        if (latestRevision < 0) {
            throw new IllegalArgumentException("latestRevision must not be negative");
        }
        this.latestRevision = latestRevision;
        this.draft = Objects.requireNonNull(draft, "draft");
        this.disableReason = Objects.requireNonNull(disableReason, "disableReason");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        requireShape();
        requireDraftNameMatchesKey();
    }

    /**
     * Creates a new DRAFT catalog entry. Manually authored entries (A03a) carry no
     * origin; the A03b distillation entry point creates the same DRAFT with the selected
     * execution attached.
     */
    public static TeamSkill create(
            TeamScope scope,
            TeamSkillKey skillKey,
            String content,
            Optional<TeamSkillOrigin> origin,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new TeamSkill(
                TeamSkillId.generate(),
                scope,
                skillKey,
                origin,
                TeamSkillStatus.DRAFT,
                Optional.empty(),
                0L,
                Optional.of(new TeamSkillDraft(content)),
                Optional.empty(),
                0L,
                AuditMetadata.createdBy(
                        Objects.requireNonNull(actor, "actor"),
                        Objects.requireNonNull(occurredAt, "occurredAt")));
    }

    /** Restores a persisted head; validates the shape invariants enforced by V61. */
    public static TeamSkill reconstitute(
            TeamSkillId id,
            TeamScope scope,
            TeamSkillKey skillKey,
            Optional<TeamSkillOrigin> origin,
            TeamSkillStatus status,
            Optional<TeamSkillRevision> effectiveRevision,
            long latestRevision,
            Optional<TeamSkillDraft> draft,
            Optional<String> disableReason,
            long version,
            AuditMetadata audit) {
        return new TeamSkill(
                id, scope, skillKey, origin, status, effectiveRevision, latestRevision,
                draft, disableReason, version, audit);
    }

    /**
     * Publishes the current draft as the next immutable revision and moves the effective
     * pointer. Allowed from DRAFT, PUBLISHED and DISABLED (a disabled skill is revived by
     * publishing a new revision); the draft is consumed.
     */
    public TeamSkillPublication publish(PrincipalId actor, UtcTimestamp occurredAt) {
        requireTransition(TeamSkillStatus.PUBLISHED);
        TeamSkillDraft currentDraft = draft.orElseThrow(() -> new DomainValidationException(
                "teamSkill.draft", "must be present to publish"));
        TeamSkillRevision revision = new TeamSkillRevision(latestRevision + 1);
        TeamSkillVersion appended = TeamSkillVersion.create(
                id, scope, revision, previousRevision(), currentDraft.content(),
                actor, occurredAt);
        TeamSkill updated = new TeamSkill(
                id, scope, skillKey, origin, TeamSkillStatus.PUBLISHED,
                Optional.of(revision), revision.value(), Optional.empty(),
                Optional.empty(), version + 1, audit.modifiedBy(actor, occurredAt));
        return new TeamSkillPublication(updated, appended);
    }

    /**
     * Deactivates the skill: the head keeps its last effective revision and draft as
     * historical evidence, and later executions must not load it. Reachable from DRAFT
     * (scrapping a draft) and PUBLISHED (retiring a live skill).
     */
    public TeamSkill disable(String reason, PrincipalId actor, UtcTimestamp occurredAt) {
        requireTransition(TeamSkillStatus.DISABLED);
        return new TeamSkill(
                id, scope, skillKey, origin, TeamSkillStatus.DISABLED,
                effectiveRevision, latestRevision, draft,
                Optional.ofNullable(requireReason(reason)), version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    /**
     * Activates historical content as a new revision: the appended version carries the
     * target's document verbatim (its digest equals the target's — that equality is the
     * proof this really is the historical content), the head moves forward, and history
     * itself never changes (ADR-031 §4). Rolling back onto the currently effective
     * revision is a no-op and refused.
     */
    public TeamSkillPublication rollback(
            TeamSkillVersion target, PrincipalId actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!target.skillId().equals(id) || !target.scope().equals(scope)) {
            throw new DomainValidationException(
                    "teamSkill.rollback", "target version belongs to another aggregate");
        }
        requireTransition(TeamSkillStatus.PUBLISHED);
        if (effectiveRevision.isPresent()
                && effectiveRevision.get().value() == target.revision().value()) {
            throw new TeamSkillVersionUnchangedException(id, target.revision().value());
        }
        TeamSkillRevision revision = new TeamSkillRevision(latestRevision + 1);
        TeamSkillVersion appended = TeamSkillVersion.create(
                id, scope, revision, previousRevision(), target.content(), actor, occurredAt);
        TeamSkill updated = new TeamSkill(
                id, scope, skillKey, origin, TeamSkillStatus.PUBLISHED,
                Optional.of(revision), revision.value(), Optional.empty(),
                Optional.empty(), version + 1, audit.modifiedBy(actor, occurredAt));
        return new TeamSkillPublication(updated, appended);
    }

    /**
     * Replaces the mutable draft; never touches the effective pointer and never mints a
     * version row. Allowed in every status — a disabled skill may re-draft ahead of a
     * reviving publish.
     */
    public TeamSkill updateDraft(
            String content, PrincipalId actor, UtcTimestamp occurredAt) {
        return new TeamSkill(
                id, scope, skillKey, origin, status,
                effectiveRevision, latestRevision,
                Optional.of(new TeamSkillDraft(content)), disableReason, version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    /** Authorization gate for later executions: only PUBLISHED heads load. */
    public boolean effectivelyPublished() {
        return status == TeamSkillStatus.PUBLISHED && effectiveRevision.isPresent();
    }

    /** Last effective revision retained as evidence; empty when never published. */
    public Optional<TeamSkillRevision> lastEffectiveRevision() {
        return effectiveRevision;
    }

    public TeamSkillId id() {
        return id;
    }

    public TeamScope scope() {
        return scope;
    }

    public TeamSkillKey skillKey() {
        return skillKey;
    }

    /** Immutable distillation attribution; empty for manually authored entries. */
    public Optional<TeamSkillOrigin> origin() {
        return origin;
    }

    public TeamSkillStatus status() {
        return status;
    }

    public Optional<TeamSkillRevision> effectiveRevision() {
        return effectiveRevision;
    }

    public long latestRevision() {
        return latestRevision;
    }

    public Optional<TeamSkillDraft> draft() {
        return draft;
    }

    /** Operator-facing reason shown by the F02 catalog; present only on DISABLED heads. */
    public Optional<String> disableReason() {
        return disableReason;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private Optional<TeamSkillRevision> previousRevision() {
        return latestRevision == 0
                ? Optional.empty()
                : Optional.of(new TeamSkillRevision(latestRevision));
    }

    private void requireTransition(TeamSkillStatus target) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new InvalidStateTransitionException("TeamSkill", id, status, target);
        }
    }

    private void requireShape() {
        if (effectiveRevision.isPresent()
                && (latestRevision == 0 || effectiveRevision.get().value() > latestRevision)) {
            throw new DomainValidationException(
                    "teamSkill.effectiveRevision",
                    "must not exceed latestRevision (%d)".formatted(latestRevision));
        }
        if (status == TeamSkillStatus.DRAFT && effectiveRevision.isPresent()) {
            throw new DomainValidationException(
                    "teamSkill.effectiveRevision",
                    "a DRAFT skill must not carry an effective revision");
        }
        if (status == TeamSkillStatus.PUBLISHED && effectiveRevision.isEmpty()) {
            throw new DomainValidationException(
                    "teamSkill.effectiveRevision",
                    "a PUBLISHED skill must carry an effective revision");
        }
        if (disableReason.isPresent() && status != TeamSkillStatus.DISABLED) {
            throw new DomainValidationException(
                    "teamSkill.disableReason",
                    "is only carried by a DISABLED skill");
        }
    }

    /** The SKILL.md frontmatter name is the catalog identity — it may not drift from it. */
    private void requireDraftNameMatchesKey() {
        draft.ifPresent(currentDraft -> {
            if (!currentDraft.name().equals(skillKey.value())) {
                throw new DomainValidationException(
                        "teamSkill.draft",
                        "frontmatter name must equal the skill key '" + skillKey.value() + "'");
            }
        });
    }

    private static String requireReason(String reason) {
        if (reason == null) {
            return null;
        }
        String stripped = reason.strip();
        if (stripped.length() > MAX_DISABLE_REASON_LENGTH) {
            throw new DomainValidationException(
                    "teamSkill.disableReason",
                    "must contain at most " + MAX_DISABLE_REASON_LENGTH + " characters");
        }
        return stripped.isEmpty() ? null : stripped;
    }
}
