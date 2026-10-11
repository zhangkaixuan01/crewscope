package io.crewscope.server.api;

import io.crewscope.application.operations.NotificationDeliveryRecoveryTarget;
import io.crewscope.application.operations.OperationsAdministratorDiagnostics;
import io.crewscope.application.operations.OperationsRecoveryStrongConfirmation;
import io.crewscope.application.operations.OperationsRecoveryTarget;
import io.crewscope.application.operations.OutboxDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionDeadLetterRecoveryTarget;
import java.util.List;

/** Organization administrator diagnostics: safe coordinates and recovery candidates. */
public record AdministratorDiagnosticsResponse(
        HealthSummaryResponse summary,
        List<ProjectionDiagnosticResponse> projections,
        List<RecoveryCandidateResponse> recoveryCandidates) {

    static AdministratorDiagnosticsResponse from(OperationsAdministratorDiagnostics value) {
        return new AdministratorDiagnosticsResponse(
                HealthSummaryResponse.from(value.summary()),
                value.projections().stream().map(ProjectionDiagnosticResponse::from).toList(),
                value.recoveryCandidates().stream()
                        .map(AdministratorDiagnosticsResponse::candidate)
                        .toList());
    }

    private static RecoveryCandidateResponse candidate(OperationsRecoveryTarget value) {
        String confirmation = OperationsRecoveryStrongConfirmation.expectedPhrase(value);
        if (value instanceof OutboxDeadLetterRecoveryTarget target) {
            return new OutboxRecoveryCandidateResponse(
                    "OUTBOX_DEAD_LETTER",
                    target.action().name(),
                    target.outboxEventId().toString(),
                    target.domainEventId().toString(),
                    target.expectedVersion(),
                    target.referenceHash(),
                    confirmation);
        }
        if (value instanceof ProjectionDeadLetterRecoveryTarget target) {
            return new ProjectionRecoveryCandidateResponse(
                    "PROJECTION_DEAD_LETTER",
                    target.action().name(),
                    target.projectionName().value(),
                    target.generation().value(),
                    target.deadLetterId().value().toString(),
                    target.domainEventId().toString(),
                    target.expectedGenerationVersion(),
                    target.referenceHash(),
                    confirmation);
        }
        if (value instanceof NotificationDeliveryRecoveryTarget target) {
            return new NotificationRecoveryCandidateResponse(
                    "NOTIFICATION_DELIVERY",
                    target.action().name(),
                    target.deliveryId().toString(),
                    target.expectedVersion(),
                    target.referenceHash(),
                    confirmation);
        }
        throw new IllegalStateException("Unsupported operations recovery target");
    }
}
