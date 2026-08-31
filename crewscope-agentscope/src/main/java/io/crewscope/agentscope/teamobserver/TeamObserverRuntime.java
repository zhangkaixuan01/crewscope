package io.crewscope.agentscope.teamobserver;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.agentscope.SafeModelFailures;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.agentscope.template.TemplateAgentSessionIdentity;
import io.crewscope.application.teamobserver.TeamObserverReadService;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.teamobserver.output.TeamObserverStructuredOutputSpecs;
import io.crewscope.application.teamobserver.output.TeamSummaryOutputV1;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.teamobserver.TeamSummaryResult;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/** Executes the read-only `team-observer@1` AgentScope loop over invocation-bound Tool authority. */
public final class TeamObserverRuntime {

    private final TeamObserverAgentProvider agents;
    private final TeamObserverTemplateRuntimeRegistry templates;
    private final TeamObserverReadService reads;
    private final TeamObserverToolkitFactory toolkits;
    private final TimeProvider timeProvider;
    private final TeamObserverPromptRenderer prompts = new TeamObserverPromptRenderer();
    private final Duration timeout;
    private final OperationalTelemetry telemetry;

    public TeamObserverRuntime(
            AgentTemplateRuntimeRegistry agents,
            TeamObserverTemplateRuntimeRegistry templates,
            TeamObserverReadService reads,
            TimeProvider timeProvider,
            Duration timeout) {
        this(Objects.requireNonNull(agents, "agents")::create,
                templates, reads, timeProvider, timeout, OperationalTelemetry.noop());
    }

    public TeamObserverRuntime(
            AgentTemplateRuntimeRegistry agents,
            TeamObserverTemplateRuntimeRegistry templates,
            TeamObserverReadService reads,
            TimeProvider timeProvider,
            Duration timeout,
            OperationalTelemetry telemetry) {
        this(Objects.requireNonNull(agents, "agents")::create,
                templates, reads, timeProvider, timeout, telemetry);
    }

    TeamObserverRuntime(
            TeamObserverAgentProvider agents,
            TeamObserverTemplateRuntimeRegistry templates,
            TeamObserverReadService reads,
            TimeProvider timeProvider,
            Duration timeout) {
        this(agents, templates, reads, timeProvider, timeout, OperationalTelemetry.noop());
    }

    TeamObserverRuntime(
            TeamObserverAgentProvider agents,
            TeamObserverTemplateRuntimeRegistry templates,
            TeamObserverReadService reads,
            TimeProvider timeProvider,
            Duration timeout,
            OperationalTelemetry telemetry) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.toolkits = new TeamObserverToolkitFactory(reads, templates);
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "Team Observer timeout must be between zero and 10 minutes");
        }
    }

    public Mono<TeamSummaryResult> summarize(TeamObserverRuntimeRequest request) {
        TeamObserverRuntimeRequest required = Objects.requireNonNull(request, "request");
        return Mono.defer(() -> observeSummary(required));
    }

    private Mono<TeamSummaryResult> observeSummary(TeamObserverRuntimeRequest required) {
        OperationalTelemetry.Observation observation = telemetry.start(
                OperationalTelemetry.Request.teamObserver());
        return summarizeAuthorized(required)
                .doOnSuccess(ignored -> observation.succeed())
                .doOnError(failure -> observation.fail(telemetryError(failure)))
                .doOnCancel(observation::cancel);
    }

    private Mono<TeamSummaryResult> summarizeAuthorized(TeamObserverRuntimeRequest required) {
        templates.requireRuntime(required.definition());
        TeamObserverEvidenceCatalog evidence = new TeamObserverEvidenceCatalog();
        Toolkit toolkit = toolkits.create(required.summaryRequest(), evidence);
        TemplateAgentBuildRequest build = new TemplateAgentBuildRequest(
                required.definition(),
                TemplateAgentSessionIdentity.teamObserver(required.session()),
                toolkit);
        RuntimeContext context = RuntimeContext.builder()
                .userId(required.session().agentScopeKey().userId())
                .sessionId(required.session().agentScopeKey().sessionId())
                .put(TeamObserverRuntimeSession.class, required.session())
                .build();
        JsonNode schema = JsonUtils.getJsonCodec().fromJson(
                TeamObserverTemplate.outputSchema(), JsonNode.class);

        return Mono.using(
                        () -> agents.create(build),
                        agent -> call(agent, required, schema, context),
                        HarnessAgent::close,
                        true)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, SafeModelFailures::sanitize)
                .map(output -> createResult(required, evidence, output));
    }

    private Mono<TeamSummaryOutputV1> call(
            HarnessAgent agent,
            TeamObserverRuntimeRequest request,
            JsonNode schema,
            RuntimeContext context) {
        String prompt = prompts.render(request.instruction());
        return agent.call(List.of(new UserMessage(prompt)), schema, context)
                .onErrorMap(TeamObserverRuntime::sanitizeModelFailure)
                .map(TeamObserverRuntime::decode);
    }

    private TeamSummaryResult createResult(
            TeamObserverRuntimeRequest request,
            TeamObserverEvidenceCatalog evidence,
            TeamSummaryOutputV1 output) {
        int maximum = request.summaryRequest().maxItemsPerSection();
        // Membership is checked once more after model execution to close revocation races.
        reads.requireAuthorized(request.summaryRequest());
        return TeamSummaryResult.create(
                request.summaryRequest(),
                request.definition().profile(),
                timeProvider.now(),
                evidence.resolve(TeamSummarySection.PROGRESS, output.progress(), maximum),
                evidence.resolve(TeamSummarySection.BLOCKERS, output.blockers(), maximum),
                evidence.resolve(
                        TeamSummarySection.REVIEW_BACKLOG, output.reviewBacklog(), maximum),
                evidence.resolve(
                        TeamSummarySection.PENDING_CONFIRMATIONS,
                        output.pendingConfirmations(),
                        maximum),
                evidence.resolve(TeamSummarySection.ANOMALIES, output.anomalies(), maximum));
    }

    private static TeamSummaryOutputV1 decode(Msg message) {
        Msg required = Objects.requireNonNull(message, "message");
        if (!required.hasStructuredData()) {
            throw new IllegalArgumentException(
                    "Team Observer model did not return structured output");
        }
        return TeamObserverStructuredOutputSpecs.TEAM_SUMMARY.requireValue(
                StrictStructuredOutputDecoder.decode(
                        required.getStructuredData(false),
                        TeamObserverStructuredOutputSpecs.TEAM_SUMMARY));
    }

    private static Throwable sanitizeModelFailure(Throwable failure) {
        if (failure instanceof DomainValidationException
                || failure instanceof IllegalArgumentException
                || failure instanceof PlatformExecutionSecurityException) {
            return failure;
        }
        return SafeModelFailures.sanitize(failure);
    }

    private static OperationalTelemetry.ErrorCode telemetryError(Throwable failure) {
        Throwable required = Objects.requireNonNull(failure, "failure");
        if (required instanceof PlatformExecutionSecurityException) {
            return OperationalTelemetry.ErrorCode.PERMISSION;
        }
        if (required instanceof DomainValidationException) {
            return OperationalTelemetry.ErrorCode.AUTHORIZATION_DRIFT;
        }
        if (required instanceof IllegalArgumentException) {
            return OperationalTelemetry.ErrorCode.OUTPUT_INVALID;
        }
        return switch (SafeModelFailures.safeCode(required)) {
            case "MODEL_TIMEOUT" -> OperationalTelemetry.ErrorCode.TIMEOUT;
            case "MODEL_RATE_LIMITED" -> OperationalTelemetry.ErrorCode.RATE_LIMITED;
            case "MODEL_AUTHENTICATION_FAILED" ->
                    OperationalTelemetry.ErrorCode.AUTHENTICATION;
            case "MODEL_REQUEST_REJECTED" -> OperationalTelemetry.ErrorCode.INVALID_RESPONSE;
            default -> OperationalTelemetry.ErrorCode.UNAVAILABLE;
        };
    }
}

@FunctionalInterface
interface TeamObserverAgentProvider {
    HarnessAgent create(TemplateAgentBuildRequest request);
}
