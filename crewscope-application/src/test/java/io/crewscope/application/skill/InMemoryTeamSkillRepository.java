package io.crewscope.application.skill;

import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contract-grade in-memory fake: atomic save, optimistic head, tenant scoping, keyset
 * pages. Unlike the knowledge fake it accepts duplicate content hashes — a rollback
 * re-publishes historical content as a new revision.
 */
class InMemoryTeamSkillRepository implements TeamSkillRepository {

    private final Map<TeamSkillId, TeamSkill> skills = new HashMap<>();
    private final Map<TeamSkillId, List<TeamSkillVersion>> versions = new HashMap<>();
    boolean failNextCreate;

    @Override
    public TeamSkill create(TeamSkill skill) {
        if (failNextCreate) {
            failNextCreate = false;
            throw new IllegalStateException("simulated create failure");
        }
        findByKey(skill.scope().organizationId(), skill.scope().teamId(), skill.skillKey())
                .ifPresent(ignored -> {
                    throw new TeamSkillKeyConflictException(skill.scope(), skill.skillKey());
                });
        skills.put(skill.id(), skill);
        versions.put(skill.id(), new ArrayList<>());
        return skill;
    }

    @Override
    public TeamSkill save(TeamSkill skill, Optional<TeamSkillVersion> appendedVersion) {
        TeamSkill stored = requireExisting(skill.id());
        if (skill.version() != stored.version() + 1) {
            throw new OptimisticLockConflictException(
                    "TeamSkill", skill.id(), skill.version() - 1, stored.version());
        }
        appendedVersion.ifPresent(version -> {
            if (!version.skillId().equals(skill.id()) || !version.scope().equals(skill.scope())) {
                throw new IllegalArgumentException(
                        "appended version must belong to the saving skill's scope");
            }
            versions.getOrDefault(skill.id(), List.of()).stream()
                    .filter(existing -> existing.revision().equals(version.revision()))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new OptimisticLockConflictException(
                                "TeamSkillVersion",
                                skill.id(),
                                version.revision().value(),
                                existing.revision().value());
                    });
        });
        skills.put(skill.id(), skill);
        appendedVersion.ifPresent(version -> versions.get(skill.id()).add(version));
        return skill;
    }

    @Override
    public Optional<TeamSkill> findById(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId) {
        return Optional.ofNullable(skills.get(skillId))
                .filter(skill -> inScope(skill, organizationId, teamId));
    }

    @Override
    public Optional<TeamSkill> findByKey(
            OrganizationId organizationId, TeamId teamId, TeamSkillKey skillKey) {
        return skills.values().stream()
                .filter(skill -> inScope(skill, organizationId, teamId))
                .filter(skill -> skill.skillKey().equals(skillKey))
                .findFirst();
    }

    @Override
    public TeamSkillPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillFilter filter,
            TeamSkillPageRequest pageRequest) {
        List<TeamSkill> remaining = skills.values().stream()
                .filter(skill -> inScope(skill, organizationId, teamId))
                .filter(skill -> filter.statuses().contains(skill.status()))
                .sorted(Comparator.comparing(skill -> skill.skillKey().value()))
                .filter(skill -> pageRequest.afterSkillKey()
                        .map(after -> skill.skillKey().value().compareTo(after.value()) > 0)
                        .orElse(true))
                .toList();
        List<TeamSkill> page = remaining.stream().limit(pageRequest.limit()).toList();
        Optional<TeamSkillKey> next = remaining.size() > pageRequest.limit()
                ? Optional.of(page.get(page.size() - 1).skillKey())
                : Optional.empty();
        return new TeamSkillPage(page, next);
    }

    @Override
    public Optional<TeamSkillVersion> findVersion(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillRevision revision) {
        return fullHistory(organizationId, teamId, skillId).stream()
                .filter(version -> version.revision().equals(revision))
                .findFirst();
    }

    @Override
    public TeamSkillVersionPage findVersionHistory(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillVersionPageRequest pageRequest) {
        List<TeamSkillVersion> remaining =
                fullHistory(organizationId, teamId, skillId).stream()
                        .filter(version -> pageRequest.afterRevision()
                                .map(after -> version.revision().value() > after.value())
                                .orElse(true))
                        .toList();
        List<TeamSkillVersion> page = remaining.stream().limit(pageRequest.limit()).toList();
        Optional<TeamSkillRevision> next = remaining.size() > pageRequest.limit()
                ? Optional.of(page.get(page.size() - 1).revision())
                : Optional.empty();
        return new TeamSkillVersionPage(page, next);
    }

    @Override
    public Optional<TeamSkillVersion> findEffectiveVersion(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId) {
        return findById(organizationId, teamId, skillId)
                .filter(TeamSkill::effectivelyPublished)
                .flatMap(skill -> findVersion(
                        organizationId, teamId, skillId, skill.effectiveRevision().orElseThrow()));
    }

    private List<TeamSkillVersion> fullHistory(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId) {
        return findById(organizationId, teamId, skillId)
                .map(skill -> versions.getOrDefault(skill.id(), List.of()).stream()
                        .sorted(Comparator.comparing(version -> version.revision().value()))
                        .toList())
                .orElseGet(List::of);
    }

    private TeamSkill requireExisting(TeamSkillId skillId) {
        TeamSkill stored = skills.get(skillId);
        if (stored == null) {
            throw new IllegalArgumentException("unknown skill " + skillId);
        }
        return stored;
    }

    private static boolean inScope(
            TeamSkill skill, OrganizationId organizationId, TeamId teamId) {
        return skill.scope().organizationId().equals(organizationId)
                && skill.scope().teamId().equals(teamId);
    }
}
