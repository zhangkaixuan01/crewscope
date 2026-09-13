package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.search.SearchableObjectType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Request parsing keeps the public search contract bounded and enum-safe. */
class SearchApiSupportTest {
    @Test
    void parsesRepeatedTypeFiltersCaseInsensitively() {
        assertEquals(
                java.util.Set.of(SearchableObjectType.WORK_ITEM, SearchableObjectType.AGENT),
                SearchApiSupport.types(List.of("work_item", "AGENT")));
    }

    @Test
    void rejectsBlankAndOverlongQueries() {
        assertThrows(ApiRequestException.class, () -> SearchApiSupport.text(" "));
        assertThrows(ApiRequestException.class, () -> SearchApiSupport.text("a".repeat(101)));
    }
}
