package io.crewscope.agentscope.review;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.SafeModelFailures;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.agentscope.StructuredDeliveryCall;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.application.review.ReviewFindingBatchRecorder;
import io.crewscope.application.review.ReviewFindingBatchResult;
import io.crewscope.application.review.output.ReviewFindingListV1;
import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
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
    private final StructuredDeliveryCall<ReviewFindingListV1> delivery =
            StructuredDeliveryCall.of(
                    "Reviewer", STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION, ReviewerSpecialistRuntime::decode);
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
        return delivery
                .execute(agent, prompts.render(request.contextPackage()), schema, runtimeContext)
                .map(ReviewFindingListV1::toCandidates);
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
}

@FunctionalInterface
interface ReviewerAgentProvider {
    HarnessAgent create(io.crewscope.agentscope.template.TemplateAgentBuildRequest request);
}
