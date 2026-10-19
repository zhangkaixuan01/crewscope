package io.crewscope.agentscope.distiller;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.SafeModelFailures;
import io.crewscope.agentscope.StructuredDeliveryCall;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.agentscope.template.TemplateAgentSessionIdentity;
import io.crewscope.application.distiller.DistillationAttemptUsage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * The single Distiller call pipeline shared by the Knowledge and Skill runtimes (M13-C03):
 * one tool-less structured-output turn over the exact TEAM contract, one usage fact per
 * real Provider attempt, a bounded single recovery and sanitized failures. The per-kind
 * differences (label, recovery instruction, output schema and draft decoder) are supplied
 * by the owning runtime; prompt rendering stays with the runtime.
 */
public final class DistillerCallPipeline<DRAFT> {

    /** Draft plus the runtime observation the server adapter turns into the Port result. */
    public record Outcome<DRAFT>(
            DRAFT draft,
            List<DistillationAttemptUsage> attempts,
            Optional<String> observedModelName) {

        public Outcome {
            draft = Objects.requireNonNull(draft, "draft");
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            observedModelName = Objects.requireNonNull(observedModelName, "observedModelName");
        }
    }

    @FunctionalInterface
    public interface AgentProvider {

        HarnessAgent create(TemplateAgentBuildRequest request);
    }

    private final StructuredDeliveryCall<DRAFT> delivery;
    private final Supplier<String> outputSchema;
    private final AgentProvider agents;
    private final DistillerTemplateRuntimeRegistry templates;
    private final Duration timeout;

    public DistillerCallPipeline(
            String label,
            String recoveryInstruction,
            Supplier<String> outputSchema,
            Function<Msg, DRAFT> decoder,
            AgentProvider agents,
            DistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        String requiredLabel = Objects.requireNonNull(label, "label");
        this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    requiredLabel + " timeout must be between zero and 10 minutes");
        }
        this.delivery = StructuredDeliveryCall.of(
                requiredLabel,
                Objects.requireNonNull(recoveryInstruction, "recoveryInstruction"),
                Objects.requireNonNull(decoder, "decoder"));
    }

    /** Runs one authorized Distiller call; the prompt is already whitelist-rendered. */
    public Mono<Outcome<DRAFT>> distill(
            AgentTemplateRuntimeDefinition definition,
            DistillerRuntimeSession session,
            String prompt) {
        AgentTemplateRuntimeDefinition requiredDefinition =
                Objects.requireNonNull(definition, "definition");
        DistillerRuntimeSession requiredSession = Objects.requireNonNull(session, "session");
        String requiredPrompt = Objects.requireNonNull(prompt, "prompt");
        templates.requireRuntime(requiredDefinition);
        TemplateAgentBuildRequest build = new TemplateAgentBuildRequest(
                requiredDefinition,
                TemplateAgentSessionIdentity.distiller(requiredSession),
                new Toolkit());
        DistillerUsageAccumulator usage = new DistillerUsageAccumulator();
        RuntimeContext context = RuntimeContext.builder()
                .userId(requiredSession.agentScopeKey().userId())
                .sessionId(requiredSession.agentScopeKey().sessionId())
                .put(DistillerRuntimeSession.class, requiredSession)
                .put(DistillerUsageAccumulator.class, usage)
                .build();
        JsonNode schema = JsonUtils.getJsonCodec().fromJson(outputSchema.get(), JsonNode.class);

        return Mono.using(
                        () -> agents.create(build),
                        agent -> delivery.execute(agent, requiredPrompt, schema, context),
                        HarnessAgent::close,
                        true)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, SafeModelFailures::sanitize)
                .map(draft -> new Outcome<>(
                        draft, usage.attempts(), usage.observedModelName()));
    }
}
