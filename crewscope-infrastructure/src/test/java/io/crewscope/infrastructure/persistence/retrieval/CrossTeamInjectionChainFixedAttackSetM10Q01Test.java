package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.memory.DefaultAgentMemoryPolicyCatalog;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeRetrievalQuery;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.PromptInjectionPlan;
import io.crewscope.application.retrieval.PromptInjectionRequest;
import io.crewscope.application.retrieval.PromptInjectionService;
import io.crewscope.application.retrieval.RepositoryChunkVector;
import io.crewscope.application.retrieval.RepositoryGeneration;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.InjectionBudgetPlanner;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.knowledge.PgVectorKnowledgeEmbeddingStore;
import io.crewscope.infrastructure.persistence.memory.JdbcAgentMemoryRepositoryAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M10-Q01 fixed cross-team attack set over the whole injection chain (S01 §4's frozen
 * proof duty, deferred to this package): the real {@link PromptInjectionService} —
 * real retrieval over the real pgvector stores, the real {@link AgentMemoryService}
 * over the real memory adapter, the real manifest adapter — assembles one execution's
 * injection while a neighbour team of the same organization holds deliberately similar
 * knowledge entries, identically-pathed chunks and near-identical memory values.
 * Everything the sealed manifest references must stay inside the attacking team's
 * coordinates, and the unscoped control SQL must immediately surface the neighbour's
 * rows: the isolation is proven to live in the predicates, not in a data coincidence.
 * The member-facing memory view answers the neighbour team's profile with the same
 * not-found shape a missing team answers.
 */
class CrossTeamInjectionChainFixedAttackSetM10Q01Test extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final SourceCommit COMMIT =
            new SourceCommit("0123456789012345678901234567890123456789");
    private static final AgentMemoryPolicyReference DEFAULT_REF =
            AgentMemoryPolicy.defaults().reference();
    private static final ManifestSourceRef SKILL_INSTRUCTION = new ManifestSourceRef(
            ManifestSourceType.SKILL_INSTRUCTION, "builtin-execution-skill",
            1L, "f".repeat(64), ManifestSourceStage.INJECTED);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Alpha creator",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Alpha", NOW);
    private final TeamId teamAlpha = initialization.team().id();
    private final PrincipalId actorId = actor.id();
    /** The neighbour team of the same organization: the closest possible stranger. */
    private final TeamId teamBeta = TeamId.generate();
    private final PrincipalId betaOwner = PrincipalId.generate();
    private final AgentProfileId profileAlpha = AgentProfileId.generate();
    private final AgentProfileId profileBeta = AgentProfileId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingAlpha = RepositoryBindingId.generate();
    private final RepositoryBindingId bindingBeta = RepositoryBindingId.generate();
    private final Map<KnowledgeEntryId, KnowledgeEntryVersion> versions = new LinkedHashMap<>();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private JdbcRepositoryGenerationStoreAdapter generationWrites;
    private PgVectorKnowledgeEmbeddingStore knowledgeVectors;
    private PgVectorRepositoryChunkStore chunkVectors;
    private JdbcAgentMemoryRepositoryAdapter memoryAdapter;
    private UUID workspaceAlpha;
    private TaskExecutionId executionId;
    private AgentMemoryService memory;
    private PromptInjectionService injection;

    private final Set<UUID> alphaEntries = new LinkedHashSet<>();
    private final Set<String> alphaMemoryKeys = new LinkedHashSet<>();
    private final Set<UUID> betaEntries = new LinkedHashSet<>();
    private final Set<String> betaMemoryKeys = new LinkedHashSet<>();

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        generationWrites = new JdbcRepositoryGenerationStoreAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        knowledgeVectors = new PgVectorKnowledgeEmbeddingStore(jdbc);
        chunkVectors = new PgVectorRepositoryChunkStore(jdbc);
        memoryAdapter = new JdbcAgentMemoryRepositoryAdapter(jdbc);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();

        seedExecutionChain();
        seedNeighbourTenant();

        GuardStore guards = new GuardStore(initialization);
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                new FixedVectorExecutor(),
                new JdbcGenerationCatalogAdapter(jdbc),
                knowledgeVectors,
                chunkVectors,
                new VersionReadingRepository(),
                new SingleBindingRepository(),
                guards,
                guards,
                ChunkingPolicy.defaults(),
                true);
        memory = new AgentMemoryService(
                memoryAdapter,
                new SingleProfileRepository(),
                new SingleConfigurationRepository(),
                new DefaultAgentMemoryPolicyCatalog(),
                guards,
                guards,
                directTransactions(),
                () -> NOW,
                true);
        JdbcInjectionManifestRepositoryAdapter manifests =
                new JdbcInjectionManifestRepositoryAdapter(
                        jdbc, new ObjectMapper().findAndRegisterModules());
        injection = new PromptInjectionService(
                retrieval,
                memory,
                manifests,
                true,
                true,
                new InjectionBudgetPlanner.InjectionBudgetLimits(8192L, 3072L, 4096L, 1024L),
                SKILL_INSTRUCTION,
                () -> NOW);

        seedKnowledge(teamAlpha, "alpha", "c", alphaEntries);
        seedKnowledge(teamBeta, "beta", "4", betaEntries);
        seedChunks(teamAlpha, bindingAlpha, "a");
        seedChunks(teamBeta, bindingBeta, "7");
        seedMemory(ownerKey(teamAlpha, profileAlpha, actorId),
                "alpha-reply-language", "简体中文", "alpha-code-style", "tabs",
                alphaMemoryKeys);
        seedMemory(ownerKey(teamBeta, profileBeta, betaOwner),
                "beta-reply-language", "Deutsch", "beta-code-style", "spaces",
                betaMemoryKeys);
    }

    @Test
    void theSealedManifestReferencesStayInsideTheAttackingTeam() {
        PromptInjectionPlan plan = injection.assemble(request());

        assertTrue(plan.injected(), "the switch is on and the chain is real: injection runs");
        List<ManifestSourceRef> references = plan.manifest().references();
        // One skill instruction, three knowledge entries, two one-fragment chunk
        // candidates and two memory preferences — small enough that the frozen budget
        // never trims, so every reference is sealed as INJECTED.
        assertEquals(1 + 3 + 2 + 2, references.size());
        assertTrue(references.stream().allMatch(ManifestSourceRef::injected),
                "the frozen default budget keeps this payload whole");

        Set<String> sealedKnowledge = references.stream()
                .filter(reference -> reference.type() == ManifestSourceType.KNOWLEDGE_ENTRY)
                .map(ManifestSourceRef::sourceId)
                .collect(Collectors.toSet());
        assertEquals(alphaEntries.stream().map(UUID::toString).collect(Collectors.toSet()),
                sealedKnowledge,
                "every sealed knowledge reference is an attacking-team entry id");
        Set<String> sealedChunks = references.stream()
                .filter(reference -> reference.type() == ManifestSourceType.REPOSITORY_CHUNK)
                .map(ManifestSourceRef::sourceId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("docs/runbook.md#1-10", "docs/rollback.md#1-10"), sealedChunks,
                "every sealed chunk reference is an attacking-team span");
        Set<String> sealedMemory = references.stream()
                .filter(reference -> reference.type() == ManifestSourceType.MEMORY_PREFERENCE)
                .map(ManifestSourceRef::sourceId)
                .collect(Collectors.toSet());
        assertEquals(alphaMemoryKeys, sealedMemory,
                "every sealed memory reference is an attacking-team memory key");

        // The rendered payload agrees with the seal: nothing of the neighbour's, ever.
        assertTrue(plan.knowledge().stream()
                .map(candidate -> candidate.entry().entryId().value())
                .allMatch(alphaEntries::contains));
        assertTrue(plan.chunks().stream()
                .flatMap(candidate -> candidate.fragments().stream())
                .map(fragment -> fragment.path())
                .allMatch(path -> Set.of("docs/runbook.md", "docs/rollback.md").contains(path)));
        assertTrue(plan.memory().stream()
                .map(entry -> entry.memoryKey().value())
                .allMatch(alphaMemoryKeys::contains));

        // Control assertion of the frozen proof: drop the owner predicate and the
        // neighbour's near-identical rows surface immediately.
        List<String> unscopedMemory = jdbc.queryForList(
                "SELECT memory_key FROM crewscope.agent_memory_entry WHERE organization_id = ?",
                String.class, organizationId.value());
        assertEquals(alphaMemoryKeys.size() + betaMemoryKeys.size(), unscopedMemory.size());
        assertTrue(unscopedMemory.containsAll(betaMemoryKeys),
                "the neighbour's values are right there one predicate away");
    }

    @Test
    void theMemberMemoryViewAnswersTheNeighbourTeamAsNotFound() {
        // Same organization, other team: the guard answers the identical not-found
        // shape a missing team answers — never a cross-team read.
        assertThrows(AggregateNotFoundException.class, () -> memory.view(
                new TeamAccessContext(actor, false), organizationId, teamBeta, profileBeta));
    }

    // ------------------------------------------------------------------ request

    private PromptInjectionRequest request() {
        return new PromptInjectionRequest(
                organizationId, teamAlpha,
                new WorkspaceId(workspaceAlpha), projectId,
                executionId, 1, profileAlpha, actor,
                "Ship the runbook update",
                List.of("Tests pass"),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, bindingAlpha, COMMIT));
    }

    // ------------------------------------------------------------------ seeding

    /** The attacking team's full execution chain — manifest FKs need a real task_execution. */
    private void seedExecutionChain() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID responsibilitySnapshotId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        String responsibilityHash = "b".repeat(64);

        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Injection Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Alpha', 'ACTIVE')",
                teamAlpha.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Injection Workspace', 'ACTIVE')
                """,
                workspace, organizationId.value(), teamAlpha.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (id, organization_id, team_id, workspace_id, project_key, name)
                VALUES (?, ?, ?, ?, ?, 'Injection Project')
                """,
                project, organizationId.value(), teamAlpha.value(), workspace,
                "IC-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Alpha creator', 'ACTIVE')
                """,
                actorId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                memberId, organizationId.value(), teamAlpha.value(), actorId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority)
                VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Injection work item', 'READY', 'MEDIUM')
                """,
                workItemId, organizationId.value(), teamAlpha.value(), workspace, project,
                "IC-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, 'EXECUTOR', ?, 'USER', ?, 'ACTIVE', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                assignmentId, organizationId.value(), teamAlpha.value(), workspace, project,
                workItemId, actorId.value(), memberId, actorId.value(), actorId.value(),
                actorId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                responsibilitySnapshotId, organizationId.value(), teamAlpha.value(), workspace,
                project, workItemId, responsibilityHash, actorId.value(), actorId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot_entry (
                    snapshot_id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, assignment_id, assignment_version, role,
                    principal_id, principal_type, member_id, assigned_at, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'EXECUTOR', ?, 'USER', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                responsibilitySnapshotId, organizationId.value(), teamAlpha.value(), workspace,
                project, workItemId, assignmentId, actorId.value(), memberId);
        jdbc.update(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    objective, acceptance_criteria, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'Ship the runbook update',
                        '["Tests pass"]'::jsonb, 'CREATED', ?, ?)
                """,
                taskId, organizationId.value(), teamAlpha.value(), workspace, project,
                workItemId, responsibilitySnapshotId, actorId.value(), actorId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, 1, 3, 50, CURRENT_TIMESTAMP, 'CREATED', ?, ?)
                """,
                execution, organizationId.value(), teamAlpha.value(), workspace, project,
                taskId, actorId.value(), actorId.value());
        workspaceAlpha = workspace;
        executionId = new TaskExecutionId(execution);
        UUID agentPrincipalAlpha = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, team_id, principal_type, display_name, status)
                VALUES (?, ?, ?, 'TEAM_AGENT', 'Alpha Agent', 'ACTIVE')
                """,
                agentPrincipalAlpha, organizationId.value(), teamAlpha.value());
        seedAgentProfileRow(profileAlpha.value(), teamAlpha, workspace,
                agentPrincipalAlpha, actorId);
    }

    /** The neighbour tenant: memory owner FKs need real team/profile/principal rows. */
    private void seedNeighbourTenant() {
        UUID workspaceBeta = UUID.randomUUID();
        UUID agentPrincipalBeta = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Beta', 'ACTIVE')",
                teamBeta.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Beta Workspace', 'ACTIVE')
                """,
                workspaceBeta, organizationId.value(), teamBeta.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, team_id, principal_type, display_name, status)
                VALUES (?, ?, ?, 'TEAM_AGENT', 'Beta Agent', 'ACTIVE')
                """,
                agentPrincipalBeta, organizationId.value(), teamBeta.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Beta owner', 'ACTIVE')
                """,
                betaOwner.value(), organizationId.value());
        seedAgentProfileRow(profileBeta.value(), teamBeta, workspaceBeta,
                agentPrincipalBeta, betaOwner);
    }

    private void seedAgentProfileRow(
            UUID profileRowId, TeamId team, UUID workspaceId,
            UUID agentPrincipalId, PrincipalId owner) {
        jdbc.update(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    profile_type, status, created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 'TEAM', 'ACTIVE', ?, ?)
                """,
                profileRowId, organizationId.value(), team.value(), workspaceId,
                agentPrincipalId, owner.value(), owner.value());
    }

    private void seedKnowledge(TeamId team, String keyPrefix, String hashChar, Set<UUID> into) {
        for (int index = 0; index < 3; index++) {
            UUID entryId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO crewscope.knowledge_entry (
                        id, organization_id, team_id, entry_key, status, effective_revision,
                        latest_revision, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id)
                    VALUES (?, ?, ?, ?, 'PUBLISHED', 1, 1, 0, now(), ?, now(), ?)
                    """,
                    entryId, organizationId.value(), team.value(),
                    keyPrefix + "-entry-" + index, actorId.value(), actorId.value());
            String versionHash = UUID.nameUUIDFromBytes(
                            ("injection-" + entryId).getBytes())
                    .toString().replace("-", "") + "0".repeat(32);
            jdbc.update(
                    """
                    INSERT INTO crewscope.knowledge_entry_version (
                        organization_id, team_id, entry_id, revision, previous_revision,
                        title, content, content_hash, created_at, created_by_principal_id)
                    VALUES (?, ?, ?, 1, NULL, 'Title', 'Content', ?, now(), ?)
                    """,
                    organizationId.value(), team.value(), entryId, versionHash, actorId.value());
            versions.put(new KnowledgeEntryId(entryId), KnowledgeEntryVersion.create(
                    new KnowledgeEntryId(entryId),
                    new TeamScope(organizationId, team),
                    new KnowledgeEntryRevision(1),
                    Optional.empty(),
                    "Title", "Content", actorId, NOW));
            knowledgeVectors.replace(new KnowledgeEmbeddingVector(
                    organizationId, team, new KnowledgeEntryId(entryId),
                    new KnowledgeEntryRevision(1), MODEL,
                    String.valueOf((char) (hashChar.charAt(0) + index)).repeat(64),
                    unitVector(0)));
            into.add(entryId);
        }
    }

    /** Opens and activates one generation of the team's coordinate, seeding two chunks. */
    private void seedChunks(TeamId team, RepositoryBindingId binding, String hashChar) {
        RepositoryIndexKey key = new RepositoryIndexKey(
                organizationId, team, binding, COMMIT,
                ChunkingPolicy.defaults().policyHash(), MODEL);
        jobs.findLiveByIndexKey(key).ifPresent(this::retire);
        UUID job = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, team, projectId,
                key, actorId, NOW)).id();
        RepositoryGeneration opened = generationWrites.open(key, job);
        generationWrites.activate(
                opened.generationKey(), GenerationRetentionPolicy.DEFAULT, actorId);
        chunkVectors.replaceBatch(opened.generationKey(), List.of(
                chunk(opened.generationKey(), 1, "docs/runbook.md",
                        hashChar.repeat(64)),
                chunk(opened.generationKey(), 2, "docs/rollback.md",
                        String.valueOf((char) (hashChar.charAt(0) + 1)).repeat(64))));
    }

    private void retire(KnowledgeIndexJob live) {
        // Generations never overlap in time across coordinates here, so the global
        // claim is the coordinate's own live job.
        KnowledgeIndexJob claim = jobs.claimNext("retrieval-test", NOW, LEASE).orElseThrow();
        assertEquals(live.id(), claim.id(), "the only live job of the coordinate is claimed");
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(
                claim.failed("SUPERSEDED", done), "retrieval-test", done, LEASE).isPresent());
    }

    private RepositoryChunkVector chunk(
            RepositoryGenerationKey generation, int chunkSeq, String path, String contentHash) {
        return new RepositoryChunkVector(
                generation, chunkSeq, path, "markdown", 1, 10,
                contentHash, "span " + chunkSeq + " of " + path, MODEL, unitVector(0));
    }

    /** Near-identical memory values in both teams: one identifying word apart. */
    private void seedMemory(
            AgentMemoryOwnerKey key, String firstKey, String firstValue,
            String secondKey, String secondValue, Set<String> into) {
        memoryAdapter.ensureOwner(key, key.ownerPrincipalId(), NOW);
        UtcTimestamp expiresAt = UtcTimestamp.from(NOW.value().plus(Duration.ofDays(90)));
        for (String[] pair : new String[][] {{firstKey, firstValue}, {secondKey, secondValue}}) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.agent_memory_entry (
                        id, organization_id, team_id, agent_profile_id, owner_principal_id,
                        policy_id, policy_version, memory_key, value, clearance_generation,
                        version, expires_at, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), key.organizationId().value(), key.teamId().value(),
                    key.agentProfileId().value(), key.ownerPrincipalId().value(),
                    DEFAULT_REF.policyId(), DEFAULT_REF.version(), pair[0], pair[1],
                    expiresAt.toOffsetDateTime(), NOW.toOffsetDateTime(),
                    key.ownerPrincipalId().value(),
                    NOW.toOffsetDateTime(), key.ownerPrincipalId().value());
            into.add(pair[0]);
        }
    }

    private AgentMemoryOwnerKey ownerKey(
            TeamId team, AgentProfileId profile, PrincipalId owner) {
        return new AgentMemoryOwnerKey(organizationId, team, profile, owner);
    }

    // ------------------------------------------------------------------ wiring collaborators

    private static TransactionExecutor directTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
    }

    /** The guard collaborator pair over the attacking team. */
    private static final class GuardStore implements TeamRepository, TeamMembershipQuery {
        private final TeamInitialization initialization;
        private final List<TeamMember> members;

        private GuardStore(TeamInitialization initialization) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
        }

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId org, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(org) && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId org, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId org, TeamId team) {
            return members;
        }
    }

    /** The version read only; every other read fails the test loudly. */
    private final class VersionReadingRepository implements KnowledgeRepository {
        @Override
        public KnowledgeEntry create(KnowledgeEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntry save(
                KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findById(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId org, TeamId team, KnowledgeEntryKey entryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryPage findByTeam(
                OrganizationId org, TeamId team,
                KnowledgeEntryFilter filter, KnowledgeEntryPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            KnowledgeEntryVersion version = versions.get(entryId);
            return version != null && version.revision().equals(revision)
                    ? Optional.of(version) : Optional.empty();
        }

        @Override
        public KnowledgeEntryVersionPage findVersionHistory(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId,
                KnowledgeVersionPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId org, TeamId team) {
            throw new UnsupportedOperationException();
        }
    }

    /** One ACTIVE binding at the attacking team's fixed four coordinates. */
    private final class SingleBindingRepository implements RepositoryBindingRepository {
        @Override
        public RepositoryBinding create(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RepositoryBinding update(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RepositoryBinding> findById(
                OrganizationId org, TeamId team, WorkProjectId project, RepositoryBindingId id) {
            return org.equals(organizationId) && team.equals(teamAlpha)
                    && project.equals(projectId) && id.equals(bindingAlpha)
                    ? Optional.of(RepositoryBinding.reconstitute(
                            bindingAlpha,
                            new RepositoryBindingScope(
                                    organizationId, teamAlpha,
                                    new WorkspaceId(workspaceAlpha), projectId),
                            RepositoryKind.LOCAL_MANAGED,
                            new RepositoryKey("repo-" + bindingAlpha.value()),
                            new RepositoryBranchName("main"),
                            RepositoryBindingStatus.ACTIVE,
                            0,
                            AuditMetadata.createdBy(actorId, NOW)))
                    : Optional.empty();
        }

        @Override
        public Optional<RepositoryBinding> findByKey(
                OrganizationId org, TeamId team, WorkProjectId project, RepositoryKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepositoryBinding> findByWorkProject(
                OrganizationId org, TeamId team, WorkProjectId project) {
            throw new UnsupportedOperationException();
        }
    }

    /** Answers only the attacking team's profile; the injection chain never reads others. */
    private final class SingleProfileRepository implements AgentProfileRepository {
        @Override
        public AgentProfile create(AgentProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentProfile update(AgentProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentProfile> findById(OrganizationId org, AgentProfileId id) {
            return id.equals(profileAlpha) ? Optional.of(alphaProfile()) : Optional.empty();
        }

        @Override
        public Optional<AgentProfile> findActiveDefaultPersonal(
                OrganizationId organizationId, io.crewscope.domain.team.TeamMemberId ownerMemberId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentProfile> findActiveByAgentPrincipalId(
                OrganizationId organizationId, PrincipalId agentPrincipalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentProfile> findPage(OrganizationId organizationId, int offset, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    /** The one configuration the memory policy reference hangs on. */
    private final class SingleConfigurationRepository implements AgentConfigurationRepository {
        @Override
        public AgentConfigurationVersion append(AgentConfigurationVersion configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentConfigurationVersion> findCurrent(
                OrganizationId org, AgentProfileId profileId) {
            return profileId.equals(profileAlpha)
                    ? Optional.of(AgentConfigurationVersion.createInitial(
                            alphaProfile(),
                            template(),
                            Optional.of(actorId),
                            Optional.empty(),
                            Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                            Optional.empty(),
                            Set.of(),
                            Optional.of(DEFAULT_REF),
                            Optional.empty(),
                            new PolicyPackReference(PolicyPackId.generate(), 1),
                            SafeModelGenerateOptions.defaults(),
                            actorId,
                            NOW))
                    : Optional.empty();
        }

        @Override
        public Optional<AgentConfigurationVersion> findByRevision(
                OrganizationId organizationId, AgentProfileId agentProfileId,
                io.crewscope.domain.agent.AgentConfigurationRevision revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentConfigurationVersion> findAll(
                OrganizationId organizationId, AgentProfileId agentProfileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentConfigurationVersion> findPage(
                OrganizationId organizationId, AgentProfileId agentProfileId,
                int offset, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private AgentProfile alphaProfile() {
        Principal specialist = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamAlpha),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(actorId),
                "Alpha team specialist",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                NOW);
        return AgentProfile.createTemplateInstance(
                profileAlpha,
                initialization.defaultWorkspace(),
                specialist,
                AgentOwnership.user(
                        organizationId, teamAlpha, initialization.ownerMember().id()),
                template(),
                false,
                actorId,
                NOW);
    }

    private AgentTemplateDefinition template() {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey("injection-assistant"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("team.execute")), Set.of()),
                AgentTemplatePolicy.define(
                        "Approved Team execution baseline.",
                        Set.of(),
                        Set.of(),
                        Optional.empty(),
                        Set.of(
                                AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                                AgentConfigurableSlot.MODEL_BINDING,
                                AgentConfigurableSlot.KNOWLEDGE_SCOPE),
                        Set.of()),
                actorId,
                NOW);
    }

    // ------------------------------------------------------------------ shared vector fixtures

    /** Unit vector on one axis: axis 0 aligns with the fixed query. */
    private static float[] unitVector(int axis) {
        float[] vector = new float[MODEL.dimension()];
        vector[axis] = 1.0f;
        return vector;
    }

    /** Embedding seam pinned to axis 0: every query geometry is deterministic. */
    private final class FixedVectorExecutor implements KnowledgeEmbeddingExecutor {
        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId org, TeamId team) {
            return MODEL;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            return new EmbeddingBatchResult(
                    List.of(unitVector(0)),
                    MODEL,
                    new ModelCatalogCoordinate(
                            ModelCatalogEntryId.generate(),
                            new ModelProviderKey("dashscope"),
                            new ModelId(MODEL.modelKey()),
                            new ModelCatalogRevision(MODEL.revision())),
                    ModelConnectionId.generate(),
                    1);
        }
    }

    private void migrateDefaultChain() {
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .load()
                .migrate();
    }

    private void migrateVectorChain() {
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration-vector")
                .schemas("crewscope")
                .table("flyway_vector_history")
                .validateMigrationNaming(true)
                .createSchemas(false)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
