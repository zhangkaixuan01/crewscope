package io.crewscope.application.action;

import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.shared.error.DomainErrorCode;

/** Telemetry port for one claim-loop fact of the Action Worker. */
@FunctionalInterface
public interface ActionWorkerObserver {

    /**
     * Reports one dispatch that stayed READY because its claim preconditions failed on the
     * worker. The durable wait is intended, but a row that never becomes claimable again must
     * be locatable from logs or metrics alone — a statement census used to be the only way
     * (M9b-Q02 defect 5). The reason is the closed domain error code, never message details.
     */
    void claimSkipped(ActionDispatchId dispatchId, DomainErrorCode reason);

    static ActionWorkerObserver noOp() {
        return (dispatchId, reason) -> {};
    }
}
