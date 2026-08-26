package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Stable descending keyset position for Team Lark mapping administration. */
public record LarkMemberMappingCursor(
        UtcTimestamp updatedAt, LarkMemberMappingId mappingId) {

    public LarkMemberMappingCursor {
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        mappingId = Objects.requireNonNull(mappingId, "mappingId");
    }

    public static LarkMemberMappingCursor from(LarkMemberMapping mapping) {
        LarkMemberMapping value = Objects.requireNonNull(mapping, "mapping");
        return new LarkMemberMappingCursor(value.audit().updatedAt(), value.id());
    }
}
