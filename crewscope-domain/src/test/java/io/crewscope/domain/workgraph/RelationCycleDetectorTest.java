package io.crewscope.domain.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Pure-algorithm probes: direct and transitive cycles, depth bound, split isolation. */
class RelationCycleDetectorTest {

    @Test
    void detectsTheOppositeEdgeOfAnExistingBlockingEdge() {
        // stored A BLOCKS B; inserting B BLOCKS A must be reported as a cycle
        Map<WorkItemId, List<WorkItemId>> dependents = adjacency("A->B");

        RelationCycleDetector.Probe probe =
                RelationCycleDetector.probeBlocksCycle(id("B"), id("A"), lookup(dependents));

        assertTrue(probe.reachable());
        assertFalse(probe.depthExhausted());
    }

    @Test
    void detectsTransitiveCyclesAcrossChains() {
        // stored X->Y, Y->Z; inserting Z->X closes X->Y->Z->X
        Map<WorkItemId, List<WorkItemId>> dependents = adjacency("X->Y", "Y->Z");

        assertTrue(
                RelationCycleDetector.probeBlocksCycle(id("Z"), id("X"), lookup(dependents))
                        .reachable());
        // the same edge read the other way is fine: X is not reachable from Z
        assertFalse(
                RelationCycleDetector.probeBlocksCycle(id("X"), id("Z"), lookup(dependents))
                        .reachable());
    }

    @Test
    void reportsUndecidedWhenTheWalkHitsTheDepthBound() {
        // chain C33->C32->...->C0 (C33 blocks C32 ...); inserting C0 BLOCKS C33 asks whether
        // C33's blocking closure of length 33 reaches C0 — beyond MAX_DEPTH, so undecided
        Map<WorkItemId, List<WorkItemId>> chain = new HashMap<>();
        for (int index = 33; index > 0; index--) {
            chain.put(id("C" + index), List.of(id("C" + (index - 1))));
        }
        chain.put(id("C0"), List.of());

        RelationCycleDetector.Probe probe =
                RelationCycleDetector.probeBlocksCycle(id("C0"), id("C33"), lookup(chain));

        assertFalse(probe.reachable());
        assertTrue(probe.depthExhausted());
    }

    @Test
    void splitAncestryDetectsForestCyclesButNotBlockingOverlaps() {
        // stored child chain X->Y->Z (SPLIT_FROM); inserting Z SPLIT_FROM X closes the ring
        Map<WorkItemId, List<WorkItemId>> parents = adjacency("X->Y", "Y->Z");

        assertTrue(
                RelationCycleDetector.probeSplitAncestry(id("Z"), id("X"), lookup(parents))
                        .reachable());

        // A SPLIT_FROM B together with B BLOCKS A stays legal: each probe consults only the
        // adjacency view its caller builds for its own kind. With the right (here: empty)
        // blocking view the probe admits the edge; misfeeding the split adjacency would
        // close a ring that does not exist — kind filtering is the caller's contract
        Map<WorkItemId, List<WorkItemId>> splitAdjacency = adjacency("A->B");
        assertFalse(
                RelationCycleDetector.probeBlocksCycle(id("B"), id("A"), node -> List.of())
                        .reachable());
        assertTrue(
                RelationCycleDetector.probeBlocksCycle(id("B"), id("A"), lookup(splitAdjacency))
                        .reachable());
        // symmetrically, the ancestry probe over its own empty parent view ignores a pure
        // blocking graph, while the blocking adjacency read as parent edges would reject
        Map<WorkItemId, List<WorkItemId>> blocksAdjacency = adjacency("B->A");
        assertFalse(
                RelationCycleDetector.probeSplitAncestry(id("A"), id("B"), node -> List.of())
                        .reachable());
        assertTrue(
                RelationCycleDetector.probeSplitAncestry(id("A"), id("B"), lookup(blocksAdjacency))
                        .reachable());
    }

    @Test
    void reportsReachableWhenTheClosureCrossesItselfThroughADiamond() {
        // diamond closure C->D1, C->D2, D1->E, D2->E: E appears twice but UNION-style
        // visited-dedup keeps the walk finite and still finds the target
        Map<WorkItemId, List<WorkItemId>> dependents =
                adjacency("C->D1", "C->D2", "D1->E", "D2->E");

        RelationCycleDetector.Probe probe =
                RelationCycleDetector.probeBlocksCycle(id("E"), id("C"), lookup(dependents));

        assertTrue(probe.reachable());
        assertFalse(probe.depthExhausted());
    }

    @Test
    void rejectsNullAdjacencyProducts() {
        // the null sits on C (not the target), so the walk expands it and fails fast
        Map<WorkItemId, List<WorkItemId>> dependents = new HashMap<>();
        dependents.put(id("C"), java.util.Arrays.asList((WorkItemId) null));

        assertThrows(
                NullPointerException.class,
                () ->
                        RelationCycleDetector.probeBlocksCycle(
                                id("B"), id("C"), lookup(dependents)));
    }

    private static Function<WorkItemId, List<WorkItemId>> lookup(
            Map<WorkItemId, List<WorkItemId>> adjacency) {
        return node -> adjacency.getOrDefault(node, List.of());
    }

    /** Builds {@code dependentsOf} maps from "NAME->NAME" edge descriptions. */
    private static Map<WorkItemId, List<WorkItemId>> adjacency(String... edges) {
        Map<WorkItemId, List<WorkItemId>> adjacency = new HashMap<>();
        for (String edge : edges) {
            String[] parts = edge.split("->");
            adjacency
                    .computeIfAbsent(id(parts[0]), ignored -> new java.util.ArrayList<>())
                    .add(id(parts[1]));
        }
        return adjacency;
    }

    private static WorkItemId id(String name) {
        // deterministic per name within one JVM run; uniqueness is all that matters here
        return WorkItemId.from(
                java.util.UUID.nameUUIDFromBytes(name.getBytes()).toString());
    }
}
