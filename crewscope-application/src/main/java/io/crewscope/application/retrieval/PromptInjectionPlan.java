package io.crewscope.application.retrieval;

import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Objects;

/**
 * The assembly outcome the renderer consumes (M10-I02b): the sealed (or converged)
 * manifest plus the actual content of every candidate that survived the budget —
 * or, when injection is switched off, the uniformly empty disabled shape: no
 * manifest, no references, nothing to render.
 */
public record PromptInjectionPlan(
        InjectionManifest manifest,
        List<RetrievalCandidate> knowledge,
        List<RetrievalCandidate> chunks,
        List<AgentMemoryEntry> memory,
        boolean injected) {

    public PromptInjectionPlan {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        memory = memory == null ? List.of() : List.copyOf(memory);
        if (injected) {
            Objects.requireNonNull(manifest, "manifest");
        } else if (manifest != null
                || !knowledge.isEmpty() || !chunks.isEmpty() || !memory.isEmpty()) {
            throw new DomainValidationException(
                    "promptInjectionPlan", "a disabled plan carries nothing at all");
        }
    }

    /** The switch-off shape: no manifest was sealed and no prompt block exists. */
    public static PromptInjectionPlan disabled() {
        return new PromptInjectionPlan(null, List.of(), List.of(), List.of(), false);
    }
}
