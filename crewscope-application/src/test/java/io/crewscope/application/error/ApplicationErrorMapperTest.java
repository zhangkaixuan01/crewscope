package io.crewscope.application.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApplicationErrorMapperTest {

    @Test
    void extractsADirectDomainFailure() {
        Optional<DomainError> error = ApplicationErrorMapper.from(
                new DomainValidationException("workItem.title", "must not be blank"));

        assertEquals(DomainErrorCode.INVALID_VALUE, error.orElseThrow().code());
    }

    @Test
    void extractsADomainFailureFromInfrastructureWrappers() {
        RuntimeException failure = new RuntimeException(
                "transaction failed",
                new IllegalStateException(
                        "persistence wrapper",
                        new DomainValidationException("workItem.key", "has invalid format")));

        Optional<DomainError> error = ApplicationErrorMapper.from(failure);

        assertEquals("workItem.key", error.orElseThrow().details().get("field"));
    }

    @Test
    void hidesUnknownFailures() {
        assertTrue(ApplicationErrorMapper.from(new RuntimeException("database password"))
                .isEmpty());
    }

    @Test
    void stopsWhenAThrowableCauseChainContainsACycle() {
        assertTrue(ApplicationErrorMapper.from(new CyclicFailure()).isEmpty());
    }

    /** Simulates a broken third-party wrapper without mutating Throwable's protected cause field. */
    private static final class CyclicFailure extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
