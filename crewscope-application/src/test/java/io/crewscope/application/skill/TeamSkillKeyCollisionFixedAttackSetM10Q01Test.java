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
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M10-Q01 fixed cross-team attack set for the skill load path (S01 §4's frozen proof
 * duty, deferred to this package): two teams of one organization publish the same
 * skill key with near-identical intent and different content. Every resolution —
 * unsealed catalog resolution, sealed-manifest re-resolution with digest
 * reconciliation, and pinned-configuration resolution — must answer only the
 * attacking team's coordinates; the neighbour's same-key rows neither substitute
 * for a missing skill nor soften the fail-closed digest check.
 */
final class TeamSkillKeyCollisionFixedAttackSetM10Q01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final String BUILT_IN_SOURCE_ID = "coding-specialist-bundle";
    private static final PrincipalId ACTOR = PrincipalId.generate();

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamAlpha = TeamId.generate();
    /** The neighbour team of the same organization: the closest possible stranger. */
    private final TeamId teamBeta = TeamId.generate();
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

    @Test
    void theNeighbourSSameKeyPublicationNeverSubstitutesForTheAttackingTeam() {
        // Only the neighbour has published the key so far.
        publishedSkill(teamBeta, "code-review", "Review diffs the Beta way.");

        assertTrue(source.resolveForExecution(
                        organizationId, teamAlpha, Set.of("code-review"), executionId, 1)
                        .isEmpty(),
                "a missing skill of the attacking team stays missing — the neighbour's "
                        + "same-key row never substitutes");

        // The attacking team publishes its own content under the same key.
        publishedSkill(teamAlpha, "code-review", "Review diffs line by line.");

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved =
                source.resolveForExecution(
                        organizationId, teamAlpha, Set.of("code-review"), executionId, 1);

        assertEquals(1, resolved.size());
        assertEquals(document("code-review", "Review diffs line by line.").stripTrailing(),
                resolved.get(0).content(),
                "the attacking team loads exactly its own document");
        assertEquals(SkillContentHash.of(
                        document("code-review", "Review diffs line by line.").stripTrailing())
                        .value(),
                resolved.get(0).contentHash());

        // Control assertion of the frozen proof: the neighbour's row exists in the very
        // same catalog, one team predicate away.
        TeamSkill neighbour = catalog.findByKey(
                        organizationId, teamBeta, new TeamSkillKey("code-review"))
                .orElseThrow();
        assertTrue(neighbour.effectivelyPublished(),
                "the neighbour's same-key publication is right there, and irrelevant");
    }

    @Test
    void sealedReconciliationIgnoresTheNeighbourSSameKeyRowsAndStaysFailClosed() {
        publishedSkill(teamAlpha, "code-review", "Review diffs line by line.");
        publishedSkill(teamBeta, "code-review", "Review diffs the Beta way.");
        String alphaHash = SkillContentHash.of(
                document("code-review", "Review diffs line by line.").stripTrailing()).value();
        // The sealed reference pins the attacking team's revision 1.
        manifests.seal(manifest(3, skillRef("code-review", 1, alphaHash)));

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved =
                source.resolveForExecution(
                        organizationId, teamAlpha, Set.of("code-review"), executionId, 3);

        assertEquals(1, resolved.size());
        assertEquals(1, resolved.get(0).revision());
        assertEquals(alphaHash, resolved.get(0).contentHash(),
                "the neighbour's same-key row never enters the reconciliation");

        // The fail-closed digest check keeps its teeth with the neighbour's same-key
        // same-revision row sitting in the catalog: a tampered seal still stops the load
        // instead of being satisfied by any same-key content elsewhere.
        manifests.seal(manifest(4, skillRef("code-review", 1,
                SkillContentHash.of("tampered content").value())));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> source.resolveForExecution(
                        organizationId, teamAlpha, Set.of("code-review"), executionId, 4));
        assertTrue(failure.getMessage().contains("digest"));
    }

    @Test
    void pinnedExecutionsLoadOnlyTheAttackingTeamSSkills() {
        publishedSkill(teamAlpha, "code-review", "Review diffs line by line.");
        publishedSkill(teamBeta, "code-review", "Review diffs the Beta way.");
        AgentConfigurationVersion pinned = pinnedConfiguration(Set.of("code-review"));
        when(configurations.findByRevision(any(), any(), any()))
                .thenReturn(Optional.of(pinned));

        List<TeamSkillExecutionSource.PublishedTeamSkill> resolved =
                source.resolveForPinnedExecution(
                        organizationId, teamAlpha, Optional.of(pinnedSnapshot()), executionId, 1);

        assertEquals(List.of("code-review"),
                resolved.stream()
                        .map(TeamSkillExecutionSource.PublishedTeamSkill::skillKey)
                        .toList());
        assertEquals(document("code-review", "Review diffs line by line.").stripTrailing(),
                resolved.get(0).content(),
                "the pinned load answers the attacking team's coordinates only");
    }

    // ------------------------------------------------------------------ helpers

    private TeamSkill publishedSkill(TeamId team, String key, String body) {
        TeamSkill stored = catalog.create(TeamSkill.create(
                new io.crewscope.domain.team.TeamScope(organizationId, team),
                new TeamSkillKey(key),
                document(key, body),
                Optional.empty(),
                ACTOR,
                NOW));
        var publication = stored.publish(ACTOR, NOW);
        return catalog.save(publication.skill(), Optional.of(publication.version()));
    }

    private static String document(String key, String body) {
        return "---\nname: " + key + "\ndescription: " + body + "\n---\n\n" + body + "\n";
    }

    private static ManifestSourceRef skillRef(String key, long revision, String hash) {
        return new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION,
                key,
                revision,
                hash,
                ManifestSourceStage.INJECTED);
    }

    private static ManifestSourceRef builtInRef() {
        return new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION,
                BUILT_IN_SOURCE_ID,
                1,
                "0".repeat(64),
                ManifestSourceStage.INJECTED);
    }

    private InjectionManifest manifest(int attempt, ManifestSourceRef skill) {
        return new InjectionManifest(
                InjectionManifestId.generate(),
                executionId,
                attempt,
                List.of(builtInRef(), skill),
                List.of(),
                new PromptBudgetSnapshot(8192, 10, 0, 0),
                List.of(),
                NOW);
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
            return List.copyOf(manifests.values());
        }
    }
}
