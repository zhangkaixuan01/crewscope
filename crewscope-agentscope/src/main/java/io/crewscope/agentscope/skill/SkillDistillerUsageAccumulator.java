package io.crewscope.agentscope.skill;

import io.agentscope.core.model.ChatUsage;
import io.crewscope.application.skill.SkillDistillationPort;
import io.crewscope.domain.model.ModelTokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-attempt token usage observation of one Skill Distiller call (M10-A03b). Each real
 * Provider attempt appends one fact; the counters mirror the sanitized runtime invariant
 * of {@code AgentRunEventRecorded.TokenUsage} — non-negative, cached a subset of input,
 * total computed as input plus output so a Provider's inconsistent total can never corrupt
 * the fact. Invalid counters degrade to {@link ModelTokenUsage#unreported()} rather than
 * failing a distillation whose model call already succeeded.
 */
public final class SkillDistillerUsageAccumulator {

    private final List<SkillDistillationPort.AttemptUsage> attempts = new ArrayList<>();
    private String observedModelName;

    /** Appends one attempt fact; attempts are numbered from one in observation order. */
    public synchronized void record(ChatUsage usage, String modelName) {
        attempts.add(new SkillDistillationPort.AttemptUsage(
                attempts.size() + 1, sanitize(usage)));
        if (modelName != null && !modelName.isBlank()) {
            observedModelName = modelName;
        }
    }

    /** Snapshot of the recorded attempts, never empty once the call reached a Provider. */
    public synchronized List<SkillDistillationPort.AttemptUsage> attempts() {
        return List.copyOf(attempts);
    }

    /** The model name observed on the runtime {@code Model} of the last recorded attempt. */
    public synchronized Optional<String> observedModelName() {
        return Optional.ofNullable(observedModelName);
    }

    private static ModelTokenUsage sanitize(ChatUsage usage) {
        if (usage == null
                || usage.getInputTokens() < 0
                || usage.getOutputTokens() < 0
                || usage.getCachedTokens() < 0
                || usage.getCachedTokens() > usage.getInputTokens()) {
            return ModelTokenUsage.unreported();
        }
        return new ModelTokenUsage(
                usage.getInputTokens(),
                usage.getOutputTokens(),
                usage.getCachedTokens(),
                Math.addExact(usage.getInputTokens(), usage.getOutputTokens()));
    }

    @Override
    public synchronized String toString() {
        return "SkillDistillerUsageAccumulator[attempts=" + attempts.size()
                + ", model=" + Objects.toString(observedModelName, "unknown") + "]";
    }
}
