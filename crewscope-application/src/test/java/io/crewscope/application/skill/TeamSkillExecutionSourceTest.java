package io.crewscope.application.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.retrieval.InjectionManifestConflictException;
import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.SkillContentHash;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillPublication;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Load-time resolution contract for M10-A03b: the sealed manifest pins an attempt's
 * dynamic skills (re-resolved from the immutable version row with digest verification),
 * unsealed attempts resolve the approved keys against the catalog's effective published
 * versions, and every disabled switch or unpublished key resolves to nothing.
 */
final class TeamSkillExecutionSourceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final String BUILT_IN_SOURCE_ID = "coding-specialist-bundle";
    private static final PrincipalId ACTOR = PrincipalId.generate();

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final InMemoryTeamSkillRepository catalog = new InMemoryTeamSkillRepository();
    private final ManifestStore manifests = new ManifestStore();
    private final AgentConfigurationRepository configurations =
            mock(AgentConfigurationRepository.class);
    private final TaskExecutionId executionId = TaskExecutionId.generate();

    private DefaultTeamSkillExecutionSource source;

    @BeforeEach
    void setUp() {
        source = new DefaultTeamSkillExecutionSource(
                catalog, manifests, configurations, BUILT_IN_SOURCE_ID, true, true);
    }

    // ------------------------------------------------------------------ catalog resolution

    @Test
    void unsealedAttemptsResolveApprovedKeysAgainstEffectivePublishedVersions() {
        TeamSkill published = publishedSkill("code-review", "Review diffs line by line.");
        publishedSkill("deploy-runbook", "Drain the pool first.");

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved = source.resolveForExecution(
                organizationId, teamId, Set.of("deploy-runbook", "code-review"),
                executionId, 1);

        assertEquals(List.of("code-review", "deploy-runbook"),
                resolved.stream().map(TeamSkillExecutionSource.PublishedTeamSkill::skillKey)
                        .toList(),
                "resolution iterates the approved set in stable key order");
        assertEquals(1, resolved.get(0).revision());
        // The stored document is the draft-normalized SKILL.md (trailing whitespace stripped).
        assertEquals(document("code-review", "Review diffs line by line.").stripTrailing(),
                resolved.get(0).content());
        assertEquals(
                SkillContentHash.of(
                        document("code-review", "Review diffs line by line.").stripTrailing())
                        .value(),
                resolved.get(0).contentHash());
        assertEquals(published.id(), catalog
                .findByKey(organizationId, teamId, new TeamSkillKey("code-review"))
                .orElseThrow().id());
    }

    // ------------------------------------------------------------------ pinned read-back

    @Test
    void pinnedExecutionsReadApprovedKeysFromThePinnedConfigurationRevision() {
        publishedSkill("code-review", "Review diffs line by line.");
        AgentConfigurationVersion version = pinnedConfiguration(Set.of("code-review"));
        when(configurations.findByRevision(any(), any(), any()))
                .thenReturn(Optional.of(version));

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved =
                source.resolveForPinnedExecution(
                        organizationId, teamId, Optional.of(pinnedSnapshot()), executionId, 1);

        assertEquals(List.of("code-review"),
                resolved.stream().map(TeamSkillExecutionSource.PublishedTeamSkill::skillKey)
                        .toList(),
                "the pinned revision's approved keys drive the load, not the current one");
    }

    @Test
    void snapshotsWithoutAPinnedConfigurationLoadNothingDynamic() {
        assertTrue(source.resolveForPinnedExecution(
                organizationId, teamId, Optional.empty(), executionId, 1).isEmpty());
    }

    @Test
    void aMissingPinnedConfigurationRevisionFailsClosed() {
        when(configurations.findByRevision(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> source.resolveForPinnedExecution(
                organizationId, teamId, Optional.of(pinnedSnapshot()), executionId, 1));
    }

    @Test
    void aPinnedConfigurationHashMismatchFailsClosed() {
        AgentConfigurationVersion tampered = pinnedConfiguration(Set.of("code-review"));
        when(tampered.configurationHash())
                .thenReturn(new AgentConfigurationHash("b".repeat(64)));
        when(configurations.findByRevision(any(), any(), any()))
                .thenReturn(Optional.of(tampered));

        assertThrows(IllegalStateException.class, () -> source.resolveForPinnedExecution(
                organizationId, teamId, Optional.of(pinnedSnapshot()), executionId, 1));
    }

    private static ResolvedAgentExecutionConfiguration pinnedSnapshot() {
        ResolvedAgentExecutionConfiguration pinned =
                mock(ResolvedAgentExecutionConfiguration.class);
        when(pinned.agentProfileId()).thenReturn(AgentProfileId.generate());
        when(pinned.configurationRevision()).thenReturn(new AgentConfigurationRevision(4));
        when(pinned.configurationHash()).thenReturn(new AgentConfigurationHash("a".repeat(64)));
        return pinned;
    }

    private static AgentConfigurationVersion pinnedConfiguration(Set<String> approvedSkillKeys) {
        AgentConfigurationVersion version = mock(AgentConfigurationVersion.class);
        when(version.configurationHash()).thenReturn(new AgentConfigurationHash("a".repeat(64)));
        when(version.approvedSkillKeys()).thenReturn(approvedSkillKeys);
        return version;
    }

    @Test
    void draftsDisabledSkillsAndForeignKeysResolveToNothing() {
        publishedSkill("live-skill", "Live");
        // DRAFT: created but never published.
        catalog.create(TeamSkill.create(
                teamScope(), new TeamSkillKey("draft-skill"), document("draft-skill", "Draft"),
                Optional.empty(), ACTOR, NOW));
        // DISABLED: published then disabled.
        TeamSkill retired = publishedSkill("retired-skill", "Retired");
        catalog.save(
                retired.disable("retired", ACTOR, NOW), Optional.empty());
        // No catalog row at all for "ghost-skill".

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved = source.resolveForExecution(
                organizationId, teamId,
                Set.of("live-skill", "draft-skill", "retired-skill", "ghost-skill"),
                executionId, 1);

        assertEquals(
                List.of("live-skill"),
                resolved.stream().map(TeamSkillExecutionSource.PublishedTeamSkill::skillKey)
                        .toList());
    }

    @Test
    void unparsableConfigurationKeysMatchNothing() {
        publishedSkill("valid-key", "Live");

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved = source.resolveForExecution(
                organizationId, teamId, Set.of("valid-key", "Invalid_Key", "also invalid"),
                executionId, 1);

        assertEquals(
                List.of("valid-key"),
                resolved.stream().map(TeamSkillExecutionSource.PublishedTeamSkill::skillKey)
                        .toList());
    }

    // ------------------------------------------------------------------ manifest pinning

    @Test
    void sealedManifestReResolvesTheAttemptSPinnedTriples() {
        TeamSkill head = publishedSkill("code-review", "Review diffs line by line.");
        // A later publish lands after the seal: the pinned revision must survive it.
        // Draft replacement and publication are two separate command commits (A03a shape).
        TeamSkill redrafted = catalog.save(
                head.updateDraft(document("code-review", "Updated wording."), ACTOR, NOW),
                Optional.empty());
        TeamSkillPublication second = redrafted.publish(ACTOR, NOW);
        catalog.save(second.skill(), Optional.of(second.version()));
        manifests.seal(manifest(List.of(
                builtInRef(),
                new ManifestSourceRef(
                        ManifestSourceType.SKILL_INSTRUCTION,
                        "code-review",
                        1,
                        SkillContentHash.of(
                                document("code-review", "Review diffs line by line.")
                                        .stripTrailing()).value(),
                        ManifestSourceStage.INJECTED))));

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved = source.resolveForExecution(
                organizationId, teamId, Set.of("code-review"), executionId, 3);

        assertEquals(1, resolved.size());
        assertEquals(1, resolved.get(0).revision(),
                "the sealed revision is pinned even though revision 2 exists");
        assertEquals(document("code-review", "Review diffs line by line.").stripTrailing(),
                resolved.get(0).content());
    }

    @Test
    void sealedDigestMismatchFailsClosed() {
        publishedSkill("code-review", "Review diffs line by line.");
        manifests.seal(manifest(List.of(
                builtInRef(),
                new ManifestSourceRef(
                        ManifestSourceType.SKILL_INSTRUCTION,
                        "code-review",
                        1,
                        SkillContentHash.of("tampered content").value(),
                        ManifestSourceStage.INJECTED))));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> source.resolveForExecution(
                        organizationId, teamId, Set.of("code-review"), executionId, 3));
        assertTrue(failure.getMessage().contains("digest"));
    }

    @Test
    void sealedReferenceToAMissingVersionFailsClosed() {
        publishedSkill("code-review", "Review diffs line by line.");
        manifests.seal(manifest(List.of(
                builtInRef(),
                new ManifestSourceRef(
                        ManifestSourceType.SKILL_INSTRUCTION,
                        "code-review",
                        7,
                        SkillContentHash.of("Review diffs line by line.").value(),
                        ManifestSourceStage.INJECTED))));

        assertThrows(
                IllegalStateException.class,
                () -> source.resolveForExecution(
                        organizationId, teamId, Set.of("code-review"), executionId, 3));
    }

    // ------------------------------------------------------------------ switches and sets

    @Test
    void eitherSwitchOffLoadsNoDynamicSkills() {
        publishedSkill("code-review", "Review diffs line by line.");
        Set<String> keys = Set.of("code-review");

        assertTrue(new DefaultTeamSkillExecutionSource(
                        catalog, manifests, configurations, BUILT_IN_SOURCE_ID, false, true)
                .resolveForExecution(organizationId, teamId, keys, executionId, 1).isEmpty());
        assertTrue(new DefaultTeamSkillExecutionSource(
                        catalog, manifests, configurations, BUILT_IN_SOURCE_ID, true, false)
                .resolveForExecution(organizationId, teamId, keys, executionId, 1).isEmpty());
    }

    @Test
    void emptyApprovedSetsWhichCoversSchemaV1SnapshotsResolveToNothing() {
        assertTrue(source.resolveForExecution(
                organizationId, teamId, Set.of(), executionId, 1).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private io.crewscope.domain.team.TeamScope teamScope() {
        return new io.crewscope.domain.team.TeamScope(organizationId, teamId);
    }

    private TeamSkill publishedSkill(String key, String body) {
        TeamSkill stored = catalog.create(TeamSkill.create(
                teamScope(),
                new TeamSkillKey(key),
                document(key, body),
                Optional.empty(),
                ACTOR,
                NOW));
        TeamSkillPublication publication = stored.publish(ACTOR, NOW);
        return catalog.save(publication.skill(), Optional.of(publication.version()));
    }

    private static String document(String key, String body) {
        return "---\nname: " + key + "\ndescription: " + body + "\n---\n\n" + body + "\n";
    }

    private static ManifestSourceRef builtInRef() {
        return new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION,
                BUILT_IN_SOURCE_ID,
                1,
                "0".repeat(64),
                ManifestSourceStage.INJECTED);
    }

    private InjectionManifest manifest(List<ManifestSourceRef> references) {
        return new InjectionManifest(
                InjectionManifestId.generate(),
                executionId,
                3,
                references,
                List.of(),
                new PromptBudgetSnapshot(8192, 10, 0, 0),
                List.of(),
                NOW);
    }

    /** Attempt-keyed manifest store mirroring the (executionId, attempt) uniqueness. */
    private static final class ManifestStore implements InjectionManifestRepository {

        private final Map<String, InjectionManifest> manifests = new HashMap<>();

        void seal(InjectionManifest manifest) {
            manifests.put(
                    manifest.executionId().value() + ":" + manifest.attempt(), manifest);
        }

        @Override
        public InjectionManifest append(InjectionManifest manifest) {
            throw new InjectionManifestConflictException(manifest.executionId(), manifest.attempt());
        }

        @Override
        public Optional<InjectionManifest> findByAttempt(
                OrganizationId organizationId,
                TeamId teamId,
                TaskExecutionId executionId,
                int attempt) {
            return Optional.ofNullable(manifests.get(executionId.value() + ":" + attempt));
        }

        @Override
        public List<InjectionManifest> findByExecution(
                OrganizationId organizationId, TeamId teamId, TaskExecutionId executionId) {
            return new ArrayList<>(manifests.values());
        }
    }
}
