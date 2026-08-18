package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceFailureClassification;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestStatistics;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TestEvidenceTest {

    private static final UtcTimestamp PUBLISHED_AT =
            UtcTimestamp.parse("2026-08-17T09:14:00Z");

    @Test
    void derivesSuccessfulVerdictFromCommandsReportTestsAndEveryAcceptanceCriterion() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        CommandEvidence command = successfulCommand(fixture, 1);
        TestEvidence evidence = publish(
                fixture,
                List.of(command),
                new TestStatistics(4, 4, 0, 0, 0),
                passed(fixture, command),
                true);

        assertTrue(evidence.succeeded());
        assertEquals(Optional.empty(), evidence.failureClassification());
        assertEquals(List.of(command.reference()), evidence.commands());
        assertEquals(fixture.target.reference(), evidence.codingTarget());
        assertEquals(fixture.policy.reference(), evidence.workspacePolicy());
        assertEquals(diffManifest().generation(), evidence.diffGeneration());
        assertEquals(diffManifest().contentHash(), evidence.diffManifestHash());
        assertThrows(
                UnsupportedOperationException.class,
                () -> evidence.commands().add(command.reference()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> evidence.acceptanceResults().clear());
        assertEquals(evidence.evidenceHash(), reconstitute(evidence).evidenceHash());
    }

    @Test
    void validatesTestCountEquationWithoutOverflow() {
        assertEquals(6, new TestStatistics(6, 3, 1, 1, 1).total());
        assertThrows(
                DomainValidationException.class,
                () -> new TestStatistics(5, 3, 1, 1, 1));
        assertThrows(
                DomainValidationException.class,
                () -> new TestStatistics(-1, 0, 0, 0, 0));
        assertThrows(
                DomainValidationException.class,
                () -> new TestStatistics(
                        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, 0));
    }

    @Test
    void requiresStrictCommandOrderUniqueIdentityAndVerificationCommand() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        CommandEvidence first = successfulCommand(fixture, 1);
        CommandEvidence second = successfulCommand(fixture, 2);

        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        fixture,
                        List.of(second, first),
                        new TestStatistics(1, 1, 0, 0, 0),
                        passed(fixture, first),
                        true));
        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        fixture,
                        List.of(first, first),
                        new TestStatistics(1, 1, 0, 0, 0),
                        passed(fixture, first),
                        true));
        CommandEvidence compileOnly = CommandEvidenceTest.evidence(
                fixture,
                EvidenceSequence.first(),
                CommandEvidenceTest.spec(fixture, io.crewscope.domain.coding.CommandKind.COMPILE),
                CommandTermination.EXITED,
                Optional.of(0));
        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        fixture,
                        List.of(compileOnly),
                        new TestStatistics(1, 1, 0, 0, 0),
                        passed(fixture, compileOnly),
                        true));
        CommandEvidence anotherWorkspaceCommand = successfulCommand(
                CodingEvidenceFixture.create(), 3);
        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        fixture,
                        List.of(anotherWorkspaceCommand),
                        new TestStatistics(1, 1, 0, 0, 0),
                        passed(fixture, anotherWorkspaceCommand),
                        true));
    }

    @Test
    void closesAcceptanceTextOrderAndEvidenceMembership() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        CommandEvidence included = successfulCommand(fixture, 1);
        CommandEvidence outside = successfulCommand(fixture, 2);
        List<AcceptanceResult> wrongText = new ArrayList<>(passed(fixture, included));
        wrongText.set(
                0,
                new AcceptanceResult(
                        1,
                        "Agent claimed another criterion",
                        AcceptanceStatus.PASSED,
                        List.of(included.reference()),
                        new EvidenceSummary("claimed")));
        List<AcceptanceResult> outsideReference = new ArrayList<>(passed(fixture, included));
        outsideReference.set(
                0,
                new AcceptanceResult(
                        1,
                        fixture.target.acceptanceCriteria().get(0),
                        AcceptanceStatus.PASSED,
                        List.of(outside.reference()),
                        new EvidenceSummary("outside")));

        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        fixture,
                        List.of(included),
                        new TestStatistics(1, 1, 0, 0, 0),
                        wrongText,
                        true));
        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        fixture,
                        List.of(included),
                        new TestStatistics(1, 1, 0, 0, 0),
                        outsideReference,
                        true));
        assertThrows(
                DomainValidationException.class,
                () -> new AcceptanceResult(
                        1,
                        fixture.target.acceptanceCriteria().get(0),
                        AcceptanceStatus.NOT_EVALUATED,
                        List.of(included.reference()),
                        new EvidenceSummary("not evaluated")));
    }

    @Test
    void appliesStableFailurePriorityAndCannotAcceptAClaimedSuccessFlag() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        CommandEvidence failedCommand = CommandEvidenceTest.evidence(
                fixture,
                EvidenceSequence.first(),
                CommandEvidenceTest.spec(fixture, io.crewscope.domain.coding.CommandKind.TEST),
                CommandTermination.TIMED_OUT,
                Optional.empty());
        assertFailure(
                publish(
                        fixture,
                        List.of(failedCommand),
                        new TestStatistics(0, 0, 0, 0, 0),
                        notEvaluated(fixture),
                        false),
                EvidenceFailureClassification.COMMAND_TIMED_OUT);

        CommandEvidence success = successfulCommand(fixture, 1);
        assertFailure(
                publish(
                        fixture,
                        List.of(success),
                        new TestStatistics(0, 0, 0, 0, 0),
                        notEvaluated(fixture),
                        false),
                EvidenceFailureClassification.TEST_REPORT_MISSING);
        assertFailure(
                publish(
                        fixture,
                        List.of(success),
                        new TestStatistics(0, 0, 0, 0, 0),
                        notEvaluated(fixture),
                        true),
                EvidenceFailureClassification.NO_TESTS_EXECUTED);
        assertFailure(
                publish(
                        fixture,
                        List.of(success),
                        new TestStatistics(2, 1, 1, 0, 0),
                        notEvaluated(fixture),
                        true),
                EvidenceFailureClassification.TESTS_FAILED);
        assertFailure(
                publish(
                        fixture,
                        List.of(success),
                        new TestStatistics(1, 1, 0, 0, 0),
                        notEvaluated(fixture),
                        true),
                EvidenceFailureClassification.ACCEPTANCE_INCOMPLETE);
        List<AcceptanceResult> failedAcceptance = new ArrayList<>(passed(fixture, success));
        failedAcceptance.set(
                1,
                new AcceptanceResult(
                        2,
                        fixture.target.acceptanceCriteria().get(1),
                        AcceptanceStatus.FAILED,
                        List.of(success.reference()),
                        new EvidenceSummary("criterion failed")));
        assertFailure(
                publish(
                        fixture,
                        List.of(success),
                        new TestStatistics(1, 1, 0, 0, 0),
                        failedAcceptance,
                        true),
                EvidenceFailureClassification.ACCEPTANCE_FAILED);

        // There is intentionally no success argument on TestEvidence.publish; all status is derived.
        assertFalse(publish(
                        fixture,
                        List.of(success),
                        new TestStatistics(1, 1, 0, 0, 0),
                        failedAcceptance,
                        true)
                .succeeded());
    }

    @Test
    void rejectsWrongReportTypeAndHashOrClassificationTampering() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        CommandEvidence command = successfulCommand(fixture, 1);
        assertThrows(
                DomainValidationException.class,
                () -> TestEvidence.publish(
                        TestEvidenceId.generate(),
                        fixture.workspace,
                        fixture.target,
                        fixture.policy,
                        diffManifest(),
                        EvidenceSequence.first(),
                        List.of(command),
                        new TestStatistics(1, 1, 0, 0, 0),
                        passed(fixture, command),
                        Optional.of(CommandEvidenceTest.artifact(
                                EvidenceArtifactKind.COMMAND_LOG, "wrong")),
                        new EvidenceSummary("summary"),
                        fixture.domain.owner,
                        PUBLISHED_AT));

        TestEvidence evidence = publish(
                fixture,
                List.of(command),
                new TestStatistics(1, 1, 0, 0, 0),
                passed(fixture, command),
                true);
        assertThrows(
                DomainValidationException.class,
                () -> TestEvidence.reconstitute(
                        evidence.id(),
                        evidence.scope(),
                        evidence.taskId(),
                        evidence.taskExecutionId(),
                        evidence.attempt(),
                        evidence.executionWorkspaceId(),
                        evidence.workspaceFingerprint(),
                        evidence.codingTarget(),
                        evidence.diffGeneration(),
                        evidence.diffManifestHash(),
                        evidence.sequence(),
                        evidence.workspacePolicy(),
                        evidence.commands(),
                        evidence.statistics(),
                        evidence.acceptanceResults(),
                        evidence.testReport(),
                        new EvidenceSummary("tampered"),
                        evidence.failureClassification(),
                        evidence.evidenceHash(),
                        evidence.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> TestEvidence.reconstitute(
                        evidence.id(),
                        evidence.scope(),
                        evidence.taskId(),
                        evidence.taskExecutionId(),
                        evidence.attempt(),
                        evidence.executionWorkspaceId(),
                        evidence.workspaceFingerprint(),
                        evidence.codingTarget(),
                        evidence.diffGeneration(),
                        RuntimeContentHash.sha256("tampered-tested-diff"),
                        evidence.sequence(),
                        evidence.workspacePolicy(),
                        evidence.commands(),
                        evidence.statistics(),
                        evidence.acceptanceResults(),
                        evidence.testReport(),
                        evidence.summary(),
                        evidence.failureClassification(),
                        evidence.evidenceHash(),
                        evidence.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> TestEvidence.reconstitute(
                        evidence.id(),
                        evidence.scope(),
                        evidence.taskId(),
                        evidence.taskExecutionId(),
                        evidence.attempt(),
                        evidence.executionWorkspaceId(),
                        evidence.workspaceFingerprint(),
                        evidence.codingTarget(),
                        evidence.diffGeneration(),
                        evidence.diffManifestHash(),
                        evidence.sequence(),
                        evidence.workspacePolicy(),
                        evidence.commands(),
                        evidence.statistics(),
                        evidence.acceptanceResults(),
                        evidence.testReport(),
                        evidence.summary(),
                        Optional.of(EvidenceFailureClassification.ACCEPTANCE_FAILED),
                        evidence.evidenceHash(),
                        evidence.audit()));
    }

    private static CommandEvidence successfulCommand(CodingEvidenceFixture fixture, long sequence) {
        return CommandEvidenceTest.evidence(
                fixture,
                new EvidenceSequence(sequence),
                CommandEvidenceTest.spec(fixture, io.crewscope.domain.coding.CommandKind.TEST),
                CommandTermination.EXITED,
                Optional.of(0));
    }

    private static List<AcceptanceResult> passed(
            CodingEvidenceFixture fixture, CommandEvidence command) {
        List<AcceptanceResult> results = new ArrayList<>();
        for (int index = 0; index < fixture.target.acceptanceCriteria().size(); index++) {
            results.add(new AcceptanceResult(
                    index + 1,
                    fixture.target.acceptanceCriteria().get(index),
                    AcceptanceStatus.PASSED,
                    List.of(command.reference()),
                    new EvidenceSummary("criterion passed")));
        }
        return results;
    }

    private static List<AcceptanceResult> notEvaluated(CodingEvidenceFixture fixture) {
        List<AcceptanceResult> results = new ArrayList<>();
        for (int index = 0; index < fixture.target.acceptanceCriteria().size(); index++) {
            results.add(new AcceptanceResult(
                    index + 1,
                    fixture.target.acceptanceCriteria().get(index),
                    AcceptanceStatus.NOT_EVALUATED,
                    List.of(),
                    new EvidenceSummary("not evaluated")));
        }
        return results;
    }

    private static TestEvidence publish(
            CodingEvidenceFixture fixture,
            List<CommandEvidence> commands,
            TestStatistics statistics,
            List<AcceptanceResult> acceptanceResults,
            boolean withReport) {
        return TestEvidence.publish(
                TestEvidenceId.generate(),
                fixture.workspace,
                fixture.target,
                fixture.policy,
                diffManifest(),
                EvidenceSequence.first(),
                commands,
                statistics,
                acceptanceResults,
                withReport
                        ? Optional.of(CommandEvidenceTest.artifact(
                                EvidenceArtifactKind.TEST_REPORT, "test report"))
                        : Optional.empty(),
                new EvidenceSummary("test evidence summary"),
                fixture.domain.owner,
                PUBLISHED_AT);
    }

    private static void assertFailure(
            TestEvidence evidence, EvidenceFailureClassification expected) {
        assertFalse(evidence.succeeded());
        assertEquals(Optional.of(expected), evidence.failureClassification());
    }

    private static TestEvidence reconstitute(TestEvidence evidence) {
        return TestEvidence.reconstitute(
                evidence.id(),
                evidence.scope(),
                evidence.taskId(),
                evidence.taskExecutionId(),
                evidence.attempt(),
                evidence.executionWorkspaceId(),
                evidence.workspaceFingerprint(),
                evidence.codingTarget(),
                evidence.diffGeneration(),
                evidence.diffManifestHash(),
                evidence.sequence(),
                evidence.workspacePolicy(),
                evidence.commands(),
                evidence.statistics(),
                evidence.acceptanceResults(),
                evidence.testReport(),
                evidence.summary(),
                evidence.failureClassification(),
                evidence.evidenceHash(),
                evidence.audit());
    }

    private static DiffManifest diffManifest() {
        return DiffManifest.initial(List.of());
    }
}
