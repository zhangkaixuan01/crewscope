package io.crewscope.server.api;

import io.crewscope.application.audit.AuditPage;
import java.util.List;
import java.util.Objects;

/** Public newest-first Audit page with one opaque signed continuation. */
public record AuditPageResponse(List<AuditEventResponse> items, String nextCursor) {

    static AuditPageResponse from(AuditPage page, AuditCursorCodec codec) {
        AuditPage value = Objects.requireNonNull(page, "page");
        return new AuditPageResponse(
                value.events().stream().map(AuditEventResponse::from).toList(),
                value.nextCursor().map(codec::encode).orElse(null));
    }
}
