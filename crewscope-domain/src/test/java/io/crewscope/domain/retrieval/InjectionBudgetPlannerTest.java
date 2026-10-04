package io.crewscope.domain.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two-phase budget trim (M10-S01 §3.4): per-layer tail trim under the layer budget,
 * then a total pass that surrenders candidates from the lowest-priority layer tail
 * first. Limits are deliberately allowed to overlap the total at this layer — the
 * wiring layer rejects such configurations at startup — so the total pass is
 * exercisable and keeps the sealed snapshot within the total constructively.
 */
final class InjectionBudgetPlannerTest {

    private static final ManifestSourceType KNOWLEDGE = ManifestSourceType.KNOWLEDGE_ENTRY;
    private static final ManifestSourceType CHUNK = ManifestSourceType.REPOSITORY_CHUNK;
    private static final ManifestSourceType MEMORY = ManifestSourceType.MEMORY_PREFERENCE;

    @Test
    void keepsEverythingWithinBudgetsWithoutTrims() {
        InjectionBudgetPlanner.InjectionBudgetPlan plan = InjectionBudgetPlanner.plan(
                new InjectionBudgetPlanner.InjectionBudgetLimits(100, 30, 40, 10),
                costs(List.of(10L, 20L), List.of(15L), List.of(5L, 5L)));

        assertEquals(keptCounts(2, 1, 2), plan.keptCounts());
        assertTrue(plan.trims().isEmpty());
        assertEquals(new PromptBudgetSnapshot(100, 30, 15, 10), plan.budget());
    }

    @Test
    void layerBudgetTrimsTheTailInOrder() {
        // Layer budget 25 keeps the first two 10s and tail-trims the last two.
        InjectionBudgetPlanner.InjectionBudgetPlan plan = InjectionBudgetPlanner.plan(
                new InjectionBudgetPlanner.InjectionBudgetLimits(100, 25, 40, 10),
                costs(List.of(10L, 10L, 10L, 5L), List.of(), List.of()));

        assertEquals(keptCounts(2, 0, 0), plan.keptCounts());
        assertEquals(List.of(new TrimRecord(KNOWLEDGE, 2, "layer budget exceeded")),
                plan.trims());
        assertEquals(new PromptBudgetSnapshot(100, 20, 0, 0), plan.budget());
    }

    @Test
    void singleCandidateLargerThanItsWholeLayerIsFullyTrimmed() {
        InjectionBudgetPlanner.InjectionBudgetPlan plan = InjectionBudgetPlanner.plan(
                new InjectionBudgetPlanner.InjectionBudgetLimits(100, 2, 40, 10),
                costs(List.of(5L), List.of(), List.of()));

        assertEquals(keptCounts(0, 0, 0), plan.keptCounts());
        assertEquals(List.of(new TrimRecord(KNOWLEDGE, 1, "layer budget exceeded")),
                plan.trims());
        assertEquals(new PromptBudgetSnapshot(100, 0, 0, 0), plan.budget());
    }

    @Test
    void totalOverrunTrimsFromTheLowestLayerTailFirst() {
        // Each layer fits its own cap (18 + 12 + 20 = 50 kept), but the total is 40:
        // the memory tail (15) goes, its head (5) stays — priority layers untouched.
        InjectionBudgetPlanner.InjectionBudgetPlan plan = InjectionBudgetPlanner.plan(
                new InjectionBudgetPlanner.InjectionBudgetLimits(40, 18, 12, 20),
                costs(List.of(9L, 9L), List.of(12L), List.of(5L, 15L)));

        assertEquals(keptCounts(2, 1, 1), plan.keptCounts());
        assertEquals(List.of(new TrimRecord(MEMORY, 1, "total budget exceeded")),
                plan.trims());
        assertEquals(new PromptBudgetSnapshot(40, 18, 12, 5), plan.budget());
    }

    @Test
    void totalOverrunWalksUpThePriorityLadderOnlyAsFarAsNeeded() {
        // Memory then chunks surrender everything; knowledge keeps its single entry.
        InjectionBudgetPlanner.InjectionBudgetPlan plan = InjectionBudgetPlanner.plan(
                new InjectionBudgetPlanner.InjectionBudgetLimits(20, 20, 20, 20),
                costs(List.of(20L), List.of(20L), List.of(20L)));

        assertEquals(keptCounts(1, 0, 0), plan.keptCounts());
        assertEquals(List.of(
                new TrimRecord(MEMORY, 1, "total budget exceeded"),
                new TrimRecord(CHUNK, 1, "total budget exceeded")), plan.trims());
        assertEquals(new PromptBudgetSnapshot(20, 20, 0, 0), plan.budget());
    }

    @Test
    void bothReasonsCanLandOnTheSameLayer() {
        // Memory first tail-trims under its 6-token cap, then the total pass takes
        // the survivor too: two records, two reasons, one layer.
        InjectionBudgetPlanner.InjectionBudgetPlan plan = InjectionBudgetPlanner.plan(
                new InjectionBudgetPlanner.InjectionBudgetLimits(4, 10, 10, 6),
                costs(List.of(), List.of(), List.of(5L, 5L, 5L)));

        assertEquals(keptCounts(0, 0, 0), plan.keptCounts());
        assertEquals(List.of(
                new TrimRecord(MEMORY, 2, "layer budget exceeded"),
                new TrimRecord(MEMORY, 1, "total budget exceeded")), plan.trims());
        assertEquals(new PromptBudgetSnapshot(4, 0, 0, 0), plan.budget());
    }

    @Test
    void limitsRejectNegativeBudgets() {
        assertThrows(DomainValidationException.class,
                () -> new InjectionBudgetPlanner.InjectionBudgetLimits(-1, 1, 1, 1));
        assertThrows(DomainValidationException.class,
                () -> new InjectionBudgetPlanner.InjectionBudgetLimits(10, 1, -1, 1));
    }

    @Test
    void skillInstructionCostsAreRejected() {
        EnumMap<ManifestSourceType, List<Long>> map =
                costs(List.of(1L), List.of(), List.of());
        map.put(ManifestSourceType.SKILL_INSTRUCTION, List.of(1L));

        assertThrows(DomainValidationException.class,
                () -> InjectionBudgetPlanner.plan(
                        new InjectionBudgetPlanner.InjectionBudgetLimits(10, 10, 10, 10), map));
    }

    // ------------------------------------------------------------------ fixtures

    private static EnumMap<ManifestSourceType, List<Long>> costs(
            List<Long> knowledge, List<Long> chunks, List<Long> memory) {
        EnumMap<ManifestSourceType, List<Long>> map = new EnumMap<>(ManifestSourceType.class);
        map.put(KNOWLEDGE, knowledge);
        map.put(CHUNK, chunks);
        map.put(MEMORY, memory);
        return map;
    }

    private static EnumMap<ManifestSourceType, Integer> keptCounts(
            int knowledge, int chunks, int memory) {
        EnumMap<ManifestSourceType, Integer> map = new EnumMap<>(ManifestSourceType.class);
        map.put(KNOWLEDGE, knowledge);
        map.put(CHUNK, chunks);
        map.put(MEMORY, memory);
        return map;
    }
}
