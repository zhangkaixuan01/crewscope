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

    private static final String STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION = """
            The previous skill distillation answer arrived as plain text instead of the required
            structured output. Produce the Skill draft now. Call generate_response exactly once;
            do not answer with text.

            Distill one Team Skill draft for the requested skill key from the sanitized
            execution transcript below.
            %s

            Return exactly description and body.
            """;

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
                        agent -> call(agent, prompt, schema, context),
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

    private Mono<DistilledSkillDraftV1> call(
            HarnessAgent agent, String prompt, JsonNode schema, RuntimeContext context) {
        // Same shape as the Reviewer (M10-Q02 defect 7): the model occasionally answers with
        // plain text instead of the structured tool call even on HTTP 200 — the transport
        // retry never sees it. Re-ask the same agent once with an explicit structured-output
        // instruction, and treat a still-invalid delivery as a sanitized terminal model
        // failure instead of a bare IllegalArgumentException.
        return agent.call(List.of(new UserMessage(prompt)), schema, context)
                .flatMap(message -> validStructuredDelivery(message)
                        ? Mono.just(message)
                        : recoverStructuredDelivery(agent, prompt, schema, context))
                .map(SkillDistillerRuntime::decode)
                .onErrorMap(SkillDistillerRuntime::sanitizeModelFailure);
    }

    private Mono<Msg> recoverStructuredDelivery(
            HarnessAgent agent, String prompt, JsonNode schema, RuntimeContext context) {
        return agent.call(
                        List.of(new UserMessage(
                                STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION.formatted(prompt))),
                        schema,
                        context)
                .flatMap(recovered -> validStructuredDelivery(recovered)
                        ? Mono.just(recovered)
                        : Mono.error(new StructuredDeliveryException()));
    }

    private static boolean validStructuredDelivery(Msg message) {
        if (!message.hasStructuredData()) {
            return false;
        }
        try {
            SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL.requireValue(
                    StrictStructuredOutputDecoder.decode(
                            message.getStructuredData(false),
                            SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL));
            return true;
        } catch (RuntimeException invalidDelivery) {
            // Provider output is untrusted: keep its content out of logs and take the bounded
            // recovery path instead of failing the distillation outright.
            return false;
        }
    }

    private static DistilledSkillDraftV1 decode(Msg message) {
        return SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL.requireValue(
                StrictStructuredOutputDecoder.decode(
                        Objects.requireNonNull(message, "message").getStructuredData(false),
                        SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL));
    }

    private static Throwable sanitizeModelFailure(Throwable failure) {
        if (failure instanceof StructuredDeliveryException) {
            // Both the call and its bounded recovery delivered an unusable shape — a terminal
            // model failure, not a programming error: sanitize like any provider failure so
            // the bounded code (MODEL_EXECUTION_FAILED) reaches the caller instead of a raw
            // IllegalArgumentException.
            org.slf4j.LoggerFactory.getLogger(SkillDistillerRuntime.class).warn(
                    "Skill Distiller model never returned structured output after one recovery attempt");
            return SafeModelFailures.sanitize(failure);
        }
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

    /** Internal marker for a delivery that stayed unparseable through one bounded recovery. */
    private static final class StructuredDeliveryException extends RuntimeException {

        private StructuredDeliveryException() {
            super("Skill Distiller model did not produce a valid structured delivery");
        }
    }
}

@FunctionalInterface
interface SkillDistillerAgentProvider {
    HarnessAgent create(TemplateAgentBuildRequest request);
}
