package io.crewscope.domain.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Longest upstream blocking chain: chains, forks, isolation, cycle refusal. */
class CriticalPathTest {

    @Test
    void returnsTheSingleChainRootFirst() {
        // A blocks B blocks C: the chain ending at C is [A, B, C]
        Map<WorkItemId, List<WorkItemId>> upstream = adjacency("B<-A", "C<-B");

        List<WorkItemId> chain =
                CriticalPath.longestUpstreamChain(id("C"), lookup(upstream));

        assertEquals(List.of(id("A"), id("B"), id("C")), chain);
    }

    @Test
    void picksTheLongestBranchAtAFork() {
        // D is blocked by B (with blocker A above it) and by C directly; A->B->D wins
        Map<WorkItemId, List<WorkItemId>> upstream = adjacency("B<-A", "D<-B", "D<-C");

        List<WorkItemId> chain =
                CriticalPath.longestUpstreamChain(id("D"), lookup(upstream));

        assertEquals(List.of(id("A"), id("B"), id("D")), chain);
    }

    @Test
    void returnsJustTheNodeWithoutUpstream() {
        Map<WorkItemId, List<WorkItemId>> upstream = new HashMap<>();

        assertEquals(List.of(id("X")), CriticalPath.longestUpstreamChain(id("X"), lookup(upstream)));
    }

    @Test
    void survivesDeepChainsWithoutRecursionAndRefusesCycles() {
        // 2000-node chain proves the iterative walk; a 2-cycle then fails fast
        Map<WorkItemId, List<WorkItemId>> chain = new HashMap<>();
        for (int index = 0; index < 2000; index++) {
            chain.put(id("N" + index), List.of(id("N" + (index + 1))));
        }
        chain.put(id("N2000"), List.of());

        assertEquals(2001, CriticalPath.longestUpstreamChain(id("N0"), lookup(chain)).size());

        Map<WorkItemId, List<WorkItemId>> cyclic = adjacency("P<-Q", "Q<-P");
        assertThrows(
                IllegalArgumentException.class,
                () -> CriticalPath.longestUpstreamChain(id("P"), lookup(cyclic)));
    }

    private static Function<WorkItemId, List<WorkItemId>> lookup(
            Map<WorkItemId, List<WorkItemId>> adjacency) {
        return node -> adjacency.getOrDefault(node, List.of());
    }

    /** Builds {@code upstreamOf} maps ("A blocks B" written as "B<-A") with stable names. */
    private static Map<WorkItemId, List<WorkItemId>> adjacency(String... edges) {
        Map<WorkItemId, List<WorkItemId>> adjacency = new HashMap<>();
        for (String edge : edges) {
            String[] parts = edge.split("<-");
            adjacency
                    .computeIfAbsent(id(parts[0]), ignored -> new java.util.ArrayList<>())
                    .add(id(parts[1]));
        }
        return adjacency;
    }

    private static WorkItemId id(String name) {
        return WorkItemId.from(
                java.util.UUID.nameUUIDFromBytes(name.getBytes()).toString());
    }
}
