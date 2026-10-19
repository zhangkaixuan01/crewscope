package io.crewscope.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Bounded structured-delivery call shared by the single-turn structured-output runtimes
 * (M13-C01, extracted from the four hand-written Reviewer/distiller shapes). One model
 * call; when the delivery is not the frozen schema shape the same agent is re-asked
 * exactly once with an explicit structured-output instruction; a delivery that stays
 * invalid is sanitized into a terminal model failure instead of a bare
 * {@code IllegalArgumentException}.
 *
 * <p>Ownership boundary: the caller keeps the {@code Mono.using} session scope, the
 * timeout, and any usage/telemetry accumulation on the {@link RuntimeContext}; this
 * component owns only the call-recover-decode-sanitize chain. The {@code decoder} must
 * both validate and convert a delivery (an invalid shape throws a runtime exception),
 * so it is applied once for validation and once for the emitted value.
 */
public final class StructuredDeliveryCall<T> {

    private static final Logger log = LoggerFactory.getLogger(StructuredDeliveryCall.class);

    private final String modelLabel;
    private final String recoveryInstruction;
    private final Function<Msg, T> decoder;

    private StructuredDeliveryCall(
            String modelLabel, String recoveryInstruction, Function<Msg, T> decoder) {
        this.modelLabel = Objects.requireNonNull(modelLabel, "modelLabel");
        this.recoveryInstruction = Objects.requireNonNull(recoveryInstruction, "recoveryInstruction");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public static <T> StructuredDeliveryCall<T> of(
            String modelLabel, String recoveryInstruction, Function<Msg, T> decoder) {
        return new StructuredDeliveryCall<>(modelLabel, recoveryInstruction, decoder);
    }

    public Mono<T> execute(
            HarnessAgent agent, String prompt, JsonNode schema, RuntimeContext context) {
        HarnessAgent requiredAgent = Objects.requireNonNull(agent, "agent");
        String requiredPrompt = Objects.requireNonNull(prompt, "prompt");
        JsonNode requiredSchema = Objects.requireNonNull(schema, "schema");
        RuntimeContext requiredContext = Objects.requireNonNull(context, "context");
        return requiredAgent
                .call(List.of(new UserMessage(requiredPrompt)), requiredSchema, requiredContext)
                .flatMap(message -> validStructuredDelivery(message)
                        ? Mono.just(message)
                        : recoverStructuredDelivery(
                                requiredAgent, requiredPrompt, requiredSchema, requiredContext))
                .map(decoder)
                .onErrorMap(this::sanitizeModelFailure);
    }

    private Mono<Msg> recoverStructuredDelivery(
            HarnessAgent agent, String prompt, JsonNode schema, RuntimeContext context) {
        return agent
                .call(
                        List.of(new UserMessage(recoveryInstruction.formatted(prompt))),
                        schema,
                        context)
                .flatMap(recovered -> validStructuredDelivery(recovered)
                        ? Mono.just(recovered)
                        : Mono.error(new StructuredDeliveryException(modelLabel)));
    }

    private boolean validStructuredDelivery(Msg message) {
        if (!message.hasStructuredData()) {
            return false;
        }
        try {
            decoder.apply(message);
            return true;
        } catch (RuntimeException invalidDelivery) {
            // Provider output is untrusted: keep its content out of logs and take the bounded
            // recovery path instead of failing the call outright.
            return false;
        }
    }

    private Throwable sanitizeModelFailure(Throwable failure) {
        if (failure instanceof StructuredDeliveryException) {
            // Both the call and its bounded recovery delivered an unusable shape — a terminal
            // model failure, not a programming error: sanitize like any provider failure so
            // the bounded code (MODEL_EXECUTION_FAILED) reaches the caller instead of a raw
            // IllegalArgumentException.
            log.warn("{} model never returned structured output after one recovery attempt",
                    modelLabel);
            return SafeModelFailures.sanitize(failure);
        }
        if (failure instanceof DomainValidationException
                || failure instanceof IllegalArgumentException
                || failure instanceof PlatformExecutionSecurityException) {
            return failure;
        }
        // Keep the sanitization contract (no provider text) but record the bounded code and
        // the originating class, or a fast local failure becomes an opaque MODEL_FAILURE
        // (M9b-Q02 defect 20 lesson).
        log.warn("{} model call failed [code={}, cause={}, root={}]",
                modelLabel,
                SafeModelFailures.safeCode(failure),
                failure.getClass().getName(),
                failure.getCause() == null ? "none" : failure.getCause().getClass().getName());
        return SafeModelFailures.sanitize(failure);
    }

    /** Marker for a delivery that stayed unparseable through one bounded recovery. */
    private static final class StructuredDeliveryException extends RuntimeException {

        private StructuredDeliveryException(String modelLabel) {
            super(modelLabel + " model did not produce a valid structured delivery");
        }
    }
}
