package io.crewscope.agentscope.coding;

import io.crewscope.application.coding.output.CodeChangeResultV1;
import io.crewscope.domain.coding.CodingCheckpointId;
import java.util.Objects;
import java.util.Optional;

/** Final M4-I12 verdict; web projections consume only durable domain facts later in M4-A04. */
public record CodingSpecialistStepResult(
        CodingSpecialistStepStatus status,
        int modelCalls,
        int repairRounds,
        Optional<CodeChangeResultV1> output,
        Optional<CodingCheckpointId> checkpointId,
        Optional<String> failureCode) {

    public CodingSpecialistStepResult {
        status = Objects.requireNonNull(status, "status");
        if (modelCalls < 0 || repairRounds < 0) {
            throw new IllegalArgumentException("call counters must not be negative");
        }
        output = Objects.requireNonNull(output, "output");
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        if ((status == CodingSpecialistStepStatus.SUCCEEDED) != output.isPresent()
                || (status == CodingSpecialistStepStatus.FAILED) != failureCode.isPresent()) {
            throw new IllegalArgumentException("result payload must match the terminal status");
        }
    }
}
