package io.crewscope.domain.shared.error;

import java.util.Map;

/**
 * Reports reuse of an idempotency key with a different canonical request.
 *
 * <p>Only hashes enter the error details; request bodies and credentials remain outside logs and
 * error responses.
 */
public final class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(
            String idempotencyKey, String existingRequestHash, String requestedRequestHash) {
        super(new DomainError(
                DomainErrorCode.IDEMPOTENCY_CONFLICT,
                "Idempotency key was already used for a different request",
                Map.of(
                        "idempotencyKey", requireText(idempotencyKey, "idempotencyKey"),
                        "existingRequestHash",
                                requireText(existingRequestHash, "existingRequestHash"),
                        "requestedRequestHash",
                                requireText(requestedRequestHash, "requestedRequestHash"))));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
