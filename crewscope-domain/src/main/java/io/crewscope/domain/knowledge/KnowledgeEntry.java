package io.crewscope.domain.knowledge;

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
 * Head of one Team Knowledge entry: optimistic-locked state, the authoritative
 * effective-version pointer and the mutable draft. Publishing appends an immutable
 * {@link KnowledgeEntryVersion} and atomically moves this head's pointer, so
 * "saved" and "retrievable" are two distinct states (ADR-030 §2). Draft content is
 * never retrievable; a DELETED tombstone can never be published again.
 */
public final class KnowledgeEntry {

    /**
     * Source-to-target status transitions. A DELETED tombstone is terminal; a RETIRED
     * entry may be revived only by publishing a new revision. Kept as a declarative
     * map so the generated state-machine catalogue can publish this lifecycle.
     */
    private static final Map<KnowledgeEntryStatus, Set<KnowledgeEntryStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    KnowledgeEntryStatus.DRAFT,
                    Set.of(KnowledgeEntryStatus.PUBLISHED, KnowledgeEntryStatus.DELETED),
                    KnowledgeEntryStatus.PUBLISHED,
                    Set.of(
                            KnowledgeEntryStatus.PUBLISHED,
                            KnowledgeEntryStatus.RETIRED,
                            KnowledgeEntryStatus.DELETED),
                    KnowledgeEntryStatus.RETIRED,
                    Set.of(KnowledgeEntryStatus.PUBLISHED, KnowledgeEntryStatus.DELETED),
                    KnowledgeEntryStatus.DELETED,
                    Set.of());

    private final KnowledgeEntryId id;
    private final TeamScope scope;
    private final KnowledgeEntryKey entryKey;
    private final KnowledgeCategory category;
    private final Optional<KnowledgeEntryOrigin> origin;
    private final KnowledgeEntryStatus status;
    private final Optional<KnowledgeEntryRevision> effectiveRevision;
    private final long latestRevision;
    private final Optional<KnowledgeDraft> draft;
    private final long version;
    private final AuditMetadata audit;

    private KnowledgeEntry(
            KnowledgeEntryId id,
            TeamScope scope,
            KnowledgeEntryKey entryKey,
            KnowledgeCategory category,
            Optional<KnowledgeEntryOrigin> origin,
            KnowledgeEntryStatus status,
            Optional<KnowledgeEntryRevision> effectiveRevision,
            long latestRevision,
            Optional<KnowledgeDraft> draft,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.entryKey = Objects.requireNonNull(entryKey, "entryKey");
        this.category = Objects.requireNonNull(category, "category");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.status = Objects.requireNonNull(status, "status");
        this.effectiveRevision = Objects.requireNonNull(effectiveRevision, "effectiveRevision");
        if (latestRevision < 0) {
            throw new IllegalArgumentException("latestRevision must not be negative");
        }
        this.latestRevision = latestRevision;
        this.draft = Objects.requireNonNull(draft, "draft");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        requireShape();
    }

    /** Creates a new DRAFT entry; drafts are not retrievable until published. */
    public static KnowledgeEntry create(
            TeamScope scope,
            KnowledgeEntryKey entryKey,
            KnowledgeCategory category,
            String title,
            String content,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return createDraft(
                scope, entryKey, category, title, content, Optional.empty(), actor, occurredAt);
    }

    /**
     * Creates a DRAFT entry distilled from one completed Task execution attempt; the
     * origin is immutable attribution that survives publishing, retiring and deletion,
     * and marks the entry for the command-level disclosure check before publication.
     */
    public static KnowledgeEntry createDistilled(
            TeamScope scope,
            KnowledgeEntryKey entryKey,
            KnowledgeCategory category,
            String title,
            String content,
            KnowledgeEntryOrigin origin,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return createDraft(
                scope, entryKey, category, title, content,
                Optional.of(Objects.requireNonNull(origin, "origin")), actor, occurredAt);
    }

    private static KnowledgeEntry createDraft(
            TeamScope scope,
            KnowledgeEntryKey entryKey,
            KnowledgeCategory category,
            String title,
            String content,
            Optional<KnowledgeEntryOrigin> origin,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new KnowledgeEntry(
                KnowledgeEntryId.generate(),
                scope,
                entryKey,
                category,
                origin,
                KnowledgeEntryStatus.DRAFT,
                Optional.empty(),
                0L,
                Optional.of(new KnowledgeDraft(title, content)),
                0L,
                AuditMetadata.createdBy(
                        Objects.requireNonNull(actor, "actor"),
                        Objects.requireNonNull(occurredAt, "occurredAt")));
    }

    /** Restores a persisted head; validates the shape invariants enforced by V52/V53/V54. */
    public static KnowledgeEntry reconstitute(
            KnowledgeEntryId id,
            TeamScope scope,
            KnowledgeEntryKey entryKey,
            KnowledgeCategory category,
            Optional<KnowledgeEntryOrigin> origin,
            KnowledgeEntryStatus status,
            Optional<KnowledgeEntryRevision> effectiveRevision,
            long latestRevision,
            Optional<KnowledgeDraft> draft,
            long version,
            AuditMetadata audit) {
        return new KnowledgeEntry(
                id, scope, entryKey, category, origin, status, effectiveRevision, latestRevision,
                draft, version, audit);
    }

    /**
     * Publishes the current draft as the next immutable revision and moves the effective
     * pointer. Allowed from DRAFT, PUBLISHED and RETIRED (a retired entry may be revived
     * by publishing a new revision); a DELETED tombstone can never be published again.
     */
    public KnowledgeEntryPublication publish(PrincipalId actor, UtcTimestamp occurredAt) {
        requireTransition(KnowledgeEntryStatus.PUBLISHED);
        KnowledgeDraft currentDraft = draft.orElseThrow(() -> new DomainValidationException(
                "knowledgeEntry.draft", "must be present to publish"));
        KnowledgeEntryRevision revision = new KnowledgeEntryRevision(latestRevision + 1);
        KnowledgeEntryVersion appended = KnowledgeEntryVersion.create(
                id, scope, revision, previousRevision(),
                currentDraft.title(), currentDraft.content(), actor, occurredAt);
        KnowledgeEntry updated = new KnowledgeEntry(
                id, scope, entryKey, category, origin, KnowledgeEntryStatus.PUBLISHED,
                Optional.of(revision), revision.value(), Optional.empty(),
                version + 1, audit.modifiedBy(actor, occurredAt));
        return new KnowledgeEntryPublication(updated, appended);
    }

    /** Retires the current effective version; the last effective revision is retained. */
    public KnowledgeEntry retire(PrincipalId actor, UtcTimestamp occurredAt) {
        requireTransition(KnowledgeEntryStatus.RETIRED);
        return new KnowledgeEntry(
                id, scope, entryKey, category, origin, KnowledgeEntryStatus.RETIRED,
                effectiveRevision, latestRevision, draft, version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    /**
     * Deletes the entry as an irreversible tombstone. The head and its versions stay
     * persisted for source attribution but are never retrievable or publishable again.
     */
    public KnowledgeEntry delete(PrincipalId actor, UtcTimestamp occurredAt) {
        requireTransition(KnowledgeEntryStatus.DELETED);
        return new KnowledgeEntry(
                id, scope, entryKey, category, origin, KnowledgeEntryStatus.DELETED,
                effectiveRevision, latestRevision, draft, version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    /**
     * Replaces the mutable draft and optionally reclassifies the entry; never touches
     * the effective pointer and never mints a version row.
     */
    public KnowledgeEntry updateDraft(
            String title,
            String content,
            Optional<KnowledgeCategory> category,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (status == KnowledgeEntryStatus.DELETED) {
            throw new InvalidStateTransitionException(
                    "KnowledgeEntry", id, status, KnowledgeEntryStatus.DRAFT);
        }
        return new KnowledgeEntry(
                id, scope, entryKey, category.orElse(this.category), origin, status,
                effectiveRevision, latestRevision,
                Optional.of(new KnowledgeDraft(title, content)), version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    /** Authoritative retrieval gate: only PUBLISHED entries with an effective revision. */
    public boolean effectivelyPublished() {
        return status == KnowledgeEntryStatus.PUBLISHED && effectiveRevision.isPresent();
    }

    /** Last effective revision retained for attribution; empty when never published. */
    public Optional<KnowledgeEntryRevision> lastEffectiveRevision() {
        return effectiveRevision;
    }

    public KnowledgeEntryId id() {
        return id;
    }

    public TeamScope scope() {
        return scope;
    }

    public KnowledgeEntryKey entryKey() {
        return entryKey;
    }

    public KnowledgeCategory category() {
        return category;
    }

    /** Immutable distillation attribution; empty for manually authored entries. */
    public Optional<KnowledgeEntryOrigin> origin() {
        return origin;
    }

    public KnowledgeEntryStatus status() {
        return status;
    }

    public Optional<KnowledgeEntryRevision> effectiveRevision() {
        return effectiveRevision;
    }

    public long latestRevision() {
        return latestRevision;
    }

    public Optional<KnowledgeDraft> draft() {
        return draft;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private Optional<KnowledgeEntryRevision> previousRevision() {
        return latestRevision == 0
                ? Optional.empty()
                : Optional.of(new KnowledgeEntryRevision(latestRevision));
    }

    private void requireTransition(KnowledgeEntryStatus target) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new InvalidStateTransitionException("KnowledgeEntry", id, status, target);
        }
    }

    private void requireShape() {
        if (effectiveRevision.isPresent()
                && (latestRevision == 0 || effectiveRevision.get().value() > latestRevision)) {
            throw new DomainValidationException(
                    "knowledgeEntry.effectiveRevision",
                    "must not exceed latestRevision (%d)".formatted(latestRevision));
        }
        if (status == KnowledgeEntryStatus.DRAFT && effectiveRevision.isPresent()) {
            throw new DomainValidationException(
                    "knowledgeEntry.effectiveRevision",
                    "a DRAFT entry must not carry an effective revision");
        }
        if (status == KnowledgeEntryStatus.PUBLISHED && effectiveRevision.isEmpty()) {
            throw new DomainValidationException(
                    "knowledgeEntry.effectiveRevision",
                    "a PUBLISHED entry must carry an effective revision");
        }
    }
}
