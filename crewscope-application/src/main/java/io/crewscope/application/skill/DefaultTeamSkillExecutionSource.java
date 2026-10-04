package io.crewscope.application.skill;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.SkillContentHash;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default resolution of one attempt's dynamic Team Skills (M10-A03b). The sealed
 * injection manifest is the pin: a sealed attempt re-resolves from its committed
 * SKILL references — the immutable version row supplies the content and must still
 * hash to the recorded digest — while an unsealed attempt resolves the snapshot's
 * approved keys against the catalog's effective published versions.
 */
public final class DefaultTeamSkillExecutionSource implements TeamSkillExecutionSource {

    private final TeamSkillRepository catalog;
    private final InjectionManifestRepository manifests;
    private final AgentConfigurationRepository configurations;
    private final String builtInSkillSourceId;
    private final boolean skillEnabled;
    private final boolean injectionEnabled;

    public DefaultTeamSkillExecutionSource(
            TeamSkillRepository catalog,
            InjectionManifestRepository manifests,
            AgentConfigurationRepository configurations,
            String builtInSkillSourceId,
            boolean skillEnabled,
            boolean injectionEnabled) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.builtInSkillSourceId = Objects.requireNonNull(
                builtInSkillSourceId, "builtInSkillSourceId");
        this.skillEnabled = skillEnabled;
        this.injectionEnabled = injectionEnabled;
    }

    @Override
    public List<PublishedTeamSkill> resolveForExecution(
            OrganizationId organizationId,
            TeamId teamId,
            Set<String> approvedSkillKeys,
            TaskExecutionId executionId,
            int attempt) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(approvedSkillKeys, "approvedSkillKeys");
        Objects.requireNonNull(executionId, "executionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        // Dynamic skills load only with their load evidence: without the injection
        // manifest there is no record of what was loaded, so the load must not happen.
        if (!skillEnabled || !injectionEnabled || approvedSkillKeys.isEmpty()) {
            return List.of();
        }
        Optional<List<PublishedTeamSkill>> pinned = pinnedBySealedManifest(
                organizationId, teamId, executionId, attempt);
        if (pinned.isPresent()) {
            return pinned.orElseThrow();
        }
        return resolveAgainstCatalog(organizationId, teamId, approvedSkillKeys);
    }

    @Override
    public List<PublishedTeamSkill> resolveForPinnedExecution(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<ResolvedAgentExecutionConfiguration> pinnedExecution,
            TaskExecutionId executionId,
            int attempt) {
        Objects.requireNonNull(pinnedExecution, "pinnedExecution");
        if (pinnedExecution.isEmpty()) {
            return List.of();
        }
        ResolvedAgentExecutionConfiguration pinned = pinnedExecution.orElseThrow();
        // The row is append-only and the snapshot's resolution hash already covers its
        // content, so a miss or a digest mismatch means the fact store is inconsistent:
        // stop the execution rather than guess which skills were approved.
        AgentConfigurationVersion version = configurations
                .findByRevision(
                        Objects.requireNonNull(organizationId, "organizationId"),
                        pinned.agentProfileId(),
                        pinned.configurationRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned agent configuration revision is missing"));
        if (!version.configurationHash().equals(pinned.configurationHash())) {
            throw new IllegalStateException(
                    "Pinned agent configuration hash does not match its revision");
        }
        return resolveForExecution(
                organizationId, teamId, version.approvedSkillKeys(), executionId, attempt);
    }

    /** Re-resolves from the sealed fact; empty Optional means the attempt has not sealed yet. */
    private Optional<List<PublishedTeamSkill>> pinnedBySealedManifest(
            OrganizationId organizationId, TeamId teamId, TaskExecutionId executionId, int attempt) {
        return manifests.findByAttempt(organizationId, teamId, executionId, attempt)
                .map(manifest -> manifest.injectedReferences().stream()
                        .filter(reference -> reference.type() == ManifestSourceType.SKILL_INSTRUCTION)
                        .filter(reference -> !reference.sourceId().equals(builtInSkillSourceId))
                        .map(reference -> restorePinnedVersion(
                                organizationId, teamId, reference.sourceId(),
                                reference.version(), reference.contentHash()))
                        .toList());
    }

    /**
     * The version row is append-only, so the pinned triple always addresses stored
     * content; a digest mismatch means the fact store itself is inconsistent and the
     * execution must stop rather than load unproven bytes.
     */
    private PublishedTeamSkill restorePinnedVersion(
            OrganizationId organizationId,
            TeamId teamId,
            String skillKey,
            long revision,
            String expectedContentHash) {
        TeamSkillVersion version = findVersionByKey(
                organizationId, teamId, skillKey, new TeamSkillRevision(revision))
                .orElseThrow(() -> new IllegalStateException(
                        "Sealed skill reference addresses a missing version: " + skillKey));
        if (!version.contentHash().value().equals(expectedContentHash)) {
            throw new IllegalStateException(
                    "Sealed skill reference no longer matches its content digest: " + skillKey);
        }
        return new PublishedTeamSkill(
                skillKey, revision, version.content(), expectedContentHash);
    }

    private List<PublishedTeamSkill> resolveAgainstCatalog(
            OrganizationId organizationId, TeamId teamId, Set<String> approvedSkillKeys) {
        List<PublishedTeamSkill> resolved = new ArrayList<>();
        // Sorted iteration keeps the resolution (and its manifest reference order)
        // deterministic for one approved set.
        for (String key : new TreeSet<>(approvedSkillKeys)) {
            TeamSkillKey skillKey = parseableKey(key);
            if (skillKey == null) {
                continue;
            }
            Optional<TeamSkill> head = catalog.findByKey(organizationId, teamId, skillKey);
            if (head.isEmpty() || !head.orElseThrow().effectivelyPublished()) {
                continue;
            }
            catalog.findEffectiveVersion(organizationId, teamId, head.orElseThrow().id())
                    .ifPresent(version -> resolved.add(new PublishedTeamSkill(
                            skillKey.value(),
                            version.revision().value(),
                            version.content(),
                            version.contentHash().value())));
        }
        return List.copyOf(resolved);
    }

    /** A configuration-domain key outside the catalog grammar simply matches nothing. */
    private static TeamSkillKey parseableKey(String value) {
        try {
            return TeamSkillKey.parse(value);
        } catch (DomainValidationException unparsable) {
            return null;
        }
    }

    private Optional<TeamSkillVersion> findVersionByKey(
            OrganizationId organizationId,
            TeamId teamId,
            String skillKey,
            TeamSkillRevision revision) {
        return catalog.findByKey(organizationId, teamId, new TeamSkillKey(skillKey))
                .flatMap(head -> catalog.findVersion(
                        organizationId, teamId, head.id(), revision));
    }

}
