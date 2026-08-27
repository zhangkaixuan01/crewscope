package io.crewscope.application.teamobserver;

import io.crewscope.domain.teamobserver.TeamSummaryResult;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** One cancellable AgentScope summary execution; transport cancellation does not call cancel. */
public record TeamObserverExecution(
        CompletionStage<TeamSummaryResult> result, Runnable cancel) {

    public TeamObserverExecution {
        result = Objects.requireNonNull(result, "result");
        cancel = Objects.requireNonNull(cancel, "cancel");
    }
}
