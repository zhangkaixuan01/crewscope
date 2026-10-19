package io.crewscope.agentscope.skill;

import io.agentscope.core.message.Msg;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.agentscope.distiller.DistillerCallPipeline;
import io.crewscope.agentscope.distiller.DistillerPromptRenderer;
import io.crewscope.agentscope.distiller.DistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.application.distiller.DistillationAttemptUsage;
import io.crewscope.application.skill.SkillDistillationPort;
import io.crewscope.application.skill.output.DistilledSkillDraftV1;
import io.crewscope.application.skill.output.SkillDistillerStructuredOutputSpecs;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Executes the tool-less `skill-distiller@1` single-turn structured-output call
 * (M10-A03b). One UserMessage carries the sanitized transcript; the frozen schema is
 * the only accepted answer shape, and every real Provider attempt appends one usage
 * fact. The call pipeline itself is shared with the Knowledge Distiller (M13-C03); this
 * class supplies the skill-specific recovery instruction, schema and decoder.
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
            List<DistillationAttemptUsage> attempts,
            Optional<String> observedModelName) {

        public Observation {
            draft = Objects.requireNonNull(draft, "draft");
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            observedModelName = Objects.requireNonNull(observedModelName, "observedModelName");
        }
    }

    private final DistillerCallPipeline<DistilledSkillDraftV1> pipeline;
    private final DistillerPromptRenderer prompts = new DistillerPromptRenderer();

    public SkillDistillerRuntime(
            AgentTemplateRuntimeRegistry agents,
            DistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this(Objects.requireNonNull(agents, "agents")::create, templates, timeout);
    }

    SkillDistillerRuntime(
            DistillerCallPipeline.AgentProvider agents,
            DistillerTemplateRuntimeRegistry templates,
            Duration timeout) {
        this.pipeline = new DistillerCallPipeline<>(
                "Skill Distiller",
                STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION,
                SkillDistillerTemplate::outputSchema,
                SkillDistillerRuntime::decode,
                agents,
                templates,
                timeout);
    }

    public Mono<Observation> distill(SkillDistillerRuntimeRequest request) {
        SkillDistillerRuntimeRequest required = Objects.requireNonNull(request, "request");
        return Mono.defer(() -> pipeline.distill(
                        required.definition(),
                        required.session(),
                        prompts.renderSkill(
                                required.sanitizedSourceText(),
                                required.skillKey()))
                .map(outcome -> new Observation(
                        new SkillDistillationPort.DistilledSkillDraft(
                                outcome.draft().description(),
                                outcome.draft().body()),
                        outcome.attempts(),
                        outcome.observedModelName())));
    }

    private static DistilledSkillDraftV1 decode(Msg message) {
        return SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL.requireValue(
                StrictStructuredOutputDecoder.decode(
                        Objects.requireNonNull(message, "message").getStructuredData(false),
                        SkillDistillerStructuredOutputSpecs.DISTILLED_SKILL));
    }
}
