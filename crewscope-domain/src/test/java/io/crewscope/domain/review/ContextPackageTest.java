package io.crewscope.domain.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextPackageTest {

    @Test
    void closesOnlyBoundedDiffTestAcceptanceTemplateConfigurationAndPolicyFacts() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ContextPackage context = fixture.context;

        assertEquals(ContextPackage.SCHEMA_VERSION, 1);
        assertEquals(fixture.subject.reference(), context.subject());
        assertEquals(fixture.diff.artifact(), context.diff().artifact());
        assertEquals(fixture.diff.generation(), context.testEvidence().diffGeneration());
        assertEquals(fixture.diff.manifestHash(), context.testEvidence().diffManifestHash());
        assertEquals(1, context.hunks().size());
        assertEquals(1, context.testEvidence().commands().size());
        assertEquals(1, context.testEvidence().acceptanceResults().size());
        assertTrue(context.hunks().stream().mapToInt(ReviewDiffHunk::patchBytes).sum()
                <= ContextPackage.MAX_PATCH_BYTES);
        assertFalse(context.toString().contains("credential"));

        ContextPackage restored = ContextPackage.reconstitute(
                context.id(),
                context.version(),
                context.parentPackageId(),
                fixture.subject,
                context.diff(),
                context.testEvidence(),
                context.hunks(),
                context.reviewer(),
                context.contextHash(),
                context.audit());
        assertEquals(context.reference(), restored.reference());
    }

    @Test
    void rejectsTamperedHunkAndContextHashes() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();

        assertThrows(DomainValidationException.class, () -> ReviewSubject.reconstitute(
                fixture.subject.id(),
                fixture.subject.type(),
                fixture.subject.scope(),
                fixture.subject.taskId(),
                fixture.subject.taskExecutionId(),
                fixture.subject.attempt(),
                fixture.subject.diff(),
                TaskFactHash.sha256("tampered-subject"),
                fixture.subject.audit()));
        assertThrows(DomainValidationException.class, () -> new ReviewDiffHunk(
                new DiffPath("src/main/java/io/crewscope/Greeting.java"),
                14,
                14,
                RuntimeContentHash.sha256("different"),
                Optional.of("+return value;\n")));
        assertThrows(DomainValidationException.class, () -> ContextPackage.reconstitute(
                fixture.context.id(),
                fixture.context.version(),
                fixture.context.parentPackageId(),
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                fixture.context.hunks(),
                fixture.reviewer,
                TaskFactHash.sha256("tampered"),
                fixture.context.audit()));
    }

    @Test
    void rejectsCrossTaskAttemptScopeAndDiffEvidenceLineage() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewTestEvidenceReference wrongTask = copyEvidence(
                fixture, fixture.scope, TaskId.generate(), fixture.executionId, 1);
        ReviewTestEvidenceReference wrongAttempt = copyEvidence(
                fixture, fixture.scope, fixture.taskId, fixture.executionId, 2);
        WorkItemScope otherScope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        ReviewTestEvidenceReference wrongScope = copyEvidence(
                fixture, otherScope, fixture.taskId, fixture.executionId, 1);
        ReviewTestEvidenceReference wrongExecution = copyEvidence(
                fixture, fixture.scope, fixture.taskId, TaskExecutionId.generate(), 1);

        for (ReviewTestEvidenceReference mismatched :
                List.of(wrongTask, wrongAttempt, wrongScope, wrongExecution)) {
            assertThrows(DomainValidationException.class, () -> fixture.context(
                    ContextPackageId.generate(),
                    fixture.subject,
                    fixture.diff,
                    mismatched,
                    fixture.reviewer));
        }

        ReviewDiffReference changedDiff = fixture.diff("diff-2", 2);
        assertThrows(DomainValidationException.class, () -> fixture.context(
                ContextPackageId.generate(),
                fixture.subject,
                changedDiff,
                fixture.testEvidence,
                fixture.reviewer));
    }

    private static ReviewTestEvidenceReference copyEvidence(
            ReviewDomainFixture fixture,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            int attempt) {
        return new ReviewTestEvidenceReference(
                scope,
                taskId,
                executionId,
                attempt,
                fixture.codingTarget,
                fixture.testEvidence.id(),
                fixture.testEvidence.evidenceHash(),
                fixture.testEvidence.diffGeneration(),
                fixture.testEvidence.diffManifestHash(),
                fixture.testEvidence.commands(),
                fixture.testEvidence.acceptanceResults());
    }

    @Test
    void versionsContextsOnlyWhenAuthorityOrBoundedHunksChange() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();

        assertThrows(DomainValidationException.class, () -> ContextPackage.successor(
                ContextPackageId.generate(),
                fixture.context,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                fixture.context.hunks(),
                fixture.reviewer,
                fixture.actor,
                ReviewDomainFixture.LATER));

        ContextPackage changed = fixture.successor(
                fixture.context,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                fixture.reviewer,
                "+return name == null ? \"\" : name.trim();\n");
        assertEquals(2, changed.version());
        assertEquals(Optional.of(fixture.context.id()), changed.parentPackageId());
        assertFalse(fixture.context.contextHash().equals(changed.contextHash()));
    }
}
