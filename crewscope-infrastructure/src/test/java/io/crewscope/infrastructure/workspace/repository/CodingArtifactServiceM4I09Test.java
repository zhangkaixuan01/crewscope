package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactByteRange;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.artifact.ArtifactStoreError;
import io.crewscope.application.artifact.ArtifactStoreException;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.artifact.FilesystemArtifactStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Writer/Reader, retention, deletion, safe-summary and metadata-closure coverage for M4-I09. */
class CodingArtifactServiceM4I09Test {

    private static final Instant NOW = Instant.parse("2026-08-19T02:00:00Z");
    private static final byte[] REPORT =
            "token=top-secret\npassed=1\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private WorkItemScope scope;
    private TaskExecutionId taskExecutionId;
    private PrincipalId actorId;
    private ExecutionWorkspace workspace;
    private Principal actor;
    private ArtifactAccessContext access;
    private ArtifactMutationContext mutation;
    private CodingArtifactProperties properties;
    private FilesystemArtifactStore store;
    private CodingArtifactPublisher publisher;

    @BeforeEach
    void setUp() {
        scope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        taskExecutionId = TaskExecutionId.generate();
        actorId = PrincipalId.generate();
        workspace = mock(ExecutionWorkspace.class);
        when(workspace.id()).thenReturn(ExecutionWorkspaceId.generate());
        when(workspace.scope()).thenReturn(scope);
        when(workspace.taskExecutionId()).thenReturn(taskExecutionId);
        actor = mock(Principal.class);
        when(actor.id()).thenReturn(actorId);
        when(actor.scope()).thenReturn(PrincipalScope.team(scope.organizationId(), scope.teamId()));
        when(actor.canAct()).thenReturn(true);
        access = new ArtifactAccessContext(
                scope.organizationId(), actorId, Set.of(scope.teamId()), Set.of(scope.workspaceId()));
        mutation = new ArtifactMutationContext(scope.organizationId(), actorId);
        properties = new CodingArtifactProperties();
        properties.setRetention(Duration.ofHours(1));
        properties.setMaximumArtifactBytes(1024);
        properties.setMaximumRangeBytes(8);
        store = new FilesystemArtifactStore(
                root,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        publisher = new CodingArtifactPublisher(store, properties);
    }

    @Test
    void publishesStablePatchAndTestReportWithOneRetentionPolicy() {
        PatchArtifactWriter patches = new PatchArtifactWriter(publisher);
        var patch = patches.write(
                workspace,
                actor,
                new WorkspaceDiffSnapshot(mock(DiffManifest.class), "diff --git a/a b/a\n"));
        TestReportArtifactWriter reports = new TestReportArtifactWriter(publisher);
        EvidenceArtifactReference first = reports.write(
                workspace,
                actor,
                new EvidenceSequence(2),
                "application/xml;charset=utf-8",
                REPORT);
        EvidenceArtifactReference retried = reports.write(
                workspace,
                actor,
                new EvidenceSequence(2),
                "application/xml;charset=utf-8",
                REPORT);

        assertEquals(first, retried);
        assertEquals(CodingArtifactIds.patch(workspace.id()), patch.artifactId());
        assertEquals(CodingArtifactIds.testReport(workspace.id(), new EvidenceSequence(2)),
                first.artifactId());
        assertEquals(
                Optional.of(UtcTimestamp.from(NOW.plus(Duration.ofHours(1)))),
                store.head(first.artifactId(), access).orElseThrow().retentionUntil());
        assertEquals(
                Optional.of(UtcTimestamp.from(NOW.plus(Duration.ofHours(1)))),
                store.head(patch.artifactId(), access).orElseThrow().retentionUntil());
        assertEquals(
                CodingArtifactAvailability.EXPIRED,
                new CodingArtifactReader(
                                store,
                                properties,
                                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC))
                        .summarizeTestReport(evidence(first), access)
                        .availability());

        assertEquals(
                ArtifactStoreError.CONFLICT,
                assertThrows(
                                ArtifactStoreException.class,
                                () -> reports.write(
                                        workspace,
                                        actor,
                                        new EvidenceSequence(2),
                                        "application/xml;charset=utf-8",
                                        "different".getBytes(StandardCharsets.UTF_8)))
                        .error());
    }

    @Test
    void readsBoundedRangeAndGeneratesContentFreePublicSummary() throws Exception {
        EvidenceArtifactReference reference = new TestReportArtifactWriter(publisher).write(
                workspace,
                actor,
                EvidenceSequence.first(),
                "application/xml;charset=utf-8",
                REPORT);
        TestEvidence evidence = evidence(reference);
        CodingArtifactReader reader = new CodingArtifactReader(
                store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        CodingArtifactSummary summary = reader.summarizeTestReport(evidence, access);

        assertEquals(CodingArtifactAvailability.ACTIVE, summary.availability());
        assertEquals(REPORT.length, summary.sizeBytes());
        assertFalse(summary.toString().contains("top-secret"));
        assertFalse(summary.toString().contains(root.toString()));
        assertFalse(summary.toString().contains(actorId.toString()));
        assertEquals(
                CodingArtifactError.SIZE_LIMIT_EXCEEDED,
                assertThrows(
                                CodingArtifactException.class,
                                () -> reader.readTestReport(evidence, access, Optional.empty()))
                        .error());
        assertEquals(
                CodingArtifactError.RANGE_NOT_SATISFIABLE,
                assertThrows(
                                CodingArtifactException.class,
                                () -> reader.readTestReport(
                                        evidence,
                                        access,
                                        Optional.of(new ArtifactByteRange(REPORT.length, REPORT.length + 1))))
                        .error());
        try (CodingArtifactReadResult result = reader.readTestReport(
                evidence, access, Optional.of(new ArtifactByteRange(6, 14)))) {
            assertTrue(result.partial());
            assertEquals(REPORT.length, result.totalSize());
            assertArrayEquals("top-secr".getBytes(StandardCharsets.UTF_8),
                    result.stream().readAllBytes());
            assertEquals(-1, result.stream().read());
        }
    }

    @Test
    void rejectsRelationalMetadataMismatchBeforeReturningAnyBytes() {
        EvidenceArtifactReference stored = new TestReportArtifactWriter(publisher).write(
                workspace,
                actor,
                EvidenceSequence.first(),
                "application/xml;charset=utf-8",
                REPORT);
        EvidenceArtifactReference forged = new EvidenceArtifactReference(
                stored.artifactId(),
                EvidenceArtifactKind.TEST_REPORT,
                stored.contentType(),
                stored.sizeBytes() + 1,
                stored.contentHash());
        CodingArtifactReader reader = new CodingArtifactReader(
                store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        CodingArtifactException failure = assertThrows(
                CodingArtifactException.class,
                () -> reader.readTestReport(
                        evidence(forged), access, Optional.of(new ArtifactByteRange(0, 1))));

        assertEquals(CodingArtifactError.METADATA_MISMATCH, failure.error());
        assertFalse(failure.getMessage().contains(root.toString()));
        assertFalse(failure.getMessage().contains("top-secret"));
    }

    @Test
    void tombstonesIdempotentlyBlocksReadsAndPurgesOnlyAfterRetention() {
        EvidenceArtifactReference reference = new TestReportArtifactWriter(publisher).write(
                workspace,
                actor,
                EvidenceSequence.first(),
                "application/xml;charset=utf-8",
                REPORT);
        TestEvidence evidence = evidence(reference);
        CodingArtifactLifecycle lifecycle = new CodingArtifactLifecycle(store);
        CodingArtifactReader reader = new CodingArtifactReader(
                store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        var first = lifecycle.tombstoneTestReport(
                evidence,
                access,
                mutation,
                ArtifactTombstoneReason.USER_REQUESTED,
                Optional.of("evidence removed"));
        var retried = lifecycle.tombstoneTestReport(
                evidence,
                access,
                mutation,
                ArtifactTombstoneReason.USER_REQUESTED,
                Optional.of(" evidence removed "));

        assertEquals(first, retried);
        assertEquals(
                CodingArtifactAvailability.TOMBSTONED,
                reader.summarizeTestReport(evidence, access).availability());
        assertEquals(
                CodingArtifactError.CONTENT_UNAVAILABLE,
                assertThrows(
                                CodingArtifactException.class,
                                () -> reader.readTestReport(
                                        evidence,
                                        access,
                                        Optional.of(new ArtifactByteRange(0, 1))))
                        .error());
        assertTrue(lifecycle.purge(new ArtifactPurgeRequest(
                        UtcTimestamp.from(NOW.plus(Duration.ofMinutes(30))), 10))
                .isEmpty());
        assertEquals(
                java.util.List.of(reference.artifactId()),
                lifecycle.purge(new ArtifactPurgeRequest(
                        UtcTimestamp.from(NOW.plus(Duration.ofHours(1))), 10)));
        assertTrue(store.head(reference.artifactId(), access).isEmpty());
    }

    private TestEvidence evidence(EvidenceArtifactReference reference) {
        TestEvidence evidence = mock(TestEvidence.class);
        when(evidence.testReport()).thenReturn(Optional.of(reference));
        when(evidence.scope()).thenReturn(scope);
        when(evidence.taskExecutionId()).thenReturn(taskExecutionId);
        when(evidence.audit()).thenReturn(AuditMetadata.createdBy(actorId, UtcTimestamp.from(NOW)));
        return evidence;
    }
}
