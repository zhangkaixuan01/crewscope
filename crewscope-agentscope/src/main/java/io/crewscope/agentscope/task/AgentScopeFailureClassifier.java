package io.crewscope.agentscope.task;

import io.agentscope.core.model.transport.HttpTransportException;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maps AgentScope/runtime failures to stable, secret-free CrewScope execution failures. */
final class AgentScopeFailureClassifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentScopeFailureClassifier.class);

    private AgentScopeFailureClassifier() {}

    static ExecutionFailure budget(String code, String message) {
        return new ExecutionFailure(ExecutionFailureCategory.VALIDATION, false, message, Optional.of(code));
    }

    static ExecutionFailure classify(Throwable failure) {
        Throwable required = Objects.requireNonNull(failure, "failure");
        if (required instanceof TimeoutException || required instanceof TaskDurationBudgetException) {
            return new ExecutionFailure(ExecutionFailureCategory.TIMEOUT, true,
                    "The Task runtime exceeded its duration budget.", Optional.of("DURATION_BUDGET_EXCEEDED"));
        }
        if (required instanceof IllegalArgumentException) {
            return new ExecutionFailure(ExecutionFailureCategory.AUTHORIZATION, false,
                    "The Task runtime rejected an unsafe AgentScope event.", Optional.of("TASK_RUNTIME_EVENT_REJECTED"));
        }
        LOGGER.warn("Task Agent model call failed with cause types {} and HTTP status {}",
                failureTypes(required), httpStatus(required).map(String::valueOf).orElse("none"));
        return new ExecutionFailure(ExecutionFailureCategory.MODEL_UNAVAILABLE, true,
                "The Task Agent model is temporarily unavailable.", Optional.of("TASK_MODEL_FAILED"));
    }

    private static String failureTypes(Throwable failure) {
        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (depth > 0) types.append(" <- ");
            types.append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return types.toString();
    }

    private static Optional<Integer> httpStatus(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof HttpTransportException transport && transport.getStatusCode() != null) {
                return Optional.of(transport.getStatusCode());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
