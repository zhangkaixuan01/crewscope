package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Contract tests for the extracted Operations request/value-object boundary. */
class OperationsRequestParserContractTest {

    @Test
    void normalizesTextAndVersionsWithoutExposingDomainExceptions() {
        assertEquals("confirmation", OperationsRequestParser.requireText(" confirmation ", "confirmation"));
        assertEquals(4, OperationsRequestParser.nextVersion(3));
        assertEquals(9, OperationsRequestParser.version(9L, "version"));
    }

    @Test
    void mapsMalformedInputToStableApiError() {
        ApiRequestException error = assertThrows(
                ApiRequestException.class,
                () -> OperationsRequestParser.version(-1L, "expectedVersion"));
        assertEquals("invalid_request", error.code());
        assertEquals("expectedVersion", error.details().get("field"));
        assertThrows(ApiRequestException.class, () -> OperationsRequestParser.projectionName(""));
        assertThrows(ApiRequestException.class, () -> OperationsRequestParser.requireText(" ", "confirmation"));
    }
}
