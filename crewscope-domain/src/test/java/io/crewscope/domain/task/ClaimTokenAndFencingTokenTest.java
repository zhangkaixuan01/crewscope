package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

class ClaimTokenAndFencingTokenTest {

    @Test
    void hashesClaimSecretAndRedactsEveryStringRepresentation() {
        ClaimToken token = ExecutionLeaseDomainFixture.CLAIM_TOKEN;

        ClaimTokenHash hash = token.hash();

        assertEquals(64, hash.value().length());
        assertEquals("[REDACTED]", token.toString());
        assertEquals("[REDACTED_HASH]", hash.toString());
        assertFalse(hash.value().contains(token.reveal()));
    }

    @Test
    void rejectsWeakClaimTokensAndMalformedHashes() {
        assertThrows(DomainValidationException.class, () -> new ClaimToken("too-short"));
        assertThrows(DomainValidationException.class, () -> new ClaimToken("!".repeat(43)));
        assertThrows(DomainValidationException.class, () -> new ClaimTokenHash("a".repeat(63)));
        assertThrows(DomainValidationException.class, () -> new ClaimTokenHash("A".repeat(64)));
    }

    @Test
    void advancesFencingEpochStrictlyAndRejectsInvalidBounds() {
        FencingToken first = FencingToken.initial();
        FencingToken second = first.next();

        assertTrue(second.compareTo(first) > 0);
        assertNotEquals(first, second);
        assertThrows(DomainValidationException.class, () -> new FencingToken(0));
        assertThrows(
                DomainValidationException.class,
                () -> new FencingToken(Long.MAX_VALUE).next());
    }
}
