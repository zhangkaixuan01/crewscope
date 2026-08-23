package io.crewscope.agentscope;

/** Public adapter hook that removes provider error messages at an external model boundary. */
public final class SafeModelFailures {

    private SafeModelFailures() {}

    public static RuntimeException sanitize(Throwable failure) {
        return new SafeModelExecutionException(
                AgentCallFailureClassifier.classify(AgentCallFailureClassifier.unwrap(failure)));
    }
}
