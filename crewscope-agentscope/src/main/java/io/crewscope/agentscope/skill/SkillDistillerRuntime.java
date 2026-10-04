package io.crewscope.agentscope.skill;

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
import io.crewscope.application.skill.SkillDistillationPort;
import io.crewscope.application.skill.output.DistilledSkillDraftV1;
import io.crewscope.application.skill.output.SkillDistillerStructuredOutputSpecs;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/**
 * Executes the tool-less `skill-distiller@1` single-turn structured-output call
 * (M10-A03b). One UserMessage carries the sanitized transcript; the frozen schema is
 * the only accepted answer shape, and every real Provider attempt appends one usage
 * fact.
 */
public final class SkillDistillerRuntime {

    /** Draft plus the runtime observation the server adapter turns into the Port result. */
    public record Observation(
            SkillDistillationPort.DistilledSkillDraft draft,
            List<SkillDistillationPort.AttemptUsage> attempts,
            Optional<String> observedModelName) {

        public Observation {
            draft = Objects.requireNonNull(draft, "draft");
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            observedModelName = Objects.requireNonNull(observedModelName, "observedModelName");
        }
    }

    private final SkillDistillerAgentProvider agents;
    private final SkillDistillerTemplateRuntimeRegistry templates;
    private final SkillDistillerPromptRenderer prompts = new SkillDistillerPromptRenderer();
    private final Duration timeout;

    public SkillDistillerRuntime(
            AgentTemplateRuntimeRegistry agents,
            SkillDistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this(Objects.requireNonNull(agents, "agents")::create, templates, timeout);
    }

    SkillDistillerRuntime(
            SkillDistillerAgentProvider agents,
            SkillDistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "Skill Distiller timeout must be between zero and 10 minutes");
        }
    }

    public Mono<Observation> distill(SkillDistillerRuntimeRequest request) {
        SkillDistillerRuntimeRequest required = Objects.requireNonNull(request, "request");
        return Mono.defer(() -> distillAuthorized(required));
    }

    private Mono<Observation> distillAuthorized(SkillDistillerRuntimeRequest required) {
        templates.requireRuntime(required.definition());
        TemplateAgentBuildRequest build = new TemplateAgentBuildRequest(
                required.definition(),
                TemplateAgentSessionIdentity.skillDistiller(required.session()),
                new Toolkit());
        SkillDistillerUsageAccumulator usage = new SkillDistillerUsageAccumulator();
        RuntimeContext context = RuntimeContext.builder()
                .userId(required.session().agentScopeKey().userId())
                .sessionId(required.session().agentScopeKey().sessionId())
                .put(SkillDistillerRuntimeSession.class, required.session())
                .put(SkillDistillerUsageAccumulator.class, usage)
                .build();
        JsonNode schema = JsonUtils.getJsonCodec().fromJson(
                SkillDistillerTemplate.outputSchema(), JsonNode.class);
        String prompt = prompts.render(
                required.sanitizedSourceText(), required.skillKey());

        return Mono.using(
                        () -> agents.create(build),
                        agent -> agent.call(List.of(new UserMessage(prompt)), schema, context)
                                .onErrorMap(SkillDistillerRuntime::sanitizeModelFailure)
                                .map(SkillDistillerRuntime::decode),
                        HarnessAgent::close,
                        true)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, SafeModelFailures::sanitize)
                .map(output -> new Observation(
                        new SkillDistillationPort.DistilledSkillDraft(
                                output.description(), output.body()),
                        usage.attempts(),
                        usage.observedModelName()));
    }

    private static DistilledSkillDraftV1 decode(Msg message) {
        Msg required = Objects.requireNonNull(message, "message");
        if (!required.hasStructuredData()) {
            throw new IllegalArgumentException(
                    "Skill Distiller model did not return structured output");
        }
        return SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL.requireValue(
                StrictStructuredOutputDecoder.decode(
                        required.getStructuredData(false),
                        SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL));
    }

    private static Throwable sanitizeModelFailure(Throwable failure) {
        if (failure instanceof DomainValidationException
                || failure instanceof IllegalArgumentException
                || failure instanceof PlatformExecutionSecurityException) {
            return failure;
        }
        // Same observability lesson as the Reviewer (M9b-Q02 defect 20): keep the
        // sanitization contract (no provider text) but record the bounded code and the
        // originating class, or a fast local failure becomes an opaque MODEL_FAILURE.
        org.slf4j.LoggerFactory.getLogger(SkillDistillerRuntime.class).warn(
                "Skill Distiller model call failed [code={}, cause={}, root={}]",
                SafeModelFailures.safeCode(failure),
                failure.getClass().getName(),
                failure.getCause() == null ? "none" : failure.getCause().getClass().getName());
        return SafeModelFailures.sanitize(failure);
    }
}

@FunctionalInterface
interface SkillDistillerAgentProvider {
    HarnessAgent create(TemplateAgentBuildRequest request);
}
