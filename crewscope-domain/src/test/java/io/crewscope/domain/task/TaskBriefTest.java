package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the immutable, bounded and canonical Task input captured at delegation time. */
class TaskBriefTest {

    @Test
    void normalizesAndDefensivelyCopiesApprovedInput() {
        List<String> criteria = new ArrayList<>(List.of("  API returns 202  "));

        TaskBrief brief = new TaskBrief("  Delegate this WorkItem  ", criteria);
        criteria.add("late mutation");

        assertEquals("Delegate this WorkItem", brief.objective());
        assertEquals(List.of("API returns 202"), brief.acceptanceCriteria());
        assertThrows(UnsupportedOperationException.class,
                () -> brief.acceptanceCriteria().add("mutation"));
    }

    @Test
    void hashesFieldBoundariesAndCriterionOrderCanonically() {
        TaskBrief first = new TaskBrief("ab", List.of("c", "d"));
        TaskBrief same = new TaskBrief("ab", List.of("c", "d"));
        TaskBrief reordered = new TaskBrief("ab", List.of("d", "c"));
        TaskBrief differentBoundary = new TaskBrief("a", List.of("bc", "d"));

        assertEquals(first.contentHash(), same.contentHash());
        assertNotEquals(first.contentHash(), reordered.contentHash());
        assertNotEquals(first.contentHash(), differentBoundary.contentHash());
    }

    @Test
    void rejectsBlankAndOversizedFacts() {
        assertThrows(DomainValidationException.class,
                () -> new TaskBrief(" ", List.of("accepted")));
        assertThrows(DomainValidationException.class,
                () -> new TaskBrief("objective", List.of(" ")));
        assertThrows(DomainValidationException.class,
                () -> new TaskBrief("x".repeat(TaskBrief.MAX_OBJECTIVE_LENGTH + 1), List.of()));
    }
}
