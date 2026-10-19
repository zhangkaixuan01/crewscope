package io.crewscope.agentscope.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.SafeModelFailures;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.agentscope.StructuredDeliveryCall;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.agentscope.template.TemplateAgentSessionIdentity;
import io.crewscope.application.knowledge.KnowledgeDistillationPort;
import io.crewscope.application.knowledge.output.DistilledDraftV1;
import io.crewscope.application.knowledge.output.KnowledgeDistillerStructuredOutputSpecs;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/**
 * Executes the tool-less `knowledge-distiller@1` single-turn structured-output call
 * (M10-A02b). One UserMessage carries the sanitized transcript; the frozen schema is the
 * only accepted answer shape, and every real Provider attempt appends one usage fact.
 */
public final class KnowledgeDistillerRuntime {

    private static final String STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION = """
            The previous knowledge distillation answer arrived as plain text instead of the
            required structured output. Produce the distilled draft now.
            Call generate_response exactly once; do not answer with text.

            Distill one Team knowledge draft from the sanitized execution transcript below.
            %s

            Return exactly title, content, and suggestedCategory.
            """;

    /** Draft plus the runtime observation the server adapter turns into the Port result. */
    public record Observation(
            KnowledgeDistillationPort.DistilledDraft draft,
            List<KnowledgeDistillationPort.AttemptUsage> attempts,
            Optional<String> observedModelName) {

        public Observation {
            draft = Objects.requireNonNull(draft, "draft");
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            observedModelName = Objects.requireNonNull(observedModelName, "observedModelName");
        }
    }

    private final KnowledgeDistillerAgentProvider agents;
    private final KnowledgeDistillerTemplateRuntimeRegistry templates;
    private final KnowledgeDistillerPromptRenderer prompts = new KnowledgeDistillerPromptRenderer();
    private final StructuredDeliveryCall<DistilledDraftV1> delivery =
            StructuredDeliveryCall.of(
                    "Knowledge Distiller",
                    STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION,
                    KnowledgeDistillerRuntime::decode);
    private final Duration timeout;

    public KnowledgeDistillerRuntime(
            AgentTemplateRuntimeRegistry agents,
            KnowledgeDistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this(Objects.requireNonNull(agents, "agents")::create, templates, timeout);
    }

    KnowledgeDistillerRuntime(
            KnowledgeDistillerAgentProvider agents,
            KnowledgeDistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "Knowledge Distiller timeout must be between zero and 10 minutes");
        }
    }

    public Mono<Observation> distill(KnowledgeDistillerRuntimeRequest request) {
        KnowledgeDistillerRuntimeRequest required = Objects.requireNonNull(request, "request");
        return Mono.defer(() -> distillAuthorized(required));
    }

    private Mono<Observation> distillAuthorized(KnowledgeDistillerRuntimeRequest required) {
        templates.requireRuntime(required.definition());
        TemplateAgentBuildRequest build = new TemplateAgentBuildRequest(
                required.definition(),
                TemplateAgentSessionIdentity.distiller(required.session()),
                new Toolkit());
        KnowledgeDistillerUsageAccumulator usage = new KnowledgeDistillerUsageAccumulator();
        RuntimeContext context = RuntimeContext.builder()
                .userId(required.session().agentScopeKey().userId())
                .sessionId(required.session().agentScopeKey().sessionId())
                .put(KnowledgeDistillerRuntimeSession.class, required.session())
                .put(KnowledgeDistillerUsageAccumulator.class, usage)
                .build();
        JsonNode schema = JsonUtils.getJsonCodec().fromJson(
                KnowledgeDistillerTemplate.outputSchema(), JsonNode.class);
        String prompt = prompts.render(
                required.sanitizedSourceText(), required.requestedCategoryHint());

        return Mono.using(
                        () -> agents.create(build),
                        agent -> call(agent, prompt, schema, context),
                        HarnessAgent::close,
                        true)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, SafeModelFailures::sanitize)
                .map(output -> new Observation(
                        new KnowledgeDistillationPort.DistilledDraft(
                                output.title(), output.content(), output.suggestedCategory()),
                        usage.attempts(),
                        usage.observedModelName()));
    }

    private Mono<DistilledDraftV1> call(
            HarnessAgent agent, String prompt, JsonNode schema, RuntimeContext context) {
        return delivery.execute(agent, prompt, schema, context);
    }

    private static DistilledDraftV1 decode(Msg message) {
        return KnowledgeDistillerStructuredOutputSpecs.DISTILLED_DRAFT.requireValue(
                StrictStructuredOutputDecoder.decode(
                        Objects.requireNonNull(message, "message").getStructuredData(false),
                        KnowledgeDistillerStructuredOutputSpecs.DISTILLED_DRAFT));
    }
}

@FunctionalInterface
interface KnowledgeDistillerAgentProvider {
    HarnessAgent create(TemplateAgentBuildRequest request);
}
