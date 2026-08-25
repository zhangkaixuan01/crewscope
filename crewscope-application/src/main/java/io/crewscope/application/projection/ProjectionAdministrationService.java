package io.crewscope.application.projection;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGenerationState;
import io.crewscope.domain.projection.ProjectionLifecycleEvent;
import io.crewscope.domain.projection.ProjectionLifecycleEventType;
import io.crewscope.domain.projection.ProjectionPointer;
import io.crewscope.domain.projection.ProjectionRebuildJob;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.projection.ProjectionRebuildStart;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.projection.ProjectionSwitchPlan;
import io.crewscope.domain.projection.ProjectionTerminationPlan;
import io.crewscope.domain.projection.ProjectionValidationPlan;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Admin-only orchestration for start, retry, validation, switching, cancellation and failure. */
public final class ProjectionAdministrationService {

    private final ProjectionAdministration administration;
    private final ProjectionAdministrationRepository repository;
    private final ProjectionSnapshotVerifier verifier;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ProjectionAdministrationService(
            ProjectionAdministration administration,
            ProjectionAdministrationRepository repository,
            ProjectionSnapshotVerifier verifier,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public ProjectionAdministrationResult start(StartProjectionRebuildCommand command) {
        StartProjectionRebuildCommand required = Objects.requireNonNull(command, "command");
        ProjectionCommandFingerprint fingerprint = fingerprint(
                ProjectionAdministrationAction.START_REBUILD,
                required.organizationId(), required.projectionName().value(),
                required.expectedDefinitionVersion().value(), required.expectedPointerVersion(),
                required.actor().id());
        return transactions.required(() -> startInTransaction(required, fingerprint));
    }

    public ProjectionAdministrationResult retry(RetryProjectionRebuildCommand command) {
        RetryProjectionRebuildCommand required = Objects.requireNonNull(command, "command");
        ProjectionCommandFingerprint fingerprint = fingerprint(
                ProjectionAdministrationAction.RETRY_REBUILD,
                required.organizationId(), required.projectionName().value(),
                required.retryOfJobId(), required.expectedRetryOfJobVersion(),
                required.expectedDefinitionVersion().value(), required.expectedPointerVersion(),
                required.actor().id());
        return transactions.required(() -> retryInTransaction(required, fingerprint));
    }

    public ProjectionAdministrationResult validate(ValidateProjectionGenerationCommand command) {
        ValidateProjectionGenerationCommand required = Objects.requireNonNull(command, "command");
        ProjectionCommandFingerprint fingerprint = fingerprint(
                ProjectionAdministrationAction.VALIDATE_GENERATION,
                required.organizationId(), required.projectionName().value(),
                required.expectedDefinitionVersion().value(), required.generation().value(),
                required.rebuildJobId(), required.expectedGenerationVersion(),
                required.expectedJobVersion(), required.actor().id());
        return transactions.required(() -> validateInTransaction(required, fingerprint));
    }

    public ProjectionAdministrationResult switchGeneration(
            SwitchProjectionGenerationCommand command) {
        SwitchProjectionGenerationCommand required = Objects.requireNonNull(command, "command");
        ProjectionCommandFingerprint fingerprint = fingerprint(
                ProjectionAdministrationAction.SWITCH_GENERATION,
                required.organizationId(), required.projectionName().value(),
                required.expectedDefinitionVersion().value(),
                required.previousActiveGeneration().value(), required.targetGeneration().value(),
                required.rebuildJobId(), required.expectedPointerVersion(),
                required.expectedPreviousGenerationVersion(),
                required.expectedTargetGenerationVersion(), required.expectedJobVersion(),
                required.actor().id());
        return transactions.required(() -> switchInTransaction(required, fingerprint));
    }

    public ProjectionAdministrationResult terminate(TerminateProjectionRebuildCommand command) {
        TerminateProjectionRebuildCommand required = Objects.requireNonNull(command, "command");
        ProjectionCommandFingerprint fingerprint = fingerprint(
                required.action(), required.organizationId(), required.projectionName().value(),
                required.generation().value(), required.rebuildJobId(),
                required.expectedGenerationVersion(), required.expectedJobVersion(),
                required.failureCode().map(ProjectionFailureCode::value).orElse("none"),
                required.actor().id());
        return transactions.required(() -> terminateInTransaction(required, fingerprint));
    }

    private ProjectionAdministrationResult startInTransaction(
            StartProjectionRebuildCommand command, ProjectionCommandFingerprint fingerprint) {
        UtcTimestamp now = authorize(command.organizationId(), command.actor());
        Optional<ProjectionAdministrationResult> replay = replay(
                command.organizationId(), command.commandId(), fingerprint);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ProjectionRegistrySnapshot registry = repository.loadForUpdate(
                command.organizationId(), command.projectionName());
        requireDefinitionAndPointer(
                registry, command.expectedDefinitionVersion(), command.expectedPointerVersion());
        ProjectionRebuildStart start = ProjectionRebuildStart.start(
                command.organizationId(), registry.definition(), registry.pointer(),
                registry.generations(), ProjectionRebuildJobId.generate(), Optional.empty(),
                command.actor().id(), now);
        return saveStart(command.commandId(), command.actor().id(), start,
                ProjectionAdministrationAction.START_REBUILD, fingerprint, now);
    }

    private ProjectionAdministrationResult retryInTransaction(
            RetryProjectionRebuildCommand command, ProjectionCommandFingerprint fingerprint) {
        UtcTimestamp now = authorize(command.organizationId(), command.actor());
        Optional<ProjectionAdministrationResult> replay = replay(
                command.organizationId(), command.commandId(), fingerprint);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ProjectionRegistrySnapshot registry = repository.loadForUpdate(
                command.organizationId(), command.projectionName());
        requireDefinitionAndPointer(
                registry, command.expectedDefinitionVersion(), command.expectedPointerVersion());
        ProjectionRebuildJob previous = registry.requireJob(command.retryOfJobId());
        requireVersion("retryOf RebuildJob", command.expectedRetryOfJobVersion(), previous.version());
        ProjectionRebuildStart start = ProjectionRebuildStart.start(
                command.organizationId(), registry.definition(), registry.pointer(),
                registry.generations(), ProjectionRebuildJobId.generate(), Optional.of(previous),
                command.actor().id(), now);
        return saveStart(command.commandId(), command.actor().id(), start,
                ProjectionAdministrationAction.RETRY_REBUILD, fingerprint, now);
    }

    private ProjectionAdministrationResult validateInTransaction(
            ValidateProjectionGenerationCommand command,
            ProjectionCommandFingerprint fingerprint) {
        UtcTimestamp now = authorize(command.organizationId(), command.actor());
        Optional<ProjectionAdministrationResult> replay = replay(
                command.organizationId(), command.commandId(), fingerprint);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ProjectionRegistrySnapshot registry = repository.loadForUpdate(
                command.organizationId(), command.projectionName());
        requireDefinition(registry.definition(), command.expectedDefinitionVersion());
        ProjectionGenerationState generation = registry.requireGeneration(command.generation());
        ProjectionRebuildJob job = registry.requireJob(command.rebuildJobId());
        requireVersion(
                "Projection Generation", command.expectedGenerationVersion(), generation.version());
        requireVersion("Projection RebuildJob", command.expectedJobVersion(), job.version());
        ProjectionVerificationSnapshots snapshots = verifier.verify(registry.definition(), generation);
        ProjectionValidationPlan plan = ProjectionValidationPlan.validate(
                registry.definition(), generation, job, command.expectedGenerationVersion(),
                command.expectedJobVersion(), snapshots.expected(), snapshots.actual(),
                command.actor().id(), now);
        ProjectionAdministrationAction action = plan.result().passed()
                ? ProjectionAdministrationAction.VALIDATION_PASSED
                : ProjectionAdministrationAction.VALIDATION_FAILED;
        ProjectionAdministrationResult result = result(
                plan.generation(), plan.job(), OptionalLong.empty());
        ProjectionLifecycleEvent event = event(
                command.commandId(), command.actor().id(),
                plan.generation(), plan.job(), action, Optional.empty(), Optional.empty(), now);
        ProjectionCommandReceipt receipt = receipt(command.commandId(), fingerprint, result);
        return repository.saveValidation(plan, event, receipt).replay(fingerprint);
    }

    private ProjectionAdministrationResult switchInTransaction(
            SwitchProjectionGenerationCommand command,
            ProjectionCommandFingerprint fingerprint) {
        UtcTimestamp now = authorize(command.organizationId(), command.actor());
        Optional<ProjectionAdministrationResult> replay = replay(
                command.organizationId(), command.commandId(), fingerprint);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ProjectionRegistrySnapshot registry = repository.loadForSwitch(
                command.organizationId(), command.projectionName(), command.targetGeneration());
        requireDefinition(registry.definition(), command.expectedDefinitionVersion());
        ProjectionGenerationState previous = registry.requireGeneration(
                command.previousActiveGeneration());
        ProjectionGenerationState target = registry.requireGeneration(command.targetGeneration());
        ProjectionRebuildJob job = registry.requireJob(command.rebuildJobId());
        requireVersion(
                "Projection Pointer", command.expectedPointerVersion(), registry.pointer().version());
        requireVersion(
                "previous Projection Generation",
                command.expectedPreviousGenerationVersion(),
                previous.version());
        requireVersion(
                "target Projection Generation",
                command.expectedTargetGenerationVersion(),
                target.version());
        requireVersion("Projection RebuildJob", command.expectedJobVersion(), job.version());
        ProjectionSnapshot current = verifier.current(registry.definition(), target);
        ProjectionSwitchPlan plan = ProjectionSwitchPlan.switchValidated(
                registry.pointer(), previous, target, job, current,
                command.expectedPointerVersion(), command.expectedPreviousGenerationVersion(),
                command.expectedTargetGenerationVersion(), command.expectedJobVersion(), now);
        ProjectionAdministrationResult result = result(
                plan.activatedTarget(), plan.completedJob(),
                OptionalLong.of(plan.pointer().version()));
        ProjectionLifecycleEvent event = event(
                command.commandId(), command.actor().id(),
                plan.activatedTarget(), plan.completedJob(),
                ProjectionAdministrationAction.SWITCH_GENERATION,
                Optional.of(command.previousActiveGeneration()), Optional.empty(), now);
        ProjectionCommandReceipt receipt = receipt(command.commandId(), fingerprint, result);
        return repository.switchGeneration(plan, event, receipt).replay(fingerprint);
    }

    private ProjectionAdministrationResult terminateInTransaction(
            TerminateProjectionRebuildCommand command,
            ProjectionCommandFingerprint fingerprint) {
        UtcTimestamp now = authorize(command.organizationId(), command.actor());
        Optional<ProjectionAdministrationResult> replay = replay(
                command.organizationId(), command.commandId(), fingerprint);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ProjectionRegistrySnapshot registry = repository.loadForUpdate(
                command.organizationId(), command.projectionName());
        ProjectionGenerationState generation = registry.requireGeneration(command.generation());
        ProjectionRebuildJob job = registry.requireJob(command.rebuildJobId());
        ProjectionTerminationPlan plan = command.action()
                        == ProjectionAdministrationAction.CANCEL_REBUILD
                ? ProjectionTerminationPlan.cancel(
                        generation, job, command.expectedGenerationVersion(),
                        command.expectedJobVersion(), now)
                : ProjectionTerminationPlan.fail(
                        generation, job, command.expectedGenerationVersion(),
                        command.expectedJobVersion(), now);
        ProjectionAdministrationResult result = result(
                plan.generation(), plan.job(), OptionalLong.empty());
        ProjectionLifecycleEvent event = event(
                command.commandId(), command.actor().id(),
                plan.generation(), plan.job(), command.action(), Optional.empty(),
                command.failureCode(), now);
        ProjectionCommandReceipt receipt = receipt(command.commandId(), fingerprint, result);
        return repository.terminateRebuild(plan, event, receipt).replay(fingerprint);
    }

    private ProjectionAdministrationResult saveStart(
            ProjectionAdministrationCommandId commandId,
            io.crewscope.domain.shared.id.PrincipalId actorId,
            ProjectionRebuildStart start,
            ProjectionAdministrationAction action,
            ProjectionCommandFingerprint fingerprint,
            UtcTimestamp now) {
        ProjectionAdministrationResult result = result(
                start.generation(), start.job(), OptionalLong.empty());
        ProjectionLifecycleEvent event = event(
                commandId, actorId, start.generation(), start.job(), action,
                Optional.empty(), Optional.empty(), now);
        ProjectionCommandReceipt receipt = receipt(commandId, fingerprint, result);
        return repository.createRebuild(start, event, receipt).replay(fingerprint);
    }

    private UtcTimestamp authorize(
            OrganizationId organizationId, io.crewscope.domain.identity.Principal actor) {
        UtcTimestamp now = timeProvider.now();
        administration.requireOrganizationAdministrator(organizationId, actor, now);
        return now;
    }

    private Optional<ProjectionAdministrationResult> replay(
            OrganizationId organizationId,
            ProjectionAdministrationCommandId commandId,
            ProjectionCommandFingerprint fingerprint) {
        return repository.findReceipt(organizationId, commandId)
                .map(value -> value.replay(fingerprint));
    }

    private static void requireDefinitionAndPointer(
            ProjectionRegistrySnapshot registry,
            io.crewscope.domain.projection.ProjectionDefinitionVersion expectedDefinitionVersion,
            long expectedPointerVersion) {
        requireDefinition(registry.definition(), expectedDefinitionVersion);
        requireVersion("Projection Pointer", expectedPointerVersion, registry.pointer().version());
    }

    private static void requireDefinition(
            ProjectionDefinition definition,
            io.crewscope.domain.projection.ProjectionDefinitionVersion expected) {
        if (!definition.version().equals(expected)) {
            throw new IllegalStateException(
                    "Projection Definition version conflict: expected "
                            + expected.value() + ", actual " + definition.version().value());
        }
    }

    private static void requireVersion(String type, long expected, long actual) {
        if (expected != actual) {
            throw new IllegalStateException(
                    type + " version conflict: expected " + expected + ", actual " + actual);
        }
    }

    private static ProjectionAdministrationResult result(
            ProjectionGenerationState generation,
            ProjectionRebuildJob job,
            OptionalLong pointerVersion) {
        return new ProjectionAdministrationResult(
                generation.key().organizationId(), generation.key().projectionName(),
                generation.key().generation(), job.id(), generation.status(), job.status(),
                pointerVersion);
    }

    private static ProjectionLifecycleEvent event(
            ProjectionAdministrationCommandId commandId,
            io.crewscope.domain.shared.id.PrincipalId actorId,
            ProjectionGenerationState generation,
            ProjectionRebuildJob job,
            ProjectionAdministrationAction action,
            Optional<io.crewscope.domain.projection.ProjectionGeneration> previous,
            Optional<ProjectionFailureCode> failureCode,
            UtcTimestamp now) {
        return new ProjectionLifecycleEvent(
                commandId.value(), generation.key().organizationId(), generation.key().projectionName(),
                generation.definitionVersion(), generation.key().generation(), job.id(),
                eventType(action),
                generation.status(), job.status(), previous, failureCode, actorId, now);
    }

    private static ProjectionLifecycleEventType eventType(
            ProjectionAdministrationAction action) {
        return switch (action) {
            case START_REBUILD -> ProjectionLifecycleEventType.REBUILD_STARTED;
            case RETRY_REBUILD -> ProjectionLifecycleEventType.REBUILD_RETRIED;
            case VALIDATION_PASSED -> ProjectionLifecycleEventType.VALIDATION_PASSED;
            case VALIDATION_FAILED -> ProjectionLifecycleEventType.VALIDATION_FAILED;
            case SWITCH_GENERATION -> ProjectionLifecycleEventType.GENERATION_SWITCHED;
            case CANCEL_REBUILD -> ProjectionLifecycleEventType.REBUILD_CANCELLED;
            case FAIL_REBUILD -> ProjectionLifecycleEventType.REBUILD_FAILED;
            case VALIDATE_GENERATION -> throw new IllegalArgumentException(
                    "A command action cannot be persisted as a lifecycle outcome");
        };
    }

    private static ProjectionCommandReceipt receipt(
            ProjectionAdministrationCommandId commandId,
            ProjectionCommandFingerprint fingerprint,
            ProjectionAdministrationResult result) {
        return new ProjectionCommandReceipt(
                commandId, result.organizationId(), fingerprint, result);
    }

    private static ProjectionCommandFingerprint fingerprint(Object... coordinates) {
        StringBuilder canonical = new StringBuilder();
        for (Object coordinate : coordinates) {
            String value = Objects.requireNonNull(coordinate, "command coordinate").toString();
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        return ProjectionCommandFingerprint.sha256(canonical.toString());
    }
}
