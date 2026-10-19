package io.crewscope.agentscope.knowledge;

import io.agentscope.core.message.Msg;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.agentscope.distiller.DistillerCallPipeline;
import io.crewscope.agentscope.distiller.DistillerPromptRenderer;
import io.crewscope.agentscope.distiller.DistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.application.distiller.DistillationAttemptUsage;
import io.crewscope.application.knowledge.KnowledgeDistillationPort;
import io.crewscope.application.knowledge.output.DistilledDraftV1;
import io.crewscope.application.knowledge.output.KnowledgeDistillerStructuredOutputSpecs;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Executes the tool-less `knowledge-distiller@1` single-turn structured-output call
 * (M10-A02b). One UserMessage carries the sanitized transcript; the frozen schema is
 * the only accepted answer shape, and every real Provider attempt appends one usage
 * fact. The call pipeline itself is shared with the Skill Distiller (M13-C03); this
 * class supplies the knowledge-specific recovery instruction, schema and decoder.
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
            List<DistillationAttemptUsage> attempts,
            Optional<String> observedModelName) {

        public Observation {
            draft = Objects.requireNonNull(draft, "draft");
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            observedModelName = Objects.requireNonNull(observedModelName, "observedModelName");
        }
    }

    private final DistillerCallPipeline<DistilledDraftV1> pipeline;
    private final DistillerPromptRenderer prompts = new DistillerPromptRenderer();

    public KnowledgeDistillerRuntime(
            AgentTemplateRuntimeRegistry agents,
            DistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this(Objects.requireNonNull(agents, "agents")::create, templates, timeout);
    }

    KnowledgeDistillerRuntime(
            DistillerCallPipeline.AgentProvider agents,
            DistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this.pipeline = new DistillerCallPipeline<>(
                "Knowledge Distiller",
                STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION,
                KnowledgeDistillerTemplate::outputSchema,
                KnowledgeDistillerRuntime::decode,
                agents,
                templates,
                timeout);
    }

    public Mono<Observation> distill(KnowledgeDistillerRuntimeRequest request) {
        KnowledgeDistillerRuntimeRequest required = Objects.requireNonNull(request, "request");
        return Mono.defer(() -> pipeline.distill(
                        required.definition(),
                        required.session(),
                        prompts.renderKnowledge(
                                required.sanitizedSourceText(),
                                required.requestedCategoryHint()))
                .map(outcome -> new Observation(
                        new KnowledgeDistillationPort.DistilledDraft(
                                outcome.draft().title(),
                                outcome.draft().content(),
                                outcome.draft().suggestedCategory()),
                        outcome.attempts(),
                        outcome.observedModelName())));
    }

    private static DistilledDraftV1 decode(Msg message) {
        return KnowledgeDistillerStructuredOutputSpecs.DISTILLED_DRAFT.requireValue(
                StrictStructuredOutputDecoder.decode(
                        Objects.requireNonNull(message, "message").getStructuredData(false),
                        KnowledgeDistillerStructuredOutputSpecs.DISTILLED_DRAFT));
    }
}
