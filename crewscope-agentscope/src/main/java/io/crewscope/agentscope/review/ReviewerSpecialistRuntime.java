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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/** Executes reviewer@1 with native AgentScope structured output and CrewScope evidence authority. */
public final class ReviewerSpecialistRuntime {

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
        JsonNode schema = schema();
        RuntimeContext context = RuntimeContext.builder()
                .userId(required.agentBuild().identity().agentScopeKey().userId())
                .sessionId(required.agentBuild().identity().agentScopeKey().sessionId())
                .build();
        return Mono.using(
                        () -> agents.create(required.agentBuild()),
                        agent -> call(agent, required, schema, context),
                        HarnessAgent::close,
                        true)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, SafeModelFailures::sanitize);
    }

    private Mono<ReviewFindingBatchResult> call(
            HarnessAgent agent,
            ReviewerSpecialistRequest request,
            JsonNode schema,
            RuntimeContext runtimeContext) {
        return agent.call(
                        List.of(new UserMessage(prompts.render(request.contextPackage()))),
                        schema,
                        runtimeContext)
                .onErrorMap(ReviewerSpecialistRuntime::sanitizeModelFailure)
                .map(message -> decode(message).toCandidates())
                .map(candidates -> recorder.record(
                        request.reviewRequest(),
                        request.contextPackage(),
                        candidates,
                        request.expectedRequestVersion(),
                        request.reviewerAgent(),
                        request.observedAt()));
    }

    private static ReviewFindingListV1 decode(Msg message) {
        Msg required = Objects.requireNonNull(message, "message");
        if (!required.hasStructuredData()) {
            throw new IllegalArgumentException("Reviewer model did not return structured output");
        }
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
        if (failure instanceof DomainValidationException
                || failure instanceof IllegalArgumentException) {
            return failure;
        }
        return SafeModelFailures.sanitize(failure);
    }
}

@FunctionalInterface
interface ReviewerAgentProvider {
    HarnessAgent create(io.crewscope.agentscope.template.TemplateAgentBuildRequest request);
}
