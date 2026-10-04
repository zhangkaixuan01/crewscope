package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Two-phase prompt budget trim (M10-S01 §3.4). Phase one keeps the longest in-order
 * prefix of each layer that fits its own layer budget and tail-trims the rest. Phase
 * two lands the layered sum under the total by tail-trimming from the lowest-priority
 * layer first: memory, then repository chunks, then knowledge entries. Skill
 * instructions are hard-retained and never enter the budget.
 *
 * <p>Layer budgets are independent caps, not a partition of the total — the wiring
 * layer may reject configurations whose layer sum exceeds the total up front, but the
 * planner itself only guarantees the {@link PromptBudgetSnapshot} invariant: what is
 * actually kept never costs more than the total.</p>
 */
public final class InjectionBudgetPlanner {

    public static final String LAYER_BUDGET_EXCEEDED = "layer budget exceeded";
    public static final String TOTAL_BUDGET_EXCEEDED = "total budget exceeded";

    /** Trim order for the total pass: lowest-priority layer surrenders candidates first. */
    private static final List<ManifestSourceType> TRIMMABLE_LAYERS = List.of(
            ManifestSourceType.MEMORY_PREFERENCE,
            ManifestSourceType.REPOSITORY_CHUNK,
            ManifestSourceType.KNOWLEDGE_ENTRY);

    private InjectionBudgetPlanner() {
    }

    /**
     * @param layerCosts estimated token cost per candidate, in priority order within
     *        each layer; the three trimmable layers may be absent (treated as empty)
     *        but SKILL_INSTRUCTION must not appear
     */
    public static InjectionBudgetPlan plan(
            InjectionBudgetLimits limits, EnumMap<ManifestSourceType, List<Long>> layerCosts) {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(layerCosts, "layerCosts");
        if (layerCosts.containsKey(ManifestSourceType.SKILL_INSTRUCTION)) {
            throw new DomainValidationException(
                    "injectionBudgetPlanner.layerCosts",
                    "SKILL_INSTRUCTION is hard-retained and must not enter the budget");
        }

        EnumMap<ManifestSourceType, List<Long>> keptCosts =
                new EnumMap<>(ManifestSourceType.class);
        List<TrimRecord> trims = new ArrayList<>();
        for (ManifestSourceType layer : TRIMMABLE_LAYERS) {
            List<Long> costs = sanitized(layerCosts.getOrDefault(layer, List.of()));
            long budget = switch (layer) {
                case KNOWLEDGE_ENTRY -> limits.knowledgeTokens();
                case REPOSITORY_CHUNK -> limits.chunkTokens();
                case MEMORY_PREFERENCE -> limits.memoryTokens();
                default -> throw new IllegalStateException("unexpected layer " + layer);
            };
            int kept = 0;
            long sum = 0;
            while (kept < costs.size() && Math.addExact(sum, costs.get(kept)) <= budget) {
                sum = Math.addExact(sum, costs.get(kept));
                kept++;
            }
            if (kept < costs.size()) {
                trims.add(new TrimRecord(
                        layer, costs.size() - kept, LAYER_BUDGET_EXCEEDED));
            }
            keptCosts.put(layer, List.copyOf(costs.subList(0, kept)));
        }

        long layered = layeredSum(keptCosts);
        for (ManifestSourceType layer : TRIMMABLE_LAYERS) {
            if (layered <= limits.totalTokens()) {
                break;
            }
            List<Long> costs = new ArrayList<>(keptCosts.get(layer));
            int dropped = 0;
            while (!costs.isEmpty() && layered > limits.totalTokens()) {
                layered = Math.subtractExact(layered, costs.remove(costs.size() - 1));
                dropped++;
            }
            if (dropped > 0) {
                keptCosts.put(layer, List.copyOf(costs));
                trims.add(new TrimRecord(layer, dropped, TOTAL_BUDGET_EXCEEDED));
            }
        }

        EnumMap<ManifestSourceType, Integer> keptCounts = new EnumMap<>(ManifestSourceType.class);
        for (ManifestSourceType layer : TRIMMABLE_LAYERS) {
            keptCounts.put(layer, keptCosts.get(layer).size());
        }
        return new InjectionBudgetPlan(keptCounts, trims, new PromptBudgetSnapshot(
                limits.totalTokens(),
                sum(keptCosts.get(ManifestSourceType.KNOWLEDGE_ENTRY)),
                sum(keptCosts.get(ManifestSourceType.REPOSITORY_CHUNK)),
                sum(keptCosts.get(ManifestSourceType.MEMORY_PREFERENCE))));
    }

    private static List<Long> sanitized(List<Long> costs) {
        for (Long cost : costs) {
            Objects.requireNonNull(cost, "cost");
            if (cost < 0) {
                throw new DomainValidationException(
                        "injectionBudgetPlanner.layerCosts",
                        "token costs must not be negative");
            }
        }
        return costs;
    }

    private static long layeredSum(EnumMap<ManifestSourceType, List<Long>> keptCosts) {
        long total = 0;
        for (List<Long> costs : keptCosts.values()) {
            total = Math.addExact(total, sum(costs));
        }
        return total;
    }

    private static long sum(List<Long> costs) {
        long total = 0;
        for (long cost : costs) {
            total = Math.addExact(total, cost);
        }
        return total;
    }

    /**
     * Configured budgets. Non-negative only: the layer caps are validated against the
     * total by the wiring layer (fail-fast at startup), while the planner guarantees
     * the kept sum respects the total regardless.
     */
    public record InjectionBudgetLimits(
            long totalTokens,
            long knowledgeTokens,
            long chunkTokens,
            long memoryTokens) {

        public InjectionBudgetLimits {
            if (totalTokens < 0 || knowledgeTokens < 0 || chunkTokens < 0 || memoryTokens < 0) {
                throw new DomainValidationException(
                        "injectionBudgetLimits", "token budgets must not be negative");
            }
        }
    }

    /**
     * Trim outcome: how many candidates of each trimmable layer survive, the trim
     * evidence, and the sealed budget snapshot of what is actually injected.
     */
    public record InjectionBudgetPlan(
            EnumMap<ManifestSourceType, Integer> keptCounts,
            List<TrimRecord> trims,
            PromptBudgetSnapshot budget) {

        public InjectionBudgetPlan {
            Objects.requireNonNull(keptCounts, "keptCounts");
            keptCounts = new EnumMap<>(keptCounts);
            trims = trims == null ? List.of() : List.copyOf(trims);
            Objects.requireNonNull(budget, "budget");
        }
    }
}
