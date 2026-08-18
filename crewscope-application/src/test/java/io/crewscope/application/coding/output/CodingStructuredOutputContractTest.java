package io.crewscope.application.coding.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestStatistics;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingStructuredOutputContractTest {

    private final CodingOutputValidator validator = new CodingOutputValidator(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void publishesRecursivelyClosedVersionedSchemasWithoutSuccessClaim() {
        List.of(
                        CodingStructuredOutputSpecs.REPOSITORY_ANALYSIS,
                        CodingStructuredOutputSpecs.DIFF_MANIFEST,
                        CodingStructuredOutputSpecs.TEST_EVIDENCE,
                        CodingStructuredOutputSpecs.CODE_CHANGE_RESULT)
                .forEach(spec -> assertClosed(spec.strictJsonSchema().orElseThrow()));

        Map<String, Object> resultProperties = properties(
                CodingStructuredOutputSpecs.CODE_CHANGE_RESULT.strictJsonSchema().orElseThrow());
        assertFalse(resultProperties.containsKey("success"));
        assertFalse(resultProperties.containsKey("succeeded"));
        assertThrows(UnsupportedOperationException.class,
                () -> CodingStructuredOutputSpecs.CODE_CHANGE_RESULT.strictJsonSchema()
                        .orElseThrow().put("unknown", true));
    }

    @Test
    void validatesAnalysisPathsVersionDuplicatesAndStableHash() {
        Facts facts = Facts.create();
        RepositoryAnalysisV1 analysis = facts.analysis();

        TaskFactHash first = validator.validateRepositoryAnalysis(analysis, facts.target);
        assertEquals(first, validator.validateRepositoryAnalysis(analysis, facts.target));
        assertThrows(UnsupportedOperationException.class,
                () -> analysis.relevantPaths().add("docs/new.md"));
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateRepositoryAnalysis(
                        new RepositoryAnalysisV1("2", analysis.codingTargetSnapshotId(),
                                analysis.codingTargetRevision(), analysis.codingTargetHash(),
                                analysis.modules(), analysis.buildEntries(), analysis.relevantPaths(),
                                analysis.risks(), analysis.plan()), facts.target));
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateRepositoryAnalysis(
                        new RepositoryAnalysisV1("1", analysis.codingTargetSnapshotId(),
                                analysis.codingTargetRevision(), analysis.codingTargetHash(),
                                analysis.modules(), analysis.buildEntries(),
                                List.of("outside/Secret.java"), analysis.risks(), analysis.plan()),
                        facts.target));
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateRepositoryAnalysis(
                        new RepositoryAnalysisV1("1", analysis.codingTargetSnapshotId(),
                                analysis.codingTargetRevision(), analysis.codingTargetHash(),
                                analysis.modules(), analysis.buildEntries(),
                                List.of("docs/readme.md", "docs/readme.md"),
                                analysis.risks(), analysis.plan()), facts.target));
    }

    @Test
    void comparesDiffAndEvidenceAgainstExactAuthorityOrderAndHashes() {
        Facts facts = Facts.create();
        DiffManifestV1 diff = facts.diffOutput();
        TestEvidenceV1 evidence = facts.evidenceOutput();

        validator.validateDiffManifest(diff, facts.target, facts.workspace, facts.diffArtifact);
        validator.validateTestEvidence(
                evidence, facts.target, facts.workspace, facts.policy, facts.testEvidence);

        List<DiffFileV1> alteredFiles = new ArrayList<>(diff.files());
        DiffFileV1 file = alteredFiles.get(0);
        alteredFiles.set(0, new DiffFileV1(file.path(), file.oldPath(), file.kind(),
                file.additions() + 1, file.deletions(), file.binary(), file.patchTruncated(),
                file.patchSha256()));
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateDiffManifest(
                        new DiffManifestV1(diff.schemaVersion(), diff.executionWorkspaceId(),
                                diff.workspaceFingerprint(), diff.codingTargetSnapshotId(),
                                diff.codingTargetRevision(), diff.codingTargetHash(),
                                diff.diffGeneration(), diff.manifestHash(), diff.fileCount(),
                                diff.additions(), diff.deletions(), alteredFiles),
                        facts.target, facts.workspace, facts.diffArtifact));

        List<CommandEvidenceReferenceV1> wrongCommands = List.of(
                new CommandEvidenceReferenceV1(evidence.commands().get(0).commandEvidenceId(),
                        evidence.commands().get(0).sequence() + 1,
                        evidence.commands().get(0).evidenceHash()));
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateTestEvidence(
                        new TestEvidenceV1(evidence.schemaVersion(), evidence.testEvidenceId(),
                                evidence.evidenceHash(), evidence.executionWorkspaceId(),
                                evidence.workspaceFingerprint(), evidence.codingTargetSnapshotId(),
                                evidence.codingTargetRevision(), evidence.codingTargetHash(),
                                evidence.diffGeneration(), evidence.diffManifestHash(),
                                evidence.workspacePolicyId(), evidence.workspacePolicyHash(),
                                evidence.evidenceSequence(), wrongCommands, evidence.statistics(),
                                evidence.acceptanceResults(), evidence.summaryHash()),
                        facts.target, facts.workspace, facts.policy, facts.testEvidence));
    }

    @Test
    void finalResultFailsClosedWhenEvidenceIsFailedOrAnyReferenceIsForged() {
        Facts facts = Facts.create();
        CodeChangeResultV1 result = facts.resultOutput();

        validator.validateCodeChangeResult(result, facts.analysis(), facts.target,
                facts.workspace, facts.diffArtifact, facts.testEvidence);
        when(facts.testEvidence.succeeded()).thenReturn(false);
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateCodeChangeResult(result, facts.analysis(), facts.target,
                        facts.workspace, facts.diffArtifact, facts.testEvidence));

        when(facts.testEvidence.succeeded()).thenReturn(true);
        CodeChangeResultV1 forged = new CodeChangeResultV1(
                result.schemaVersion(), result.executionWorkspaceId(), result.workspaceFingerprint(),
                result.codingTargetSnapshotId(), result.codingTargetRevision(), result.codingTargetHash(),
                result.repositoryAnalysisHash(), result.diffArtifactId(), "f".repeat(64),
                result.testEvidenceId(), result.testEvidenceHash(), result.changeSummary(),
                result.limitations(), result.risks());
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateCodeChangeResult(forged, facts.analysis(), facts.target,
                        facts.workspace, facts.diffArtifact, facts.testEvidence));

        when(facts.testEvidence.diffManifestHash())
                .thenReturn(RuntimeContentHash.sha256("stale-tested-diff"));
        assertThrows(CodingOutputValidationException.class,
                () -> validator.validateCodeChangeResult(result, facts.analysis(), facts.target,
                        facts.workspace, facts.diffArtifact, facts.testEvidence));
    }

    @SuppressWarnings("unchecked")
    private static void assertClosed(Map<String, Object> schema) {
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(new ArrayList<>(properties.keySet()), schema.get("required"));
        properties.values().forEach(value -> {
            Map<String, Object> child = (Map<String, Object>) value;
            if ("object".equals(child.get("type"))) {
                assertClosed(child);
            } else if ("array".equals(child.get("type"))) {
                Map<String, Object> items = (Map<String, Object>) child.get("items");
                if ("object".equals(items.get("type"))) {
                    assertClosed(items);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static final class Facts {
        final WorkItemScope scope = mock(WorkItemScope.class);
        final TaskId taskId = TaskId.generate();
        final TaskExecutionId executionId = TaskExecutionId.generate();
        final CodingTargetSnapshotId targetId = CodingTargetSnapshotId.generate();
        final TaskFactHash targetHash = TaskFactHash.sha256("target");
        final CodingTargetSnapshotReference targetReference =
                new CodingTargetSnapshotReference(targetId, 1, targetHash);
        final CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
        final ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        final ExecutionWorkspaceFingerprint fingerprint =
                new ExecutionWorkspaceFingerprint(TaskFactHash.sha256("workspace").toString());
        final ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        final WorkspacePolicyId policyId = WorkspacePolicyId.generate();
        final TaskFactHash policyHash = TaskFactHash.sha256("policy");
        final WorkspacePolicyReference policyReference =
                new WorkspacePolicyReference(policyId, policyHash);
        final WorkspacePolicy policy = mock(WorkspacePolicy.class);
        final RuntimeContentHash patchHash = RuntimeContentHash.sha256("patch");
        final DiffFileEntry diffFile = DiffFileEntry.text(
                "docs/readme.md", Optional.empty(), DiffFileKind.MODIFIED,
                2, 1, false, patchHash, Optional.of("patch"));
        final DiffManifest manifest = DiffManifest.initial(List.of(diffFile));
        final DiffArtifactId diffId = DiffArtifactId.generate();
        final TaskFactHash diffHash = TaskFactHash.sha256("diff-artifact");
        final DiffArtifact diffArtifact = mock(DiffArtifact.class);
        final CommandEvidenceReference command = new CommandEvidenceReference(
                CommandEvidenceId.generate(), EvidenceSequence.first(),
                TaskFactHash.sha256("command"), Optional.empty());
        final TestStatistics statistics = new TestStatistics(2, 2, 0, 0, 0);
        final AcceptanceResult acceptance = new AcceptanceResult(
                1, "Unit tests pass", AcceptanceStatus.PASSED, List.of(command),
                new EvidenceSummary("verified"));
        final EvidenceSummary evidenceSummary = new EvidenceSummary("all checks passed");
        final TestEvidenceId evidenceId = TestEvidenceId.generate();
        final TaskFactHash evidenceHash = TaskFactHash.sha256("test-evidence");
        final TestEvidence testEvidence = mock(TestEvidence.class);

        static Facts create() {
            Facts facts = new Facts();
            when(facts.target.id()).thenReturn(facts.targetId);
            when(facts.target.revision()).thenReturn(1L);
            when(facts.target.snapshotHash()).thenReturn(facts.targetHash);
            when(facts.target.reference()).thenReturn(facts.targetReference);
            when(facts.target.scope()).thenReturn(facts.scope);
            when(facts.target.taskId()).thenReturn(facts.taskId);
            when(facts.target.allowedPaths())
                    .thenReturn(CodingTargetAllowedPaths.of("crewscope-domain", "docs"));

            when(facts.workspace.id()).thenReturn(facts.workspaceId);
            when(facts.workspace.fingerprint()).thenReturn(facts.fingerprint);
            when(facts.workspace.scope()).thenReturn(facts.scope);
            when(facts.workspace.taskId()).thenReturn(facts.taskId);
            when(facts.workspace.taskExecutionId()).thenReturn(facts.executionId);
            when(facts.workspace.attempt()).thenReturn(1);
            when(facts.workspace.codingTarget()).thenReturn(facts.targetReference);

            when(facts.policy.id()).thenReturn(facts.policyId);
            when(facts.policy.policyHash()).thenReturn(facts.policyHash);
            when(facts.policy.reference()).thenReturn(facts.policyReference);
            when(facts.policy.scope()).thenReturn(facts.scope);
            when(facts.policy.taskId()).thenReturn(facts.taskId);
            when(facts.policy.taskExecutionId()).thenReturn(facts.executionId);
            when(facts.policy.attempt()).thenReturn(1);
            when(facts.policy.codingTarget()).thenReturn(facts.targetReference);

            when(facts.diffArtifact.id()).thenReturn(facts.diffId);
            when(facts.diffArtifact.finalHash()).thenReturn(facts.diffHash);
            when(facts.diffArtifact.manifest()).thenReturn(facts.manifest);
            when(facts.diffArtifact.scope()).thenReturn(facts.scope);
            when(facts.diffArtifact.taskId()).thenReturn(facts.taskId);
            when(facts.diffArtifact.taskExecutionId()).thenReturn(facts.executionId);
            when(facts.diffArtifact.attempt()).thenReturn(1);
            when(facts.diffArtifact.executionWorkspaceId()).thenReturn(facts.workspaceId);
            when(facts.diffArtifact.codingTarget()).thenReturn(facts.targetReference);

            when(facts.testEvidence.id()).thenReturn(facts.evidenceId);
            when(facts.testEvidence.evidenceHash()).thenReturn(facts.evidenceHash);
            when(facts.testEvidence.sequence()).thenReturn(EvidenceSequence.first());
            when(facts.testEvidence.scope()).thenReturn(facts.scope);
            when(facts.testEvidence.taskId()).thenReturn(facts.taskId);
            when(facts.testEvidence.taskExecutionId()).thenReturn(facts.executionId);
            when(facts.testEvidence.attempt()).thenReturn(1);
            when(facts.testEvidence.executionWorkspaceId()).thenReturn(facts.workspaceId);
            when(facts.testEvidence.workspaceFingerprint()).thenReturn(facts.fingerprint);
            when(facts.testEvidence.codingTarget()).thenReturn(facts.targetReference);
            when(facts.testEvidence.diffGeneration()).thenReturn(facts.manifest.generation());
            when(facts.testEvidence.diffManifestHash()).thenReturn(facts.manifest.contentHash());
            when(facts.testEvidence.workspacePolicy()).thenReturn(facts.policyReference);
            when(facts.testEvidence.commands()).thenReturn(List.of(facts.command));
            when(facts.testEvidence.statistics()).thenReturn(facts.statistics);
            when(facts.testEvidence.acceptanceResults()).thenReturn(List.of(facts.acceptance));
            when(facts.testEvidence.summary()).thenReturn(facts.evidenceSummary);
            when(facts.testEvidence.succeeded()).thenReturn(true);
            return facts;
        }

        RepositoryAnalysisV1 analysis() {
            return new RepositoryAnalysisV1("1", targetId.toString(), 1, targetHash.toString(),
                    List.of("domain"), List.of("crewscope-domain/pom.xml"),
                    List.of("docs/readme.md"), List.of(), List.of("Update documentation"));
        }

        DiffManifestV1 diffOutput() {
            return new DiffManifestV1("1", workspaceId.toString(), fingerprint.toString(),
                    targetId.toString(), 1, targetHash.toString(), manifest.generation().value(),
                    manifest.contentHash().toString(), manifest.fileCount(), manifest.additions(),
                    manifest.deletions(), List.of(new DiffFileV1(
                            diffFile.path().value(), "", diffFile.kind().name(), diffFile.additions(),
                            diffFile.deletions(), diffFile.binary(), diffFile.patchTruncated(),
                            diffFile.patchSha256().toString())));
        }

        TestEvidenceV1 evidenceOutput() {
            CommandEvidenceReferenceV1 commandOutput = new CommandEvidenceReferenceV1(
                    command.id().toString(), command.sequence().value(), command.evidenceHash().toString());
            return new TestEvidenceV1("1", evidenceId.toString(), evidenceHash.toString(),
                    workspaceId.toString(), fingerprint.toString(), targetId.toString(), 1,
                    targetHash.toString(), manifest.generation().value(),
                    manifest.contentHash().toString(), policyId.toString(),
                    policyHash.toString(), 1,
                    List.of(commandOutput), new TestStatisticsV1(2, 2, 0, 0, 0),
                    List.of(new AcceptanceResultV1(1, acceptance.criterion(),
                            acceptance.status().name(), List.of(commandOutput),
                            TaskFactHash.sha256(acceptance.summary().value()).toString())),
                    TaskFactHash.sha256(evidenceSummary.value()).toString());
        }

        CodeChangeResultV1 resultOutput() {
            return new CodeChangeResultV1("1", workspaceId.toString(), fingerprint.toString(),
                    targetId.toString(), 1, targetHash.toString(),
                    CodingOutputValidator.hashRepositoryAnalysis(analysis()).toString(),
                    diffId.toString(), diffHash.toString(), evidenceId.toString(),
                    evidenceHash.toString(), List.of("Updated documentation"), List.of(), List.of());
        }
    }
}
