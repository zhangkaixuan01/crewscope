package io.crewscope.infrastructure.persistence.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Contract tests for the Review persistence JSON boundary. */
class ReviewPersistenceJsonCodecTest {

    private final ReviewPersistenceJsonCodec codec = new ReviewPersistenceJsonCodec(new ObjectMapper());

    @Test
    void serializesAndReadsOnlyStringArrays() {
        assertEquals("[\"a\",\"b\"]", codec.serialize(List.of("a", "b")));
        assertEquals(List.of("a", "b"), codec.strings("[\"a\",\"b\"]"));
        assertThrows(IllegalStateException.class, () -> codec.strings("[1]"));
    }

    @Test
    void validatesEvidenceCoordinateObjects() {
        assertEquals(List.of("id:1:hash"), codec.evidenceCoordinates(
                "[{\"id\":\"id\",\"sequence\":1,\"evidenceHash\":\"hash\"}]"));
        assertThrows(IllegalStateException.class, () -> codec.evidenceCoordinates("[\"unsafe\"]"));
    }
}
