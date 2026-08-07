package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Proves deterministic WorkItem keyset cursor transport and strict malformed-token rejection. */
class WorkItemCursorCodecTest {

    private final WorkItemCursorCodec codec = new WorkItemCursorCodec();

    @Test
    void roundTripsTheExactTimestampAndTieBreakerId() {
        WorkItemCursor cursor = new WorkItemCursor(
                UtcTimestamp.parse("2026-08-07T12:34:56.123456Z"),
                WorkItemId.from("01989ee2-f6b0-7cda-97c4-1b337043d402"));

        String token = codec.encode(cursor);

        assertEquals(cursor, codec.decode(token));
        assertEquals(token, codec.encode(codec.decode(token)));
    }

    @Test
    void rejectsMalformedWrongSizedAndUnknownVersionTokens() {
        assertThrows(ApiRequestException.class, () -> codec.decode("not*base64"));
        assertThrows(ApiRequestException.class, () -> codec.decode("AQ=="));
        assertThrows(ApiRequestException.class, () -> codec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[2])));

        WorkItemCursor cursor = new WorkItemCursor(
                UtcTimestamp.parse("2026-08-07T12:34:56Z"), WorkItemId.generate());
        byte[] unknownVersion = Base64.getUrlDecoder().decode(codec.encode(cursor));
        unknownVersion[0] = 2;
        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(unknownVersion)));
    }

    @Test
    void appliesTheSharedPageLimitContract() {
        assertEquals(50, ApiPagination.limit(null));
        assertEquals(1, ApiPagination.limit(1));
        assertEquals(100, ApiPagination.limit(100));
        assertThrows(ApiRequestException.class, () -> ApiPagination.limit(0));
        assertThrows(ApiRequestException.class, () -> ApiPagination.limit(101));
    }
}
