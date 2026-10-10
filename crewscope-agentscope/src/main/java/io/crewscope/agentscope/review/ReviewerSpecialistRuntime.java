package io.crewscope.agentscope.review;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.SafeModelFailures;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.application.review.ReviewFindingBatchRecorder;
import io.crewscope.application.review.ReviewFindingBatchResult;
import io.crewscope.application.review.output.ReviewFindingListV1;
import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.review.ReviewFindingCandidate;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/** Executes reviewer@1 with native AgentScope structured output and CrewScope evidence authority. */
public final class ReviewerSpecialistRuntime {

    private static final String STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION = """
            The previous reviewer answer arrived as plain text instead of the required structured
            output. Produce the review findings now. Call generate_response exactly once; do not
            answer with text.

            Review the following context package:
            %s

            Return schemaVersion=1. findings must be an array and may be empty (a clean review).
            """;

    private final ReviewerAgentProvider agents;
    private final ReviewFindingBatchRecorder recorder;
    private final ReviewerContextPromptRenderer prompts = new ReviewerContextPromptRenderer();
    private final Duration timeout;

    public ReviewerSpecialistRuntime(
            AgentTemplateRuntimeRegistry agents,
            ReviewFindingBatchRecorder recorder,
            Duration timeout) {
        this(Objects.requireNonNull(agents, "agents")::create, recorder, timeout);
    }

    ReviewerSpecialistRuntime(
            ReviewerAgentProvider agents,
            ReviewFindingBatchRecorder recorder,
            Duration timeout) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Reviewer timeout must be between zero and 30 minutes");
        }
    }

    public Mono<ReviewFindingBatchResult> review(ReviewerSpecialistRequest request) {
        ReviewerSpecialistRequest required = Objects.requireNonNull(request, "request");
        return analyze(required).map(candidates -> recorder.record(
                required.reviewRequest(),
                required.contextPackage(),
                candidates,
                required.expectedRequestVersion(),
                required.reviewerAgent(),
                required.observedAt()));
    }

    /** Returns decoded candidates so the application layer can own the completion transaction. */
    public Mono<List<ReviewFindingCandidate>> analyze(ReviewerSpecialistRequest request) {
        ReviewerSpecialistRequest required = Objects.requireNonNull(request, "request");
        JsonNode schema = schema();
        // The REVIEW session is the trusted boundary fact the reviewer chain validates
        // (ReviewerRuntimeContextMiddleware): server-created, purpose-pinned, and the source
        // of the AgentScope coordinates below.
        TaskAgentRuntimeSession reviewSession =
                required.agentBuild().identity().requireTaskSession();
        RuntimeContext context = RuntimeContext.builder()
                .userId(reviewSession.agentScopeKey().userId())
                .sessionId(reviewSession.agentScopeKey().sessionId())
                .put(TaskAgentRuntimeSession.class, reviewSession)
                .build();
        return Mono.using(
                        () -> agents.create(required.agentBuild()),
                        agent -> call(agent, required, schema, context),
                        HarnessAgent::close,
                        true)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, SafeModelFailures::sanitize);
    }

    private Mono<List<ReviewFindingCandidate>> call(
            HarnessAgent agent,
            ReviewerSpecialistRequest request,
            JsonNode schema,
            RuntimeContext runtimeContext) {
        // Defect 7 (M10-Q02): DeepSeek occasionally answers with plain text instead of the
        // structured tool call even on HTTP 200 — the transport retry never sees it. Take the
        // same bounded native recovery the Coding runtime uses: re-ask the same agent once
        // with an explicit structured-output instruction, and treat a still-invalid delivery
        // as a sanitized terminal model failure instead of a bare IllegalArgumentException.
        return agent.call(
                        List.of(new UserMessage(prompts.render(request.contextPackage()))),
                        schema,
                        runtimeContext)
                .flatMap(message -> validStructuredDelivery(message)
                        ? Mono.just(message)
                        : recoverStructuredDelivery(agent, request, schema, runtimeContext))
                .map(message -> decode(message).toCandidates())
                .onErrorMap(ReviewerSpecialistRuntime::sanitizeModelFailure);
    }

    private Mono<Msg> recoverStructuredDelivery(
            HarnessAgent agent,
            ReviewerSpecialistRequest request,
            JsonNode schema,
            RuntimeContext runtimeContext) {
        return agent.call(
                        List.of(new UserMessage(STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION.formatted(
                                prompts.render(request.contextPackage())))),
                        schema,
                        runtimeContext)
                .flatMap(recovered -> validStructuredDelivery(recovered)
                        ? Mono.just(recovered)
                        : Mono.error(new StructuredDeliveryException()));
    }

    private static boolean validStructuredDelivery(Msg result) {
        if (!result.hasStructuredData()) {
            return false;
        }
        try {
            StrictStructuredOutputDecoder.decode(
                    result.getStructuredData(false),
                    ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST);
            return true;
        } catch (RuntimeException invalidDelivery) {
            // Provider output is untrusted: keep its content out of logs and take the bounded
            // recovery path instead of failing the review outright.
            return false;
        }
    }

    private static ReviewFindingListV1 decode(Msg message) {
        Msg required = Objects.requireNonNull(message, "message");
        return (ReviewFindingListV1) StrictStructuredOutputDecoder.decode(
                required.getStructuredData(false),
                ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST);
    }

    private static JsonNode schema() {
        String json = JsonUtils.getJsonCodec().toJson(
                ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST
                        .strictJsonSchema()
                        .orElseThrow());
        return JsonUtils.getJsonCodec().fromJson(json, JsonNode.class);
    }

    private static Throwable sanitizeModelFailure(Throwable failure) {
        if (failure instanceof StructuredDeliveryException) {
            // Both the call and its bounded recovery delivered an unusable shape — a terminal
            // model failure, not a programming error: sanitize like any provider failure so
            // the bounded code (MODEL_EXECUTION_FAILED) reaches the caller instead of a raw
            // IllegalArgumentException.
            org.slf4j.LoggerFactory.getLogger(ReviewerSpecialistRuntime.class).warn(
                    "Reviewer model never returned structured output after one recovery attempt");
            return SafeModelFailures.sanitize(failure);
        }
        if (failure instanceof DomainValidationException
                || failure instanceof IllegalArgumentException) {
            return failure;
        }
        // Defect 20 observation (M9b-Q02): the reviewer call fails in ~49ms with no model
        // request on the wire — the wrapping below erased both the code and the originating
        // class. Keep the sanitization contract (no provider text) but record the bounded
        // code and the exception class name.
        org.slf4j.LoggerFactory.getLogger(ReviewerSpecialistRuntime.class).warn(
                "Reviewer model call failed [code={}, cause={}, root={}]",
                SafeModelFailures.safeCode(failure),
                failure.getClass().getName(),
                failure.getCause() == null ? "none" : failure.getCause().getClass().getName());
        return SafeModelFailures.sanitize(failure);
    }

    /** Internal marker for a delivery that stayed unparseable through one bounded recovery. */
    private static final class StructuredDeliveryException extends RuntimeException {

        private StructuredDeliveryException() {
            super("Reviewer model did not produce a valid structured delivery");
        }
    }
}

@FunctionalInterface
interface ReviewerAgentProvider {
    HarnessAgent create(io.crewscope.agentscope.template.TemplateAgentBuildRequest request);
}
