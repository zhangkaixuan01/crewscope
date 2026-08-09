package io.crewscope.agentscope;

import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import reactor.core.Exceptions;

/** Maps provider failures to a bounded, message-free code vocabulary. */
final class AgentCallFailureClassifier {

    private AgentCallFailureClassifier() {}

    static String classify(Throwable failure) {
        Throwable current = unwrap(failure);
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof SafeModelExecutionException safe) {
                return safe.safeCode();
            }
            if (current instanceof TimeoutException) {
                return "MODEL_TIMEOUT";
            }
            if (current instanceof ModelException modelFailure
                    && modelFailure.getMessage() != null
                    && modelFailure.getMessage().startsWith("Model request timeout after ")) {
                // AgentScope ModelUtils creates this exact provider-neutral timeout message.
                return "MODEL_TIMEOUT";
            }
            if (current instanceof HttpTransportException transport) {
                return statusCode(transport.getStatusCode());
            }
            if (current instanceof ModelHttpException modelHttp) {
                return statusCode(modelHttp.getStatusCode());
            }
            if (current instanceof IOException) {
                return "MODEL_TRANSPORT_ERROR";
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return "MODEL_EXECUTION_FAILED";
    }

    static Throwable unwrap(Throwable failure) {
        return Exceptions.isRetryExhausted(failure) && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static String statusCode(Integer status) {
        if (status == null) {
            return "MODEL_TRANSPORT_ERROR";
        }
        if (status == 429) {
            return "MODEL_RATE_LIMITED";
        }
        if (status == 401 || status == 403) {
            return "MODEL_AUTHENTICATION_FAILED";
        }
        if (status >= 500 && status < 600) {
            return "MODEL_PROVIDER_UNAVAILABLE";
        }
        if (status >= 400 && status < 500) {
            return "MODEL_REQUEST_REJECTED";
        }
        return "MODEL_EXECUTION_FAILED";
    }
}
