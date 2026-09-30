package io.crewscope.server.observability;

import io.crewscope.application.action.ActionWorkerObserver;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.shared.error.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Secret-free WARN trail for dispatches the Action Worker leaves READY after a rejected claim. */
public final class ActionWorkerLoggingObserver implements ActionWorkerObserver {

    private static final Logger log = LoggerFactory.getLogger(ActionWorkerLoggingObserver.class);

    @Override
    public void claimSkipped(ActionDispatchId dispatchId, DomainErrorCode reason) {
        // Only the dispatch id and the closed reason code — never message details, which may
        // carry tenant or resource context (M9b-Q02 defect 5).
        log.warn("action dispatch {} stayed READY; claim rejected with code {}",
                dispatchId, reason);
    }
}
