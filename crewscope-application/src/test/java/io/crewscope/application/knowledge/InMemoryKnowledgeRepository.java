package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.knowledge.KnowledgeVersionContentConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Contract-grade in-memory fake: atomic save, optimistic head, tenant scoping, keyset pages. */
class InMemoryKnowledgeRepository implements KnowledgeRepository {

    private final Map<KnowledgeEntryId, KnowledgeEntry> entries = new HashMap<>();
    private final Map<KnowledgeEntryId, List<KnowledgeEntryVersion>> versions = new HashMap<>();

    /** Test hook: the next create() throws once, simulating a commit failure. */
    boolean failNextCreate;

    List<KnowledgeEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    @Override
    public KnowledgeEntry create(KnowledgeEntry entry) {
        if (failNextCreate) {
            failNextCreate = false;
            throw new IllegalStateException("simulated entry commit failure");
        }
        findByKey(entry.scope().organizationId(), entry.scope().teamId(), entry.entryKey())
                .ifPresent(ignored -> {
                    throw new KnowledgeEntryKeyConflictException(entry.scope(), entry.entryKey());
                });
        entries.put(entry.id(), entry);
        versions.put(entry.id(), new ArrayList<>());
        return entry;
    }

    @Override
    public KnowledgeEntry save(
            KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
        KnowledgeEntry stored = requireExisting(entry.id());
        if (entry.version() != stored.version() + 1) {
            throw new OptimisticLockConflictException(
                    "KnowledgeEntry", entry.id(), entry.version() - 1, stored.version());
        }
        appendedVersion.ifPresent(version -> {
            if (!version.entryId().equals(entry.id()) || !version.scope().equals(entry.scope())) {
                throw new IllegalArgumentException(
                        "appended version must belong to the saving entry's scope");
            }
            versions.getOrDefault(entry.id(), List.of()).stream()
                    .filter(existing -> existing.revision().equals(version.revision()))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new OptimisticLockConflictException(
                                "KnowledgeEntryVersion",
                                entry.id(),
                                version.revision().value(),
                                existing.revision().value());
                    });
            versions.getOrDefault(entry.id(), List.of()).stream()
                    .filter(existing -> existing.contentHash().equals(version.contentHash()))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new KnowledgeVersionContentConflictException(
                                entry.id(), version.revision(), version.contentHash());
                    });
        });
        entries.put(entry.id(), entry);
        appendedVersion.ifPresent(version -> versions.get(entry.id()).add(version));
        return entry;
    }

    @Override
    public Optional<KnowledgeEntry> findById(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return Optional.ofNullable(entries.get(entryId))
                .filter(entry -> inScope(entry, organizationId, teamId));
    }

    @Override
    public Optional<KnowledgeEntry> findByKey(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey) {
        return entries.values().stream()
                .filter(entry -> inScope(entry, organizationId, teamId))
                .filter(entry -> entry.entryKey().equals(entryKey))
                .findFirst();
    }

    @Override
    public KnowledgeEntryPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryFilter filter,
            KnowledgeEntryPageRequest pageRequest) {
        List<KnowledgeEntry> matches = entries.values().stream()
                .filter(entry -> inScope(entry, organizationId, teamId))
                .filter(entry -> filter.statuses().contains(entry.status()))
                .filter(entry -> filter.category().map(category -> category == entry.category())
                        .orElse(true))
                .sorted(Comparator.comparing(entry -> entry.entryKey().value()))
                .toList();
        List<KnowledgeEntry> remaining = matches.stream()
                .filter(entry -> pageRequest.afterEntryKey()
                        .map(after -> entry.entryKey().value().compareTo(after.value()) > 0)
                        .orElse(true))
                .toList();
        List<KnowledgeEntry> page =
                remaining.stream().limit(pageRequest.limit()).toList();
        Optional<KnowledgeEntryKey> next = remaining.size() > pageRequest.limit()
                ? Optional.of(page.get(page.size() - 1).entryKey())
                : Optional.empty();
        return new KnowledgeEntryPage(page, next);
    }

    @Override
    public Optional<KnowledgeEntryVersion> findVersion(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision) {
        return fullHistory(organizationId, teamId, entryId).stream()
                .filter(version -> version.revision().equals(revision))
                .findFirst();
    }

    @Override
    public KnowledgeEntryVersionPage findVersionHistory(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeVersionPageRequest pageRequest) {
        List<KnowledgeEntryVersion> remaining = fullHistory(organizationId, teamId, entryId).stream()
                .filter(version -> pageRequest.afterRevision()
                        .map(after -> version.revision().value() > after.value())
                        .orElse(true))
                .toList();
        List<KnowledgeEntryVersion> page =
                remaining.stream().limit(pageRequest.limit()).toList();
        Optional<KnowledgeEntryRevision> next = remaining.size() > pageRequest.limit()
                ? Optional.of(page.get(page.size() - 1).revision())
                : Optional.empty();
        return new KnowledgeEntryVersionPage(page, next);
    }

    @Override
    public Optional<KnowledgeEntryVersion> findEffectiveVersion(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return findById(organizationId, teamId, entryId)
                .filter(KnowledgeEntry::effectivelyPublished)
                .flatMap(entry -> findVersion(
                        organizationId, teamId, entryId, entry.effectiveRevision().orElseThrow()));
    }

    @Override
    public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
            OrganizationId organizationId, TeamId teamId) {
        return entries.values().stream()
                .filter(entry -> inScope(entry, organizationId, teamId))
                .filter(KnowledgeEntry::effectivelyPublished)
                .map(entry -> findVersion(
                        organizationId, teamId, entry.id(), entry.effectiveRevision().orElseThrow()))
                .flatMap(Optional::stream)
                .toList();
    }

    private List<KnowledgeEntryVersion> fullHistory(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return findById(organizationId, teamId, entryId)
                .map(entry -> versions.getOrDefault(entry.id(), List.of()).stream()
                        .sorted(Comparator.comparing(version -> version.revision().value()))
                        .toList())
                .orElseGet(List::of);
    }

    private KnowledgeEntry requireExisting(KnowledgeEntryId entryId) {
        KnowledgeEntry stored = entries.get(entryId);
        if (stored == null) {
            throw new IllegalArgumentException("unknown entry " + entryId);
        }
        return stored;
    }

    private static boolean inScope(
            KnowledgeEntry entry, OrganizationId organizationId, TeamId teamId) {
        return entry.scope().organizationId().equals(organizationId)
                && entry.scope().teamId().equals(teamId);
    }
}
