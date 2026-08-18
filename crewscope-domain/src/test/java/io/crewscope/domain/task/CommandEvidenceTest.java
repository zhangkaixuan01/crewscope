package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceFailureClassification;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandEvidenceTest {

    private static final UtcTimestamp STARTED_AT =
            UtcTimestamp.parse("2026-08-17T09:11:00Z");
    private static final UtcTimestamp FINISHED_AT =
            UtcTimestamp.parse("2026-08-17T09:12:00Z");
    private static final UtcTimestamp RECORDED_AT =
            UtcTimestamp.parse("2026-08-17T09:13:00Z");

    @Test
    void capturesExactPolicyProfileImageArgvTimeoutAndIntegrityHash() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        CommandSpec spec = spec(fixture, CommandKind.TEST);
        CommandEvidence evidence = evidence(
                fixture,
                EvidenceSequence.first(),
                spec,
                CommandTermination.EXITED,
                Optional.of(0));

        assertEquals(fixture.policy.reference(), spec.workspacePolicy());
        assertEquals(fixture.profile.reference(), spec.buildProfile());
        assertEquals(fixture.profile.sandboxImage(), spec.sandboxImage());
        assertEquals(List.of("./mvnw", "test"), spec.argv());
        assertEquals(60, spec.timeoutSeconds());
        assertTrue(evidence.succeeded());
        assertEquals(Optional.empty(), evidence.failureClassification());
        assertEquals(evidence.evidenceHash(), evidence.reference().evidenceHash());
        assertEquals(evidence.failureClassification(), evidence.reference().failureClassification());
        assertEquals(evidence.evidenceHash(), reconstitute(evidence).evidenceHash());

        assertThrows(
                DomainValidationException.class,
                () -> CommandSpec.reconstitute(
                        spec.workspacePolicy(),
                        spec.buildProfile(),
                        spec.commandKind(),
                        spec.toolKey(),
                        List.of("./mvnw", "verify"),
                        spec.workingDirectory(),
                        spec.timeoutSeconds(),
                        spec.sandboxImage(),
                        spec.specHash()));

        CodingEvidenceFixture other = CodingEvidenceFixture.create();
        assertThrows(
                DomainValidationException.class,
                () -> CommandEvidence.record(
                        CommandEvidenceId.generate(),
                        fixture.workspace,
                        other.policy,
                        EvidenceSequence.first(),
                        spec,
                        STARTED_AT,
                        FINISHED_AT,
                        CommandTermination.EXITED,
                        Optional.of(0),
                        new EvidenceSummary("scope mismatch"),
                        artifact(EvidenceArtifactKind.COMMAND_LOG, "log"),
                        fixture.domain.owner,
                        RECORDED_AT));
        assertTrue(java.util.Arrays.stream(CommandEvidence.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .noneMatch(java.nio.file.Path.class::isAssignableFrom));
    }

    @Test
    void derivesEveryFailureClassFromTerminationAndExitCode() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        Map<CommandTermination, EvidenceFailureClassification> expected = Map.of(
                CommandTermination.START_FAILED,
                        EvidenceFailureClassification.COMMAND_START_FAILED,
                CommandTermination.TIMED_OUT,
                        EvidenceFailureClassification.COMMAND_TIMED_OUT,
                CommandTermination.OUTPUT_LIMIT_EXCEEDED,
                        EvidenceFailureClassification.COMMAND_OUTPUT_LIMIT_EXCEEDED,
                CommandTermination.SANDBOX_POLICY_VIOLATION,
                        EvidenceFailureClassification.COMMAND_SANDBOX_POLICY_VIOLATION,
                CommandTermination.CANCELLED,
                        EvidenceFailureClassification.COMMAND_CANCELLED);
        long sequence = 1;
        for (Map.Entry<CommandTermination, EvidenceFailureClassification> entry
                : expected.entrySet()) {
            CommandEvidence evidence = evidence(
                    fixture,
                    new EvidenceSequence(sequence++),
                    spec(fixture, CommandKind.TEST),
                    entry.getKey(),
                    Optional.empty());
            assertFalse(evidence.succeeded());
            assertEquals(Optional.of(entry.getValue()), evidence.failureClassification());
        }
        CommandEvidence nonZero = evidence(
                fixture,
                new EvidenceSequence(sequence),
                spec(fixture, CommandKind.TEST),
                CommandTermination.EXITED,
                Optional.of(1));
        assertEquals(
                Optional.of(EvidenceFailureClassification.COMMAND_NON_ZERO_EXIT),
                nonZero.failureClassification());
    }

    @Test
    void rejectsExitCodesForNonExitedProcessesAndMissingExitedCode() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> evidence(
                        fixture,
                        EvidenceSequence.first(),
                        spec(fixture, CommandKind.TEST),
                        CommandTermination.EXITED,
                        Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> evidence(
                        fixture,
                        EvidenceSequence.first(),
                        spec(fixture, CommandKind.TEST),
                        CommandTermination.TIMED_OUT,
                        Optional.of(124)));
    }

    @Test
    void rejectsWrongArtifactKindInvalidTimesAndUnboundedSummary() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        EvidenceArtifactReference report = artifact(
                EvidenceArtifactKind.TEST_REPORT, "report");

        assertThrows(
                DomainValidationException.class,
                () -> CommandEvidence.record(
                        CommandEvidenceId.generate(),
                        fixture.workspace,
                        fixture.policy,
                        EvidenceSequence.first(),
                        spec(fixture, CommandKind.TEST),
                        FINISHED_AT,
                        STARTED_AT,
                        CommandTermination.EXITED,
                        Optional.of(0),
                        new EvidenceSummary("ok"),
                        report,
                        fixture.domain.owner,
                        RECORDED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> new EvidenceSummary("x\n".repeat(EvidenceSummary.MAX_LINES + 1)));
        assertThrows(
                DomainValidationException.class,
                () -> new EvidenceSummary("界".repeat(EvidenceSummary.MAX_BYTES)));
        assertThrows(
                DomainValidationException.class,
                () -> CommandEvidence.record(
                        CommandEvidenceId.generate(),
                        fixture.workspace,
                        fixture.policy,
                        EvidenceSequence.first(),
                        spec(fixture, CommandKind.TEST),
                        STARTED_AT,
                        FINISHED_AT,
                        CommandTermination.EXITED,
                        Optional.of(0),
                        new EvidenceSummary("ok"),
                        report,
                        fixture.domain.owner,
                        RECORDED_AT));
    }

    @Test
    void validatesEmptyArtifactHashAndRejectsEvidenceHashTampering() {
        CodingEvidenceFixture fixture = CodingEvidenceFixture.create();
        assertEquals(
                RuntimeContentHash.sha256(""),
                new EvidenceArtifactReference(
                                ArtifactId.generate(),
                                EvidenceArtifactKind.COMMAND_LOG,
                                "text/plain; charset=utf-8",
                                0,
                                RuntimeContentHash.sha256(""))
                        .contentHash());
        assertThrows(
                DomainValidationException.class,
                () -> new EvidenceArtifactReference(
                        ArtifactId.generate(),
                        EvidenceArtifactKind.COMMAND_LOG,
                        "text/plain",
                        0,
                        RuntimeContentHash.sha256("non-empty")));

        CommandEvidence evidence = evidence(
                fixture,
                EvidenceSequence.first(),
                spec(fixture, CommandKind.TEST),
                CommandTermination.EXITED,
                Optional.of(0));
        assertThrows(
                DomainValidationException.class,
                () -> CommandEvidence.reconstitute(
                        evidence.id(),
                        evidence.scope(),
                        evidence.taskId(),
                        evidence.taskExecutionId(),
                        evidence.attempt(),
                        evidence.executionWorkspaceId(),
                        evidence.workspaceFingerprint(),
                        evidence.codingTarget(),
                        evidence.sequence(),
                        evidence.workspacePolicy(),
                        evidence.commandSpec(),
                        evidence.startedAt(),
                        evidence.finishedAt(),
                        evidence.termination(),
                        evidence.exitCode(),
                        new EvidenceSummary("tampered"),
                        evidence.commandLog(),
                        evidence.failureClassification(),
                        evidence.evidenceHash(),
                        evidence.audit()));
    }

    static CommandSpec spec(CodingEvidenceFixture fixture, CommandKind kind) {
        List<String> argv = kind == CommandKind.TEST
                ? List.of("./mvnw", "test")
                : List.of("./mvnw", "compile");
        return CommandSpec.capture(fixture.policy, fixture.profile, kind, argv, 60);
    }

    static CommandEvidence evidence(
            CodingEvidenceFixture fixture,
            EvidenceSequence sequence,
            CommandSpec spec,
            CommandTermination termination,
            Optional<Integer> exitCode) {
        return CommandEvidence.record(
                CommandEvidenceId.generate(),
                fixture.workspace,
                fixture.policy,
                sequence,
                spec,
                STARTED_AT,
                FINISHED_AT,
                termination,
                exitCode,
                new EvidenceSummary("controlled command summary"),
                artifact(EvidenceArtifactKind.COMMAND_LOG, "command output"),
                fixture.domain.owner,
                RECORDED_AT);
    }

    static EvidenceArtifactReference artifact(EvidenceArtifactKind kind, String content) {
        return new EvidenceArtifactReference(
                ArtifactId.generate(),
                kind,
                kind == EvidenceArtifactKind.COMMAND_LOG
                        ? "text/plain;charset=utf-8"
                        : "application/xml",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                RuntimeContentHash.sha256(content));
    }

    private static CommandEvidence reconstitute(CommandEvidence evidence) {
        return CommandEvidence.reconstitute(
                evidence.id(),
                evidence.scope(),
                evidence.taskId(),
                evidence.taskExecutionId(),
                evidence.attempt(),
                evidence.executionWorkspaceId(),
                evidence.workspaceFingerprint(),
                evidence.codingTarget(),
                evidence.sequence(),
                evidence.workspacePolicy(),
                evidence.commandSpec(),
                evidence.startedAt(),
                evidence.finishedAt(),
                evidence.termination(),
                evidence.exitCode(),
                evidence.summary(),
                evidence.commandLog(),
                evidence.failureClassification(),
                evidence.evidenceHash(),
                evidence.audit());
    }
}
