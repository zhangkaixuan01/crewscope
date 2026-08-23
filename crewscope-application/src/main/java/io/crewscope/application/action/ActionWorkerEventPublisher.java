package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ExternalMergeOutcome;
import io.crewscope.domain.action.ExternalResult;
import java.util.UUID;

/** Appends sanitized Action events and Outbox rows inside the caller's transaction. */
public interface ActionWorkerEventPublisher {

    void dispatchTransitioned(ActionDispatch dispatch, ActionBundle bundle, UUID correlationId);

    void receiptRecorded(ActionReceipt receipt, ActionBundle bundle, UUID correlationId);

    void externalResultMerged(
            ExternalResult result,
            ExternalMergeOutcome outcome,
            ActionBundle bundle,
            UUID correlationId);

    static ActionWorkerEventPublisher noOp() {
        return new ActionWorkerEventPublisher() {
            @Override
            public void dispatchTransitioned(
                    ActionDispatch dispatch, ActionBundle bundle, UUID correlationId) {}

            @Override
            public void receiptRecorded(
                    ActionReceipt receipt, ActionBundle bundle, UUID correlationId) {}

            @Override
            public void externalResultMerged(
                    ExternalResult result,
                    ExternalMergeOutcome outcome,
                    ActionBundle bundle,
                    UUID correlationId) {}
        };
    }
}
