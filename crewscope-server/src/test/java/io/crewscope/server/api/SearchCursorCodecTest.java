package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.search.SearchCursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cursor encoding stays opaque, versioned and lossless at the HTTP boundary. */
class SearchCursorCodecTest {
    @Test
    void roundTripPreservesTimestampAndObjectIdentity() {
        SearchCursorCodec codec = new SearchCursorCodec();
        SearchCursor source = new SearchCursor(
                Instant.parse("2026-09-13T04:05:06.123456789Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"));

        assertEquals(source, codec.decode(codec.encode(source)));
    }

    @Test
    void rejectsMalformedAndUnsupportedCursors() {
        SearchCursorCodec codec = new SearchCursorCodec();
        assertThrows(ApiRequestException.class, () -> codec.decode("not a cursor"));
        assertThrows(ApiRequestException.class, () -> codec.decode("AQ"));
    }
}
