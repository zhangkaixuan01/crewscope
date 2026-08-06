package io.crewscope.domain.shared.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainErrorTest {

    private static final WorkItemId WORK_ITEM_ID =
            WorkItemId.from("01989ee2-f6b0-7cda-97c4-1b337043d33f");

    @Test
    void validationErrorExposesAStableCodeCategoryAndSafeDetails() {
        DomainError error =
                new DomainValidationException("workItem.title", "must not be blank").error();

        assertEquals(DomainErrorCode.INVALID_VALUE, error.code());
        assertEquals("invalid_value", error.code().value());
        assertEquals(DomainErrorCategory.VALIDATION, error.category());
        assertEquals("workItem.title", error.details().get("field"));
        assertFalse(error.details().containsKey("value"));
    }

    @Test
    void invalidTransitionCarriesAggregateAndStateContext() {
        DomainError error = new InvalidStateTransitionException(
                        "WorkItem",
                        WORK_ITEM_ID,
                        WorkItemStatus.BACKLOG,
                        WorkItemStatus.DONE)
                .error();

        assertEquals(DomainErrorCode.INVALID_STATE_TRANSITION, error.code());
        assertEquals(DomainErrorCategory.CONFLICT, error.category());
        assertEquals(WORK_ITEM_ID.toString(), error.details().get("aggregateId"));
        assertEquals("BACKLOG", error.details().get("currentState"));
        assertEquals("DONE", error.details().get("targetState"));
    }

    @Test
    void optimisticLockConflictCarriesExpectedAndCommittedVersions() {
        DomainError error =
                new OptimisticLockConflictException("WorkItem", WORK_ITEM_ID, 7, 8).error();

        assertEquals(DomainErrorCode.OPTIMISTIC_LOCK_CONFLICT, error.code());
        assertEquals("7", error.details().get("expectedVersion"));
        assertEquals("8", error.details().get("actualVersion"));
    }

    @Test
    void idempotencyConflictContainsHashesWithoutRequestBodies() {
        DomainError error = new IdempotencyConflictException(
                        "command-42", "sha256:existing", "sha256:requested")
                .error();

        assertEquals(DomainErrorCode.IDEMPOTENCY_CONFLICT, error.code());
        assertEquals("sha256:existing", error.details().get("existingRequestHash"));
        assertEquals("sha256:requested", error.details().get("requestedRequestHash"));
        assertFalse(error.details().containsKey("request"));
        assertFalse(error.details().containsKey("requestBody"));
    }

    @Test
    void notFoundErrorUsesTheSharedAggregateIdentifierContract() {
        DomainError error = new AggregateNotFoundException("WorkItem", WORK_ITEM_ID).error();

        assertEquals(DomainErrorCode.AGGREGATE_NOT_FOUND, error.code());
        assertEquals(DomainErrorCategory.NOT_FOUND, error.category());
        assertEquals(WORK_ITEM_ID.toString(), error.details().get("aggregateId"));
    }

    @Test
    void detailsAreDefensivelyCopiedAndImmutable() {
        Map<String, String> mutableDetails = new HashMap<>();
        mutableDetails.put("field", "original");
        DomainError error =
                new DomainError(DomainErrorCode.INVALID_VALUE, "Invalid value", mutableDetails);

        mutableDetails.put("field", "changed");

        assertEquals(Map.of("field", "original"), error.details());
        assertThrows(UnsupportedOperationException.class, () -> error.details().put("x", "y"));
    }

    @Test
    void rejectsNegativeOptimisticLockVersions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OptimisticLockConflictException("WorkItem", WORK_ITEM_ID, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OptimisticLockConflictException("WorkItem", WORK_ITEM_ID, 0, -1));
    }
}
