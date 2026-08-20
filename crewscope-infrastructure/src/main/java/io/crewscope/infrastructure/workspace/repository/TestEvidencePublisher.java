package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestStatistics;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Publishes parser-derived TestEvidence immediately after a verification command. */
final class TestEvidencePublisher {

    private static final Set<CommandKind> VERIFICATION_COMMANDS =
            Set.of(CommandKind.TEST, CommandKind.VERIFY, CommandKind.ACCEPTANCE);

    private final TestEvidenceRepository tests;
    private final TestReportArtifactWriter reports;
    private final Clock clock;
    private final CodingTaskTimelinePublisher timeline;
    private final TransactionExecutor transactions;

    TestEvidencePublisher(
            TestEvidenceRepository tests,
            TestReportArtifactWriter reports,
            Clock clock,
            CodingTaskTimelinePublisher timeline,
            TransactionExecutor transactions) {
        this.tests = Objects.requireNonNull(tests, "tests");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    Optional<TestEvidence> publish(
            CodingWorkspaceExecution execution,
            Principal actor,
            CommandEvidence command,
            SandboxCommandExecution observed) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(observed, "observed");
        if (!VERIFICATION_COMMANDS.contains(command.commandSpec().commandKind())) {
            return Optional.empty();
        }
        var monitor = execution.diffMonitor().orElseThrow(() ->
                new IllegalStateException("Coding Workspace Diff monitor is unavailable"));
        monitor.reconcileNow();
        var manifest = monitor.latest().orElseThrow(() ->
                new IllegalStateException("Coding Workspace Diff manifest is unavailable"));
        EvidenceSequence sequence = nextSequence(execution);
        Optional<TestStatistics> parsed = MavenTestSummaryParser.parse(
                observed.stdout(), observed.stderr());
        TestStatistics statistics = parsed.orElseGet(() -> new TestStatistics(0, 0, 0, 0, 0));
        byte[] report = report(observed);
        EvidenceArtifactReference reportReference = reports.write(
                execution.workspace(),
                actor,
                sequence,
                "text/plain;charset=utf-8",
                report);
        List<CommandEvidence> evidence = List.of(command);
        AcceptanceStatus acceptanceStatus = command.succeeded()
                        && parsed.isPresent()
                        && statistics.total() > 0
                        && !statistics.hasFailures()
                ? AcceptanceStatus.PASSED
                : parsed.isPresent() ? AcceptanceStatus.FAILED : AcceptanceStatus.NOT_EVALUATED;
        List<AcceptanceResult> acceptance = java.util.stream.IntStream.range(
                        0, execution.target().acceptanceCriteria().size())
                .mapToObj(index -> new AcceptanceResult(
                        index + 1,
                        execution.target().acceptanceCriteria().get(index),
                        acceptanceStatus,
                        acceptanceStatus == AcceptanceStatus.NOT_EVALUATED
                                ? List.of()
                                : List.of(command.reference()),
                        new EvidenceSummary(acceptanceSummary(acceptanceStatus))))
                .toList();
        UtcTimestamp publishedAt = UtcTimestamp.from(clock.instant());
        if (publishedAt.compareTo(command.finishedAt()) < 0) {
            publishedAt = command.finishedAt();
        }
        TestEvidence published = TestEvidence.publish(
                TestEvidenceId.generate(),
                execution.workspace(),
                execution.target(),
                execution.policy(),
                manifest,
                sequence,
                evidence,
                statistics,
                acceptance,
                Optional.of(reportReference),
                new EvidenceSummary(summary(command, statistics, parsed.isPresent())),
                actor,
                publishedAt);
        return Optional.of(transactions.required(() -> {
            TestEvidence committed = tests.create(published);
            timeline.testEvidencePublished(committed);
            return committed;
        }));
    }

    private EvidenceSequence nextSequence(CodingWorkspaceExecution execution) {
        List<TestEvidence> existing = tests.findByWorkspace(
                execution.workspace().scope().organizationId(),
                execution.workspace().scope().teamId(),
                execution.workspace().scope().projectId(),
                execution.workspace().id());
        return existing.stream()
                .map(TestEvidence::sequence)
                .max(java.util.Comparator.naturalOrder())
                .map(EvidenceSequence::next)
                .orElseGet(EvidenceSequence::first);
    }

    private static byte[] report(SandboxCommandExecution observed) {
        String value = "--- stdout ---\n" + observed.stdout()
                + "\n--- stderr ---\n" + observed.stderr();
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String summary(
            CommandEvidence command, TestStatistics statistics, boolean parsed) {
        return "command=" + command.id()
                + " parsed=" + parsed
                + " total=" + statistics.total()
                + " passed=" + statistics.passed()
                + " failed=" + statistics.failed()
                + " errors=" + statistics.errors()
                + " skipped=" + statistics.skipped();
    }

    private static String acceptanceSummary(AcceptanceStatus status) {
        return switch (status) {
            case PASSED -> "Deployment-approved verification command passed with parsed tests";
            case FAILED -> "Verification command or parsed test result failed";
            case NOT_EVALUATED -> "Verification output did not contain a supported test summary";
        };
    }
}
