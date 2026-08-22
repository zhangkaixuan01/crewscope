package io.crewscope.infrastructure.persistence.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.coding.CodingCheckpointRepository;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.coding.WorkspacePolicyOverlayRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.coding.WorkspaceWriteBudgetStore;
import io.crewscope.application.coding.WorkspaceWriteBudgetContextException;
import io.crewscope.application.coding.query.CodingAttemptQueryPort;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.CodingCheckpointTodo;
import io.crewscope.domain.coding.CodingCheckpointWorkState;
import io.crewscope.domain.coding.CodingTodoStatus;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingKeyConflictException;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestStatistics;
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the M4-D09 adapters against migrated PostgreSQL and real uniqueness constraints. */
@SpringBootTest(
        classes = M4D09CodingPersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class M4D09CodingPersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-18T01:00:00Z");

    @Autowired private RepositoryBindingRepository bindings;
    @Autowired private CodingTargetSnapshotRepository targets;
    @Autowired private ExecutionWorkspaceRepository workspaces;
    @Autowired private WorkspacePolicyRepository policies;
    @Autowired private WorkspacePolicyOverlayRepository overlays;
    @Autowired private WorkspaceWriteBudgetStore writeBudgets;
    @Autowired private DiffArtifactRepository diffs;
    @Autowired private CommandEvidenceRepository commands;
    @Autowired private TestEvidenceRepository tests;
    @Autowired private CodingCheckpointRepository checkpoints;
    @Autowired private CodingAttemptQueryPort codingQueries;
    @Autowired private CodingPersistenceMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void wiresEveryCodingPortAndRoundTripsVersionedRepositoryBinding() {
        Fixture fixture = seedFixture("roundtrip");
        RepositoryBinding original = binding(fixture, "crewscope-java", RepositoryBindingId.generate());

        RepositoryBinding created = bindings.create(original);
        RepositoryBinding loaded = bindings.findByKey(
                        fixture.organizationId(), fixture.teamId(), fixture.projectId(),
                        original.repositoryKey())
                .orElseThrow();
        RepositoryBinding disabled = bindings.update(created.disable(
                0, actor(fixture), UtcTimestamp.parse("2026-08-18T01:01:00Z")));

        assertEquals(original.id(), loaded.id());
        assertEquals(original.scope(), loaded.scope());
        assertEquals(RepositoryBindingStatus.DISABLED, disabled.status());
        assertEquals(1, disabled.version());
        assertEquals(fixture.actorId(), disabled.audit().updatedBy().orElseThrow());
        assertInstanceOf(JdbcCodingTargetSnapshotRepositoryAdapter.class, targets);
        assertInstanceOf(JdbcExecutionWorkspaceRepositoryAdapter.class, workspaces);
        assertInstanceOf(JdbcWorkspacePolicyRepositoryAdapter.class, policies);
        assertInstanceOf(JdbcWorkspacePolicyRepositoryAdapter.class, overlays);
        assertInstanceOf(JdbcDiffArtifactRepositoryAdapter.class, diffs);
        assertInstanceOf(JdbcCommandEvidenceRepositoryAdapter.class, commands);
        assertInstanceOf(JdbcTestEvidenceRepositoryAdapter.class, tests);
        assertInstanceOf(JdbcCodingCheckpointRepositoryAdapter.class, checkpoints);
    }

    @Test
    void rejectsStaleVersionAndNeverLeaksAcrossOrganizationOrProjectScope() {
        Fixture fixture = seedFixture("scope");
        RepositoryBinding created = bindings.create(
                binding(fixture, "scope-repository", RepositoryBindingId.generate()));
        RepositoryBinding staleMutation = created.disable(
                0, actor(fixture), UtcTimestamp.parse("2026-08-18T01:02:00Z"));
        bindings.update(staleMutation);

        assertThrows(OptimisticLockConflictException.class, () -> bindings.update(staleMutation));
        assertTrue(bindings.findById(
                        OrganizationId.generate(), fixture.teamId(), fixture.projectId(), created.id())
                .isEmpty());
        assertTrue(bindings.findById(
                        fixture.organizationId(), fixture.teamId(), WorkProjectId.generate(), created.id())
                .isEmpty());
        RepositoryBinding wrongProjectMutation = RepositoryBinding.reconstitute(
                staleMutation.id(),
                new RepositoryBindingScope(
                        fixture.organizationId(),
                        fixture.teamId(),
                        fixture.workspaceId(),
                        WorkProjectId.generate()),
                staleMutation.kind(),
                staleMutation.repositoryKey(),
                staleMutation.defaultBranch(),
                staleMutation.status(),
                staleMutation.version(),
                staleMutation.audit());
        assertThrows(
                AggregateNotFoundException.class,
                () -> bindings.update(wrongProjectMutation));
    }

    @Test
    void mapsConcurrentRepositoryKeyCollisionToStableDomainConflict() throws Exception {
        Fixture fixture = seedFixture("concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        bindings.create(binding(
                                fixture, "concurrent-repository", RepositoryBindingId.generate()));
                        created.incrementAndGet();
                    } catch (RepositoryBindingKeyConflictException expected) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, created.get());
        assertEquals(1, conflicts.get());
        assertEquals(1, bindings.findByWorkProject(
                        fixture.organizationId(), fixture.teamId(), fixture.projectId())
                .size());
    }

    @Test
    void preservesTypedCommandCatalogThroughCanonicalJson() {
        CommandCatalog catalog = CommandCatalog.of(
                CommandKind.TEST,
                new BuildCommand(
                        "command.mavenTest",
                        List.of("./mvnw", "test"),
                        ".",
                        60,
                        900));

        CommandCatalog restored = mapper.commandCatalog(mapper.json(catalog.commands()));

        assertEquals(catalog, restored);
        assertEquals(List.of("a", "b"), mapper.stringList(mapper.json(List.of("a", "b"))));
    }

    @Test
    void roundTripsTargetWorkspacePolicyAndCompareAndSetOverlay() {
        Fixture fixture = seedFixture("aggregate-graph");
        RepositoryBinding persistedBinding = bindings.create(
                binding(fixture, "aggregate-graph", RepositoryBindingId.generate()));
        CodingPersistenceGraph graph = CodingPersistenceGraph.create(fixture, persistedBinding);
        seedGraph(graph);

        var persistedTarget = targets.create(graph.target);
        var persistedWorkspace = workspaces.create(graph.activeWorkspace);
        var persistedPolicy = policies.create(graph.policy);
        WorkspacePolicyOverlay initial = WorkspacePolicyOverlay.unrestricted(
                WorkspacePolicyOverlayId.generate(),
                graph.policy,
                fixture.actor(),
                UtcTimestamp.parse("2026-08-18T01:07:00Z"));
        WorkspacePolicyOverlay persistedInitial = overlays.create(initial);
        WorkspacePolicyOverlay tightened = persistedInitial.tighten(
                graph.policy,
                AllowedPathSet.of("docs"),
                persistedInitial.commandCatalog(),
                persistedInitial.sandboxBudget(),
                persistedInitial.operationBudget(),
                fixture.actor(),
                UtcTimestamp.parse("2026-08-18T01:08:00Z"));
        WorkspacePolicyOverlay persistedTightened =
                overlays.appendSuccessor(tightened, persistedInitial.overlayHash());

        assertEquals(graph.target.snapshotHash(), persistedTarget.snapshotHash());
        assertEquals(graph.activeWorkspace.fingerprint(), persistedWorkspace.fingerprint());
        assertEquals(graph.policy.policyHash(), persistedPolicy.policyHash());
        assertEquals(2, persistedTightened.version());
        assertEquals(persistedTightened.overlayHash(), overlays.findCurrentByPolicy(
                        fixture.organizationId(), fixture.teamId(), fixture.projectId(), graph.policy.id())
                .orElseThrow()
                .overlayHash());
        assertThrows(
                DomainValidationException.class,
                () -> overlays.appendSuccessor(tightened, persistedInitial.overlayHash()));
    }

    @Test
    void roundTripsNormalizedDiffCommandTestAndCheckpointGraphs() {
        CodingPersistenceGraph graph = persistGraph("artifact-graph");
        UtcTimestamp startedAt = UtcTimestamp.parse("2026-08-18T01:10:00Z");
        UtcTimestamp finishedAt = UtcTimestamp.parse("2026-08-18T01:11:00Z");
        UtcTimestamp recordedAt = UtcTimestamp.parse("2026-08-18T01:12:00Z");
        UtcTimestamp publishedAt = UtcTimestamp.parse("2026-08-18T01:13:00Z");

        DiffFileEntry file = new DiffFileEntry(
                new DiffPath("docs/M4-D09.md"),
                Optional.empty(),
                DiffFileKind.ADDED,
                2,
                0,
                false,
                false,
                RuntimeContentHash.sha256("+persistence\n+evidence"),
                Optional.of("+persistence\n+evidence"));
        DiffManifest manifest = DiffManifest.initial(List.of(file));
        byte[] patchBytes = "diff --git a/docs/M4-D09.md b/docs/M4-D09.md"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PatchArtifactReference patch = new PatchArtifactReference(
                ArtifactId.generate(),
                patchBytes.length,
                RuntimeContentHash.sha256(new String(
                        patchBytes, java.nio.charset.StandardCharsets.UTF_8)));
        DiffArtifact diff = DiffArtifact.publishFinal(
                DiffArtifactId.generate(),
                graph.finalizingWorkspace,
                graph.target,
                new RepositoryCommitId("c".repeat(40)),
                manifest,
                patch,
                graph.fixture.actor(),
                publishedAt);

        EvidenceArtifactReference commandLog = evidenceArtifact(
                EvidenceArtifactKind.COMMAND_LOG,
                "text/plain;charset=utf-8",
                "BUILD SUCCESS");
        CommandSpec commandSpec = CommandSpec.capture(
                graph.policy,
                graph.buildProfile,
                CommandKind.TEST,
                List.of("./mvnw", "test"),
                60);
        CommandEvidence command = CommandEvidence.record(
                CommandEvidenceId.generate(),
                graph.activeWorkspace,
                graph.policy,
                EvidenceSequence.first(),
                commandSpec,
                startedAt,
                finishedAt,
                CommandTermination.EXITED,
                Optional.of(0),
                new EvidenceSummary("Maven test completed successfully"),
                commandLog,
                graph.fixture.actor(),
                recordedAt);
        EvidenceArtifactReference testReport = evidenceArtifact(
                EvidenceArtifactKind.TEST_REPORT,
                "application/xml",
                "<testsuite tests=\"1\"/>");
        List<AcceptanceResult> acceptance = new java.util.ArrayList<>();
        for (int index = 0; index < graph.target.acceptanceCriteria().size(); index++) {
            acceptance.add(new AcceptanceResult(
                    index + 1,
                    graph.target.acceptanceCriteria().get(index),
                    AcceptanceStatus.PASSED,
                    List.of(command.reference()),
                    new EvidenceSummary("Criterion verified")));
        }
        TestEvidence test = TestEvidence.publish(
                TestEvidenceId.generate(),
                graph.activeWorkspace,
                graph.target,
                graph.policy,
                manifest,
                EvidenceSequence.first(),
                List.of(command),
                new TestStatistics(1, 1, 0, 0, 0),
                acceptance,
                Optional.of(testReport),
                new EvidenceSummary("All tests and acceptance checks passed"),
                graph.fixture.actor(),
                publishedAt);

        seedRuntimeArtifact(
                graph,
                patch.artifactId(),
                "DIFF_PATCH",
                "text/x-diff",
                patch.sizeBytes(),
                patch.patchSha256());
        seedRuntimeArtifact(
                graph,
                commandLog.artifactId(),
                commandLog.kind().name(),
                commandLog.contentType(),
                commandLog.sizeBytes(),
                commandLog.contentHash());
        seedRuntimeArtifact(
                graph,
                testReport.artifactId(),
                testReport.kind().name(),
                testReport.contentType(),
                testReport.sizeBytes(),
                testReport.contentHash());

        DiffArtifact persistedDiff = diffs.create(diff);
        CommandEvidence persistedCommand = commands.create(command);
        TestEvidence persistedTest = tests.create(test);
        CodingCheckpoint checkpoint = checkpoint(graph, manifest, publishedAt);
        seedAgentStateSnapshot(graph, checkpoint);
        CodingCheckpoint persistedCheckpoint = checkpoints.append(checkpoint);

        assertEquals(diff.finalHash(), persistedDiff.finalHash());
        assertEquals(List.of(file), persistedDiff.manifest().files());
        assertEquals(command.evidenceHash(), persistedCommand.evidenceHash());
        assertEquals(List.of(command.reference()), persistedTest.commands());
        assertEquals(acceptance, persistedTest.acceptanceResults());
        assertEquals(checkpoint.checkpointHash(), persistedCheckpoint.checkpointHash());
        assertEquals(checkpoint.id(), checkpoints.findLatestByWorkspace(
                        graph.fixture.organizationId(), graph.activeWorkspace.id())
                .orElseThrow()
                .id());
        assertEquals(1, commands.findByTaskExecution(
                        graph.fixture.organizationId(),
                        graph.fixture.teamId(),
                        graph.fixture.projectId(),
                        graph.taskExecutionId)
                .size());
        assertEquals(1, tests.findByWorkspace(
                        graph.fixture.organizationId(),
                        graph.fixture.teamId(),
                        graph.fixture.projectId(),
                        graph.activeWorkspace.id())
                .size());

        TaskExecution recoveringExecution = mock(TaskExecution.class);
        when(recoveringExecution.scope()).thenReturn(graph.fixture.workItemScope());
        when(recoveringExecution.taskId()).thenReturn(graph.taskId);
        when(recoveringExecution.id()).thenReturn(graph.taskExecutionId);
        when(recoveringExecution.attempt()).thenReturn(1);
        when(recoveringExecution.status()).thenReturn(TaskExecutionStatus.RECOVERING);
        when(recoveringExecution.lastFencingToken()).thenReturn(Optional.of(FencingToken.initial()));
        var recoveringWorkspace = graph.activeWorkspace.beginRecovery(
                recoveringExecution,
                graph.activeWorkspace.version(),
                graph.fixture.actor(),
                UtcTimestamp.parse("2026-08-18T01:14:00Z"));
        workspaces.update(recoveringWorkspace);

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.execution_workspace_epoch "
                        + "WHERE execution_workspace_id = ?",
                Integer.class,
                graph.activeWorkspace.id().value()));
        assertEquals(1, commands.findByTaskExecution(
                        graph.fixture.organizationId(),
                        graph.fixture.teamId(),
                        graph.fixture.projectId(),
                        graph.taskExecutionId)
                .size());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE crewscope.command_evidence SET workspace_fingerprint = ? WHERE id = ?",
                "f".repeat(64),
                command.id().value()));

        var publicAttempt = codingQueries.findByExecution(
                        graph.fixture.organizationId(),
                        graph.fixture.teamId(),
                        graph.fixture.projectId(),
                        graph.taskId,
                        graph.taskExecutionId)
                .orElseThrow();
        var publicCommands = codingQueries.findCommands(
                graph.fixture.organizationId(),
                graph.fixture.teamId(),
                graph.fixture.projectId(),
                graph.taskId,
                graph.taskExecutionId,
                Optional.empty(),
                20);
        var publicTests = codingQueries.findTestEvidence(
                graph.fixture.organizationId(),
                graph.fixture.teamId(),
                graph.fixture.projectId(),
                graph.taskId,
                graph.taskExecutionId,
                Optional.empty(),
                20);
        assertEquals(List.of("docs/M4-D09.md"), publicAttempt.diffManifest().orElseThrow()
                .files().stream().map(value -> value.path()).toList());
        assertEquals(test.id().value(), publicAttempt.codingResult().orElseThrow().testEvidenceId());
        assertEquals(command.id().value(), publicCommands.items().get(0).id());
        assertEquals(List.of(command.id().value()), publicTests.items().get(0).commandEvidenceIds());
        assertEquals(AcceptanceStatus.PASSED.name(),
                publicTests.items().get(0).acceptance().get(0).status());
    }

    @Test
    void servesCodingAttemptAndEmptyEvidenceStreamsWithFixedProjectionQueries() {
        CodingPersistenceGraph graph = persistGraph("m4-a04-query");

        var attempts = codingQueries.findByTask(
                graph.fixture.organizationId(),
                graph.fixture.teamId(),
                graph.fixture.projectId(),
                graph.taskId);
        var commandsPage = codingQueries.findCommands(
                graph.fixture.organizationId(),
                graph.fixture.teamId(),
                graph.fixture.projectId(),
                graph.taskId,
                graph.taskExecutionId,
                Optional.empty(),
                20);
        var testsPage = codingQueries.findTestEvidence(
                graph.fixture.organizationId(),
                graph.fixture.teamId(),
                graph.fixture.projectId(),
                graph.taskId,
                graph.taskExecutionId,
                Optional.empty(),
                20);

        assertEquals(1, attempts.size());
        assertEquals(graph.activeWorkspace.id().value(), attempts.get(0).workspace().id());
        assertEquals("NONE", attempts.get(0).sandbox().orElseThrow().networkMode());
        assertTrue(attempts.get(0).diffManifest().isEmpty());
        assertTrue(attempts.get(0).codingResult().isEmpty());
        assertTrue(commandsPage.items().isEmpty());
        assertTrue(testsPage.items().isEmpty());
        assertTrue(codingQueries.findByTask(
                        OrganizationId.generate(),
                        graph.fixture.teamId(),
                        graph.fixture.projectId(),
                        graph.taskId)
                .isEmpty());
    }

    @Test
    void skipsWorkspaceRowsAlreadyLockedByAnotherRecoveryTransaction() throws Exception {
        CodingPersistenceGraph graph = persistGraph("workspace-lock");
        assertEquals(
                graph.activeWorkspace.id(),
                workspaces.findByWorkspaceKey(
                                graph.fixture.organizationId(),
                                new RuntimeEnvironment("test"),
                                graph.activeWorkspace.workspaceKey())
                        .orElseThrow()
                        .id());
        assertEquals(
                graph.activeWorkspace.id(),
                new TransactionTemplate(transactionManager).execute(status ->
                                workspaces.findByTaskExecutionForUpdate(
                                        graph.fixture.organizationId(),
                                        graph.fixture.teamId(),
                                        graph.fixture.projectId(),
                                        graph.taskExecutionId))
                        .orElseThrow()
                        .id());
        TaskExecution recoveringExecution = mock(TaskExecution.class);
        when(recoveringExecution.scope()).thenReturn(graph.fixture.workItemScope());
        when(recoveringExecution.taskId()).thenReturn(graph.taskId);
        when(recoveringExecution.id()).thenReturn(graph.taskExecutionId);
        when(recoveringExecution.attempt()).thenReturn(1);
        when(recoveringExecution.status()).thenReturn(TaskExecutionStatus.RECOVERING);
        when(recoveringExecution.lastFencingToken()).thenReturn(Optional.of(FencingToken.initial()));
        var recoveringWorkspace = graph.activeWorkspace.beginRecovery(
                recoveringExecution,
                0,
                graph.fixture.actor(),
                UtcTimestamp.parse("2026-08-18T01:09:00Z"));
        workspaces.update(recoveringWorkspace);
        assertThrows(OptimisticLockConflictException.class, () -> workspaces.update(recoveringWorkspace));
        assertThrows(
                IllegalTransactionStateException.class,
                () -> workspaces.findRecoveringForUpdate(
                        graph.fixture.organizationId(), new RuntimeEnvironment("test"), 10));
        assertThrows(
                IllegalTransactionStateException.class,
                () -> workspaces.findRetentionDueForUpdate(
                        graph.fixture.organizationId(),
                        new RuntimeEnvironment("test"),
                        UtcTimestamp.parse("2026-08-18T04:00:00Z"),
                        10));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstClaim = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                List<io.crewscope.domain.coding.ExecutionWorkspace> claimed =
                        workspaces.findRecoveringForUpdate(
                                graph.fixture.organizationId(), new RuntimeEnvironment("test"), 10);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release Workspace lock");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Workspace lock holder interrupted", interrupted);
                }
                return claimed;
            }));
            if (!locked.await(10, TimeUnit.SECONDS)) {
                firstClaim.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Recovery transaction did not acquire its row lock");
            }

            List<io.crewscope.domain.coding.ExecutionWorkspace> skipped =
                    new TransactionTemplate(transactionManager).execute(status ->
                            workspaces.findRecoveringForUpdate(
                                    graph.fixture.organizationId(),
                                    new RuntimeEnvironment("test"),
                                    10));

            assertTrue(skipped.isEmpty());
            release.countDown();
            assertEquals(List.of(recoveringWorkspace.id()), firstClaim.get(5, TimeUnit.SECONDS)
                    .stream()
                    .map(io.crewscope.domain.coding.ExecutionWorkspace::id)
                    .toList());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistsWriteReservationsAcrossWorkersAndRollsBackFailedTransactions() {
        CodingPersistenceGraph graph = persistGraph("write-budget");

        var initialized = writeBudgets.initialize(
                graph.activeWorkspace, graph.policy, Set.of("docs/existing.md"), 12);
        var reserved = writeBudgets.reserve(
                graph.activeWorkspace, graph.policy, Set.of("src/Main.java"), 20);
        assertEquals(2, reserved.writeOperations());
        assertEquals(32, reserved.writtenBytes());
        assertEquals(Set.of("docs/existing.md", "src/Main.java"), reserved.changedPaths());

        var restored = writeBudgets.initialize(
                graph.activeWorkspace, graph.policy, Set.of(), 0);
        assertEquals(reserved, restored);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writeBudgets.reserve(
                    graph.activeWorkspace, graph.policy, Set.of("src/RolledBack.java"), 5);
            status.setRollbackOnly();
        });
        var afterRollback = writeBudgets.initialize(
                graph.activeWorkspace, graph.policy, Set.of(), 0);
        assertEquals(reserved, afterRollback);

        TaskExecution recoveringExecution = mock(TaskExecution.class);
        when(recoveringExecution.scope()).thenReturn(graph.fixture.workItemScope());
        when(recoveringExecution.taskId()).thenReturn(graph.taskId);
        when(recoveringExecution.id()).thenReturn(graph.taskExecutionId);
        when(recoveringExecution.attempt()).thenReturn(1);
        when(recoveringExecution.status()).thenReturn(TaskExecutionStatus.RECOVERING);
        when(recoveringExecution.lastFencingToken()).thenReturn(Optional.of(FencingToken.initial()));
        workspaces.update(graph.activeWorkspace.beginRecovery(
                recoveringExecution,
                graph.activeWorkspace.version(),
                graph.fixture.actor(),
                UtcTimestamp.parse("2026-08-18T01:20:00Z")));
        assertThrows(
                WorkspaceWriteBudgetContextException.class,
                () -> writeBudgets.reserve(
                        graph.activeWorkspace, graph.policy, Set.of("src/Stale.java"), 1));
    }

    @Test
    void rollsBackDiffRootWhenNormalizedChildPublicationFails() {
        CodingPersistenceGraph graph = persistGraph("diff-rollback");
        DiffArtifact diff = oneFileDiff(graph);
        seedRuntimeArtifact(
                graph,
                diff.patchArtifact().artifactId(),
                "DIFF_PATCH",
                "text/x-diff",
                diff.patchArtifact().sizeBytes(),
                diff.patchArtifact().patchSha256());
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION crewscope.m4_d09_reject_diff_child()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'M4-D09 forced child failure';
                END
                $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER m4_d09_reject_diff_child
                BEFORE INSERT ON crewscope.diff_file_entry
                FOR EACH ROW EXECUTE FUNCTION crewscope.m4_d09_reject_diff_child()
                """);
        try {
            assertThrows(DataAccessException.class, () -> diffs.create(diff));
        } finally {
            jdbc.execute("DROP TRIGGER m4_d09_reject_diff_child ON crewscope.diff_file_entry");
            jdbc.execute("DROP FUNCTION crewscope.m4_d09_reject_diff_child()");
        }

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.diff_artifact WHERE id = ?",
                Integer.class,
                diff.id().value()));
    }

    private CodingPersistenceGraph persistGraph(String suffix) {
        Fixture fixture = seedFixture(suffix);
        RepositoryBinding persistedBinding = bindings.create(
                binding(fixture, suffix, RepositoryBindingId.generate()));
        CodingPersistenceGraph graph = CodingPersistenceGraph.create(fixture, persistedBinding);
        seedGraph(graph);
        targets.create(graph.target);
        workspaces.create(graph.activeWorkspace);
        policies.create(graph.policy);
        return graph;
    }

    private static EvidenceArtifactReference evidenceArtifact(
            EvidenceArtifactKind kind, String contentType, String content) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new EvidenceArtifactReference(
                ArtifactId.generate(),
                kind,
                contentType,
                bytes.length,
                RuntimeContentHash.sha256(content));
    }

    private static DiffArtifact oneFileDiff(CodingPersistenceGraph graph) {
        DiffFileEntry file = new DiffFileEntry(
                new DiffPath("docs/rollback.md"),
                Optional.empty(),
                DiffFileKind.ADDED,
                1,
                0,
                false,
                false,
                RuntimeContentHash.sha256("+rollback"),
                Optional.of("+rollback"));
        String patchContent = "diff --git a/docs/rollback.md b/docs/rollback.md";
        PatchArtifactReference patch = new PatchArtifactReference(
                ArtifactId.generate(),
                patchContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                RuntimeContentHash.sha256(patchContent));
        return DiffArtifact.publishFinal(
                DiffArtifactId.generate(),
                graph.finalizingWorkspace,
                graph.target,
                new RepositoryCommitId("e".repeat(40)),
                DiffManifest.initial(List.of(file)),
                patch,
                graph.fixture.actor(),
                UtcTimestamp.parse("2026-08-18T01:14:00Z"));
    }

    private static CodingCheckpoint checkpoint(
            CodingPersistenceGraph graph, DiffManifest manifest, UtcTimestamp capturedAt) {
        AgentRun run = mock(AgentRun.class);
        when(run.scope()).thenReturn(graph.fixture.workItemScope());
        when(run.taskId()).thenReturn(graph.taskId);
        when(run.executionId()).thenReturn(graph.taskExecutionId);
        when(run.id()).thenReturn(graph.agentRunId);
        when(run.runSequence()).thenReturn(1L);
        when(run.stepExecutionId()).thenReturn(Optional.empty());
        when(run.currentSegment()).thenReturn(new AgentRunSegment(
                1,
                AgentRunSegmentKind.INVOKE,
                Optional.empty(),
                AgentRunSegmentStatus.ACTIVE,
                capturedAt,
                Optional.empty()));
        AgentStateSnapshot snapshot = mock(AgentStateSnapshot.class);
        AgentStateSnapshotId snapshotId = AgentStateSnapshotId.generate();
        RuntimeContentHash snapshotHash = RuntimeContentHash.sha256("agent-state-v1");
        when(snapshot.scope()).thenReturn(graph.fixture.workItemScope());
        when(snapshot.executionId()).thenReturn(graph.taskExecutionId);
        when(snapshot.agentRunId()).thenReturn(graph.agentRunId);
        when(snapshot.status()).thenReturn(AgentStateSnapshotStatus.CURRENT);
        when(snapshot.id()).thenReturn(snapshotId);
        when(snapshot.snapshotSequence()).thenReturn(1L);
        when(snapshot.checkpointSequence()).thenReturn(1L);
        when(snapshot.contentHash()).thenReturn(snapshotHash);
        CodingCheckpointWorkState workState = new CodingCheckpointWorkState(
                "1. Inspect\n2. Persist\n3. Verify",
                List.of(new CodingCheckpointTodo(
                        "verify", CodingTodoStatus.IN_PROGRESS, "Verify persisted evidence")));
        return CodingCheckpoint.capture(
                CodingCheckpointId.generate(),
                graph.target,
                graph.activeWorkspace,
                graph.policy,
                run,
                Optional.empty(),
                workState,
                manifest,
                Optional.empty(),
                snapshot,
                graph.fixture.actor(),
                capturedAt);
    }

    private UUID seedRuntimeArtifact(
            CodingPersistenceGraph graph,
            ArtifactId artifactId,
            String kind,
            String contentType,
            long sizeBytes,
            RuntimeContentHash contentHash) {
        UUID rowId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.runtime_artifact (
                    id, artifact_id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, agent_run_id, kind, content_type,
                    size_bytes, content_hash, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rowId,
                artifactId.value(),
                graph.fixture.organizationId().value(),
                graph.fixture.teamId().value(),
                graph.fixture.workspaceId().value(),
                graph.fixture.projectId().value(),
                graph.taskId.value(),
                graph.taskExecutionId.value(),
                graph.agentRunId.value(),
                kind,
                contentType,
                sizeBytes,
                contentHash.value(),
                graph.fixture.actorId().value(),
                graph.fixture.actorId().value());
        return rowId;
    }

    private void seedAgentStateSnapshot(
            CodingPersistenceGraph graph, CodingCheckpoint checkpoint) {
        UUID artifactRowId = seedRuntimeArtifact(
                graph,
                ArtifactId.generate(),
                "AGENT_STATE_SNAPSHOT",
                AgentStateSnapshot.CONTENT_TYPE,
                128,
                checkpoint.snapshotContentHash());
        jdbc.update(
                """
                INSERT INTO crewscope.agent_state_snapshot (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, agent_run_id, runtime_session_id,
                    agent_profile_id, agent_profile_version, agent_principal_id, agent_name,
                    agent_scope_user_id, agent_scope_session_id,
                    snapshot_sequence, checkpoint_sequence,
                    runtime_artifact_id, content_hash, size_bytes, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'CodingAgent', ?, ?,
                          ?, ?, ?, ?, 128, 'CURRENT', ?, ?)
                """,
                checkpoint.agentStateSnapshotId().value(),
                graph.fixture.organizationId().value(),
                graph.fixture.teamId().value(),
                graph.fixture.workspaceId().value(),
                graph.fixture.projectId().value(),
                graph.taskId.value(),
                graph.taskExecutionId.value(),
                graph.agentRunId.value(),
                graph.runtimeSessionId,
                graph.agentProfileId.value(),
                graph.agentPrincipalId.value(),
                "crewscope:v1:user:" + graph.taskId,
                "crewscope:v1:session:" + graph.runtimeSessionId,
                checkpoint.snapshotSequence(),
                checkpoint.checkpointSequence(),
                artifactRowId,
                checkpoint.snapshotContentHash().value(),
                graph.fixture.actorId().value(),
                graph.fixture.actorId().value());
    }

    private Fixture seedFixture(String suffix) {
        Fixture fixture = new Fixture(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate(),
                PrincipalId.generate());
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                fixture.organizationId().value(), "Organization " + suffix);
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                fixture.teamId().value(), fixture.organizationId().value(), "Team " + suffix);
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                fixture.workspaceId().value(), fixture.organizationId().value(),
                fixture.teamId().value(), "Workspace " + suffix);
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                fixture.projectId().value(), fixture.organizationId().value(),
                fixture.teamId().value(), fixture.workspaceId().value(),
                "D09" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "Project " + suffix);
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, ?, 'USER', ?, 'TEAM', 'ACTIVE')
                """,
                fixture.actorId().value(), fixture.organizationId().value(), fixture.teamId().value(),
                "Actor " + suffix);
        jdbc.update(
                """
                UPDATE crewscope.work_project
                   SET created_by_principal_id = ?, updated_by_principal_id = ?
                 WHERE id = ?
                """,
                fixture.actorId().value(), fixture.actorId().value(), fixture.projectId().value());
        return fixture;
    }

    /** Seeds only the M0-M3 parent rows needed for V14 foreign-key closure. */
    private void seedGraph(CodingPersistenceGraph graph) {
        Fixture fixture = graph.fixture;
        UUID memberId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID responsibilitySnapshotId = UUID.randomUUID();
        String responsibilityHash = "b".repeat(64);

        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, owner_principal_id,
                    display_name, visibility, status
                ) VALUES (?, ?, ?, 'TEAM_AGENT', ?, 'Coding persistence agent', 'TEAM', 'ACTIVE')
                """,
                graph.agentPrincipalId.value(), fixture.organizationId().value(),
                fixture.teamId().value(), fixture.actorId().value());

        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                memberId, fixture.organizationId().value(), fixture.teamId().value(),
                fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Coding persistence', 'READY', 'MEDIUM')
                """,
                workItemId, fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(),
                "D09-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'EXECUTOR', ?, 'USER', ?, 'ACTIVE', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                assignmentId, fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), workItemId,
                fixture.actorId().value(), memberId, fixture.actorId().value(),
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                responsibilitySnapshotId, fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), workItemId,
                responsibilityHash, fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot_entry (
                    snapshot_id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, assignment_id, assignment_version, role,
                    principal_id, principal_type, member_id, assigned_at, accepted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'EXECUTOR', ?, 'USER', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                responsibilitySnapshotId, fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), workItemId,
                assignmentId, fixture.actorId().value(), memberId);
        jdbc.update(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    objective, acceptance_criteria, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?,
                          'Persist a Coding execution', '["Tests pass"]'::jsonb,
                          'CREATED', ?, ?)
                """,
                graph.taskId.value(), fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), workItemId,
                responsibilitySnapshotId, fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 3, 50, CURRENT_TIMESTAMP, 'CREATED', ?, ?)
                """,
                graph.taskExecutionId.value(), fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), graph.taskId.value(),
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', FALSE, 'ACTIVE', ?, ?)
                """,
                graph.agentProfileId.value(), fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), graph.agentPrincipalId.value(),
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.policy_snapshot (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, revision, change_reason,
                    execution_principal_id, execution_assignment_id,
                    execution_assignment_version, responsibility_snapshot_hash,
                    policy_pack_id, policy_pack_version, agent_profile_id, agent_profile_version,
                    capabilities, allowed_tools, provider_binding_ids,
                    max_tokens, max_model_calls, max_tool_calls, max_duration_seconds,
                    snapshot_hash, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'TASK_CREATED', ?, ?, 0, ?,
                          ?, 1, ?, 1, '["SANDBOX","WORKTREE"]'::jsonb,
                          '["command.mavenCompile","command.mavenTest"]'::jsonb, '[]'::jsonb,
                          120000, 40, 160, 900, ?, ?, ?, ?, ?)
                """,
                graph.policySnapshotId.value(), fixture.organizationId().value(),
                fixture.teamId().value(), fixture.workspaceId().value(), fixture.projectId().value(),
                graph.taskId.value(), graph.taskExecutionId.value(), fixture.actorId().value(),
                assignmentId, responsibilityHash, UUID.randomUUID(), graph.agentProfileId.value(),
                graph.policy.policySnapshotHash().value(),
                CodingJdbcValue.timestamp(CodingPersistenceGraph.POLICY_AT), fixture.actorId().value(),
                CodingJdbcValue.timestamp(CodingPersistenceGraph.POLICY_AT), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.execution_runtime (
                    id, organization_id, runtime_environment, runtime_key,
                    display_name, implementation_version, capabilities, languages,
                    build_systems, status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, 'test', ?, 'M4 D09 Runtime', '1', '[]'::jsonb,
                          '[]'::jsonb, '[]'::jsonb, 'ACTIVE', ?, ?)
                """,
                graph.runtimeId.value(), fixture.organizationId().value(),
                "d09-" + UUID.randomUUID().toString().substring(0, 8),
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.runtime_worker (
                    id, organization_id, runtime_environment, runtime_id, stable_key,
                    runtime_profile, capabilities, languages, build_systems,
                    max_concurrent_executions, active_executions, status,
                    last_heartbeat_at, heartbeat_sequence,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, 'test', ?, ?, 'WORKER', '[]'::jsonb, '[]'::jsonb,
                          '[]'::jsonb, 2, 0, 'ACTIVE', CURRENT_TIMESTAMP, 1, ?, ?)
                """,
                graph.workerId.value(), fixture.organizationId().value(), graph.runtimeId.value(),
                "d09-worker-" + UUID.randomUUID().toString().substring(0, 8),
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.execution_lease (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, runtime_environment,
                    runtime_id, worker_id, claim_token_hash, fencing_token,
                    phase, status, acquired_at, last_heartbeat_at, expires_at, lease_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'test', ?, ?, ?, 1,
                          'PREPARE', 'ACTIVE', ?, ?, ?, 0)
                """,
                graph.leaseId.value(), fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), graph.taskId.value(),
                graph.taskExecutionId.value(), graph.runtimeId.value(), graph.workerId.value(),
                graph.lease.claimTokenHash().value(),
                CodingJdbcValue.timestamp(CodingPersistenceGraph.ACQUIRED_AT),
                CodingJdbcValue.timestamp(CodingPersistenceGraph.ACQUIRED_AT),
                CodingJdbcValue.timestamp(CodingPersistenceGraph.RETAIN_UNTIL));
        jdbc.update(
                """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, session_purpose,
                    project_id, task_id, task_execution_id, step_execution_id,
                    agent_principal_id, agent_principal_type,
                    agent_profile_id, agent_profile_type, agent_profile_version,
                    agent_scope_user_id, agent_scope_session_id, state_reference,
                    status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'TASK', ?, ?, ?, NULL, ?, 'TEAM_AGENT',
                          ?, 'TEAM', 0, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                graph.runtimeSessionId, fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), graph.taskId.value(),
                graph.taskExecutionId.value(), graph.agentPrincipalId.value(),
                graph.agentProfileId.value(), "crewscope:v1:user:" + graph.taskId,
                "crewscope:v1:session:" + graph.runtimeSessionId,
                "crewscope:agent-state:v1:" + graph.runtimeSessionId,
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.agent_run (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, runtime_session_id,
                    agent_principal_id, agent_profile_id, agent_profile_version,
                    run_sequence, status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 1, 'RUNNING', ?, ?)
                """,
                graph.agentRunId.value(), fixture.organizationId().value(), fixture.teamId().value(),
                fixture.workspaceId().value(), fixture.projectId().value(), graph.taskId.value(),
                graph.taskExecutionId.value(), graph.runtimeSessionId,
                graph.agentPrincipalId.value(), graph.agentProfileId.value(),
                fixture.actorId().value(), fixture.actorId().value());
        jdbc.update(
                """
                INSERT INTO crewscope.agent_run_segment (
                    agent_run_id, sequence, kind, status, started_at
                ) VALUES (?, 1, 'INVOKE', 'ACTIVE', CURRENT_TIMESTAMP)
                """,
                graph.agentRunId.value());
    }

    private static RepositoryBinding binding(
            Fixture fixture, String repositoryKey, RepositoryBindingId id) {
        return RepositoryBinding.reconstitute(
                id,
                fixture.bindingScope(),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey(repositoryKey),
                new RepositoryBranchName("main"),
                RepositoryBindingStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.actorId(), CREATED_AT));
    }

    static Principal actor(Fixture fixture) {
        return Principal.create(
                fixture.actorId(),
                PrincipalScope.team(fixture.organizationId(), fixture.teamId()),
                PrincipalType.USER,
                Optional.empty(),
                "Actor",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                CREATED_AT);
    }

    record Fixture(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            PrincipalId actorId) {

        RepositoryBindingScope bindingScope() {
            return new RepositoryBindingScope(
                    organizationId, teamId, workspaceId, projectId);
        }

        io.crewscope.domain.workitem.WorkItemScope workItemScope() {
            return new io.crewscope.domain.workitem.WorkItemScope(
                    organizationId, teamId, workspaceId, projectId);
        }

        Principal actor() {
            return M4D09CodingPersistenceIntegrationTest.actor(this);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        CodingPersistenceMapper.class,
        JdbcRepositoryBindingRepositoryAdapter.class,
        JdbcCodingTargetSnapshotRepositoryAdapter.class,
        JdbcExecutionWorkspaceRepositoryAdapter.class,
        JdbcWorkspacePolicyRepositoryAdapter.class,
        JdbcCodingArtifactRepositoryAdapter.class,
        JdbcDiffArtifactRepositoryAdapter.class,
        JdbcCommandEvidenceRepositoryAdapter.class,
        JdbcTestEvidenceRepositoryAdapter.class,
        JdbcCodingCheckpointRepositoryAdapter.class,
        JdbcCodingAttemptQueryAdapter.class,
        JdbcWorkspaceWriteBudgetStore.class
    })
    static class TestApplication {

        /** Test slice supplies the same Jackson service that the server application auto-configures. */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
