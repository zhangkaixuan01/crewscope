package io.crewscope.server.api;

import io.crewscope.application.operations.OperationsRecoveryCommandId;
import io.crewscope.application.operations.OperationsRecoveryResult;

/** Accepted recovery command coordinates as returned by the replay endpoint. */
public record RecoveryResponse(
        String commandId,
        String action,
        String targetReferenceHash,
        String status,
        String acceptedAt) {

    static RecoveryResponse from(
            OperationsRecoveryCommandId commandId, OperationsRecoveryResult value) {
        return new RecoveryResponse(
                commandId.value().toString(),
                value.action().name(),
                value.targetReferenceHash(),
                value.status().name(),
                value.acceptedAt().toString());
    }
}
