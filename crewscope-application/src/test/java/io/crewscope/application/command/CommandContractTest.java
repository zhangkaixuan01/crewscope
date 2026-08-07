package io.crewscope.application.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Locks down command key, request fingerprint, receipt and replay invariants. */
class CommandContractTest {

    @Test
    void normalizesAndValidatesPublicIdempotencyKeys() {
        assertEquals("command:work-item/42", IdempotencyKey.from(" command:work-item/42 ").value());
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.from("contains space"));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.from("../unsafe"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdempotencyKey.from("x".repeat(IdempotencyKey.MAX_LENGTH + 1)));
    }

    @Test
    void createsDeterministicBoundarySafeRequestHashes() {
        CommandRequestHash first = CommandRequestHash.sha256("CREATE", "ab", "c");
        CommandRequestHash same = CommandRequestHash.sha256("CREATE", "ab", "c");
        CommandRequestHash differentBoundary = CommandRequestHash.sha256("CREATE", "a", "bc");

        assertEquals(first, same);
        assertNotEquals(first, differentBoundary);
        assertEquals(64, first.value().length());
    }

    @Test
    void separatesFirstExecutionsFromReceiptOnlyReplays() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 7, UUID.randomUUID());

        CommandExecution<String> completed = CommandExecution.completed("result", receipt);
        CommandExecution<String> replayed = CommandExecution.replayed(receipt);

        assertEquals(Optional.of("result"), completed.result());
        assertTrue(replayed.result().isEmpty());
        assertEquals(completed.receipt(), replayed.receipt());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandExecution<>(Optional.of("result"), receipt, true));
    }
}
