package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.coding.CodingArtifactContent;
import io.crewscope.application.coding.CodingArtifactContentPort;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** M5-I06 verifies the builder against exact M4 Artifact domain coordinates. */
class ContextPackageBuilderM5I06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-23T04:00:00Z");
    private static final String PATH = "src/main/java/io/crewscope/Greeting.java";
    private static final String PATCH = """
            diff --git a/src/main/java/io/crewscope/Greeting.java b/src/main/java/io/crewscope/Greeting.java
            index 1111111..2222222 100644
            --- a/src/main/java/io/crewscope/Greeting.java
            +++ b/src/main/java/io/crewscope/Greeting.java
            @@ -13 +13 @@
            -return name.strip();
            +return name == null ? "" : name.strip();
            """;

    @Test
    void buildsHashClosedContextFromExactDiffTestCommandAndPatchFacts() {
        Fixture fixture = new Fixture(PATCH);

        ContextPackage context = fixture.builder.build(fixture.request());

        assertEquals(fixture.subject.reference(), context.subject());
        assertEquals(fixture.diff.reference(), context.diff().artifact());
        assertEquals(fixture.test.id(), context.testEvidence().id());
        assertEquals(1, context.hunks().size());
        assertEquals(PATH, context.hunks().get(0).path().value());
        assertEquals(13, context.hunks().get(0).startLine());
        assertEquals(PATCH.substring(PATCH.indexOf("@@")),
                context.hunks().get(0).patch().orElseThrow());
    }

    @Test
    void rejectsArtifactBytesOrAccessThatDriftFromM4Authority() {
        Fixture fixture = new Fixture(PATCH);
        BuildReviewContextPackageRequest outside = new BuildReviewContextPackageRequest(
                ContextPackageId.generate(), Optional.empty(), fixture.subject, fixture.diff,
                fixture.test, List.of(fixture.command), fixture.reviewer,
                new ArtifactAccessContext(
                        fixture.scope.organizationId(), fixture.actor.id(), Set.of(), Set.of()),
                fixture.actor, NOW);

        assertThrows(DomainValidationException.class, () -> fixture.builder.build(outside));

        Fixture tampered = new Fixture(PATCH.replace("name.strip()", "other.strip()"));
        when(tampered.diff.patchArtifact()).thenReturn(fixture.patchReference);
        assertThrows(DomainValidationException.class, () -> tampered.builder.build(tampered.request()));

        when(fixture.command.scope()).thenReturn(new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate()));
        assertThrows(DomainValidationException.class, () -> fixture.builder.build(fixture.request()));
    }

    @Test
    void rejectsMalformedOrContextExpandingUnifiedPatch() {
        ReviewPatchHunkParser parser = new ReviewPatchHunkParser();

        assertThrows(DomainValidationException.class, () -> parser.parse(
                "--- a/outside.java\n+++ b/outside.java\n@@ -1 +1 @@\n-old\n+new\n",
                Set.of(new io.crewscope.domain.coding.DiffPath(PATH))));
        assertThrows(DomainValidationException.class, () -> parser.parse(
                "--- a/" + PATH + "\n+++ b/" + PATH + "\n@@ -1 +1 @@\n-old\n",
                Set.of(new io.crewscope.domain.coding.DiffPath(PATH))));
    }

    @Test
    void preservesDeletedAndAddedContentThatResemblesUnifiedDiffFileHeaders() {
        ReviewPatchHunkParser parser = new ReviewPatchHunkParser();
        String patch = """
                diff --git a/%1$s b/%1$s
                --- a/%1$s
                +++ b/%1$s
                @@ -1,2 +1,2 @@
                ---- removed value
                ++++ added value
                 unchanged value
                """.formatted(PATH);

        List<io.crewscope.domain.review.ReviewDiffHunk> hunks = parser.parse(
                patch,
                Set.of(new io.crewscope.domain.coding.DiffPath(PATH)));

        assertEquals(1, hunks.size());
        assertEquals(patch.substring(patch.indexOf("@@")), hunks.get(0).patch().orElseThrow());
    }

    private static final class Fixture {
        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final Principal actor = Principal.create(
                PrincipalId.generate(), PrincipalScope.team(scope.organizationId(), scope.teamId()),
                PrincipalType.USER, Optional.empty(), "Reviewer owner", Optional.empty(),
                PrincipalVisibility.TEAM, NOW);
        private final CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
        private final DiffManifest manifest;
        private final PatchArtifactReference patchReference;
        private final DiffArtifact diff = mock(DiffArtifact.class);
        private final CommandEvidence command = mock(CommandEvidence.class);
        private final TestEvidence test = mock(TestEvidence.class);
        private final ReviewerExecutionReference reviewer;
        private final ReviewSubject subject;
        private final ContextPackageBuilder builder;

        private Fixture(String content) {
            RuntimeContentHash contentHash = RuntimeContentHash.sha256(content);
            manifest = DiffManifest.capture(
                    DiffGeneration.first(),
                    List.of(DiffFileEntry.text(
                            PATH, Optional.empty(), DiffFileKind.MODIFIED, 1, 1, false,
                            RuntimeContentHash.sha256(PATCH), Optional.of(PATCH))));
            patchReference = new PatchArtifactReference(
                    ArtifactId.generate(), PATCH.getBytes(StandardCharsets.UTF_8).length,
                    RuntimeContentHash.sha256(PATCH));
            DiffArtifactId diffId = DiffArtifactId.generate();
            TaskFactHash finalHash = TaskFactHash.sha256("final-diff");
            when(diff.id()).thenReturn(diffId);
            when(diff.scope()).thenReturn(scope);
            when(diff.taskId()).thenReturn(taskId);
            when(diff.taskExecutionId()).thenReturn(executionId);
            when(diff.attempt()).thenReturn(1);
            when(diff.codingTarget()).thenReturn(target);
            when(diff.baselineCommit()).thenReturn(new RepositoryCommitId("a".repeat(40)));
            when(diff.deliveryCommit()).thenReturn(new RepositoryCommitId("b".repeat(40)));
            when(diff.manifest()).thenReturn(manifest);
            when(diff.patchArtifact()).thenReturn(new PatchArtifactReference(
                    ArtifactId.generate(), content.getBytes(StandardCharsets.UTF_8).length,
                    contentHash));
            when(diff.finalHash()).thenReturn(finalHash);
            when(diff.reference()).thenReturn(new DiffArtifactReference(diffId, finalHash));

            CommandEvidenceReference commandReference = new CommandEvidenceReference(
                    CommandEvidenceId.generate(), EvidenceSequence.first(),
                    TaskFactHash.sha256("command"), Optional.empty());
            when(command.reference()).thenReturn(commandReference);
            when(command.scope()).thenReturn(scope);
            when(command.taskId()).thenReturn(taskId);
            when(command.taskExecutionId()).thenReturn(executionId);
            when(command.attempt()).thenReturn(1);
            when(command.codingTarget()).thenReturn(target);
            CommandSpec commandSpec = mock(CommandSpec.class);
            when(commandSpec.commandKind()).thenReturn(CommandKind.TEST);
            when(command.commandSpec()).thenReturn(commandSpec);
            when(command.termination()).thenReturn(CommandTermination.EXITED);
            when(command.exitCode()).thenReturn(Optional.of(0));
            when(command.summary()).thenReturn(new EvidenceSummary("All tests passed"));

            when(test.id()).thenReturn(TestEvidenceId.generate());
            when(test.scope()).thenReturn(scope);
            when(test.taskId()).thenReturn(taskId);
            when(test.taskExecutionId()).thenReturn(executionId);
            when(test.attempt()).thenReturn(1);
            when(test.codingTarget()).thenReturn(target);
            when(test.diffGeneration()).thenReturn(manifest.generation());
            when(test.diffManifestHash()).thenReturn(manifest.contentHash());
            when(test.commands()).thenReturn(List.of(commandReference));
            when(test.acceptanceResults()).thenReturn(List.of(new AcceptanceResult(
                    1, "Handle null name", AcceptanceStatus.PASSED,
                    List.of(commandReference), new EvidenceSummary("Criterion passed"))));
            when(test.evidenceHash()).thenReturn(TaskFactHash.sha256("test-evidence"));

            ReviewDiffReference diffReference = ReviewDiffReference.from(diff);
            subject = ReviewSubject.codeChange(
                    ReviewSubjectId.generate(), scope, taskId, executionId, 1,
                    diffReference, actor, NOW);
            reviewer = new ReviewerExecutionReference(
                    scope, taskId, executionId, AgentProfileId.generate(), 1,
                    PrincipalId.generate(), Optional.of(TeamMemberId.generate()),
                    Optional.of(TeamMemberId.generate()), ReviewerRelationship.INDEPENDENT,
                    AgentTemplateVersion.of("reviewer", 1), AgentTemplateHash.sha256("template"),
                    new AgentConfigurationRevision(1),
                    new AgentConfigurationHash(TaskFactHash.sha256("configuration").value()),
                    PolicySnapshotId.generate(), 1, TaskFactHash.sha256("policy"));
            CodingArtifactContentPort port = new CodingArtifactContentPort() {
                @Override
                public CodingArtifactContent readPatch(
                        DiffArtifact artifact,
                        ArtifactAccessContext access,
                        Optional<io.crewscope.application.artifact.ArtifactByteRange> range) {
                    return new ByteContent(artifact.patchArtifact(), content);
                }

                @Override
                public CodingArtifactContent readBuildLog(
                        CommandEvidence evidence,
                        ArtifactAccessContext access,
                        Optional<io.crewscope.application.artifact.ArtifactByteRange> range) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CodingArtifactContent readTestReport(
                        TestEvidence evidence,
                        ArtifactAccessContext access,
                        Optional<io.crewscope.application.artifact.ArtifactByteRange> range) {
                    throw new UnsupportedOperationException();
                }
            };
            builder = new ContextPackageBuilder(port);
        }

        private BuildReviewContextPackageRequest request() {
            return new BuildReviewContextPackageRequest(
                    ContextPackageId.generate(), Optional.empty(), subject, diff, test,
                    List.of(command), reviewer,
                    new ArtifactAccessContext(
                            scope.organizationId(), actor.id(), Set.of(scope.teamId()),
                            Set.of(scope.workspaceId())),
                    actor, NOW);
        }
    }

    private static final class ByteContent implements CodingArtifactContent {
        private final PatchArtifactReference reference;
        private final byte[] bytes;

        private ByteContent(PatchArtifactReference reference, String content) {
            this.reference = reference;
            this.bytes = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override public ArtifactId artifactId() { return reference.artifactId(); }
        @Override public String contentType() { return PatchArtifactReference.CONTENT_TYPE; }
        @Override public RuntimeContentHash contentHash() { return reference.patchSha256(); }
        @Override public long totalSize() { return bytes.length; }
        @Override public long startInclusive() { return 0; }
        @Override public long endExclusive() { return bytes.length; }
        @Override public InputStream stream() { return new ByteArrayInputStream(bytes); }
        @Override public void close() throws IOException {}
    }
}
