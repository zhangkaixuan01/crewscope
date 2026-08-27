package io.crewscope.application.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Closed object vocabulary, bidirectional edges and page-budget invariants for M6-A07. */
class CorrelationPageM6A07Test {

    @Test
    void keepsForwardAndReverseLinksOnTheSamePublicObjectIdentity() {
        UUID eventId = UUID.randomUUID();
        CorrelationObjectReference task = new CorrelationObjectReference(
                CorrelationObjectType.TASK, UUID.randomUUID());
        CorrelationEvent event = new CorrelationEvent(
                eventId, CorrelationEventSource.DOMAIN_EVENT, "TASK_DELEGATED_TO_AGENT",
                "USER", Optional.of(UUID.randomUUID()), Optional.of("SUCCEEDED"),
                UtcTimestamp.parse("2026-08-27T05:00:00Z"), List.of(task));
        CorrelationPage.CorrelationObjectLink reverse =
                new CorrelationPage.CorrelationObjectLink(task, List.of(eventId));

        CorrelationPage page = new CorrelationPage(
                UUID.randomUUID(), List.of(event), List.of(reverse), false, Optional.empty());

        assertEquals(task, page.events().get(0).references().get(0));
        assertEquals(eventId, page.objects().get(0).eventIds().get(0));
    }

    @Test
    void rejectsDuplicateReverseEdgesAndInconsistentContinuationState() {
        UUID eventId = UUID.randomUUID();
        CorrelationObjectReference task = new CorrelationObjectReference(
                CorrelationObjectType.TASK, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () ->
                new CorrelationPage.CorrelationObjectLink(task, List.of(eventId, eventId)));
        assertThrows(IllegalArgumentException.class, () -> new CorrelationPage(
                UUID.randomUUID(), List.of(), List.of(), true, Optional.empty()));
    }
}
