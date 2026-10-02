package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;

/**
 * Token budget snapshot taken when the manifest was sealed. Layer sums never exceed the
 * total. Skill instructions are hard-retained and deliberately have no column here —
 * they are not part of the trimmable budget (M10-S01 §3.3).
 */
public record PromptBudgetSnapshot(
        long totalTokens,
        long knowledgeTokens,
        long chunkTokens,
        long memoryTokens) {

    public PromptBudgetSnapshot {
        if (totalTokens < 0 || knowledgeTokens < 0 || chunkTokens < 0 || memoryTokens < 0) {
            throw new DomainValidationException(
                    "promptBudgetSnapshot", "token counts must not be negative");
        }
        long layered = Math.addExact(
                Math.addExact(knowledgeTokens, chunkTokens), memoryTokens);
        if (layered > totalTokens) {
            throw new DomainValidationException(
                    "promptBudgetSnapshot",
                    "layered tokens (%d) must not exceed total (%d)"
                            .formatted(layered, totalTokens));
        }
    }
}
