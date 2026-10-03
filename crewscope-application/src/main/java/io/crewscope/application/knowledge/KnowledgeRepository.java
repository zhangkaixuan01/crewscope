package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for Team Knowledge entries and their immutable versions. Every read
 * is scoped by the full (organization, Team) coordinate; the PostgreSQL adapter is
 * A02's delivery, and these signatures are its acceptance contract.
 */
public interface KnowledgeRepository {

    /** Inserts a new DRAFT entry; a duplicate (organization, Team, entryKey) is a conflict. */
    KnowledgeEntry create(KnowledgeEntry entry);

    /**
     * Atomically persists the optimistic-locked head and, when present, the version row
     * appended by the same publish. Saving a head whose version no longer matches the
     * committed one must fail with {@link OptimisticLockConflictException}.
     */
    KnowledgeEntry save(KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion);

    Optional<KnowledgeEntry> findById(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId);

    Optional<KnowledgeEntry> findByKey(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey);

    /** One keyset page of the Team listing ordered by entry key ascending. */
    KnowledgeEntryPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryFilter filter,
            KnowledgeEntryPageRequest pageRequest);

    Optional<KnowledgeEntryVersion> findVersion(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision);

    /** One keyset page of the entry's version history, oldest revision first. */
    KnowledgeEntryVersionPage findVersionHistory(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeVersionPageRequest pageRequest);

    /**
     * The authoritative effective-version gate: resolves the head's effective pointer and
     * returns empty unless the head is PUBLISHED. Drafts and retired or deleted entries
     * never resolve (ADR-030 §2). This is the only retrieval entry point.
     */
    Optional<KnowledgeEntryVersion> findEffectiveVersion(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId);

    /** Effective versions of every retrievable entry of one Team; I01's rebuild seed. */
    List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
            OrganizationId organizationId, TeamId teamId);
}
