package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Strict route, identifier and closed-enum parsing for member Inbox APIs. */
final class InboxApiSupport {

    private InboxApiSupport() {}

    static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException failure) {
            throw invalid("route");
        }
    }

    static InboxItemId itemId(String value) {
        try {
            return InboxItemId.from(value);
        } catch (IllegalArgumentException failure) {
            throw invalid("inboxItemId");
        }
    }

    static InboxFilter filter(
            List<String> itemTypes,
            List<String> sourceStatuses,
            List<String> dispositionStatuses) {
        try {
            Set<InboxItemType> types = enums(itemTypes, InboxItemType.class);
            Set<InboxSourceStatus> sources = sourceStatuses == null
                    ? Set.of(InboxSourceStatus.OPEN)
                    : enums(sourceStatuses, InboxSourceStatus.class);
            Set<InboxDispositionStatus> dispositions =
                    enums(dispositionStatuses, InboxDispositionStatus.class);
            return new InboxFilter(types, sources, dispositions);
        } catch (IllegalArgumentException failure) {
            throw invalid("filters");
        }
    }

    static InboxDispositionStatus disposition(String value) {
        try {
            InboxDispositionStatus status = InboxDispositionStatus.valueOf(
                    value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
            if (status == InboxDispositionStatus.UNREAD) {
                throw new IllegalArgumentException("UNREAD is derived");
            }
            return status;
        } catch (IllegalArgumentException failure) {
            throw invalid("status");
        }
    }

    private static <E extends Enum<E>> Set<E> enums(List<String> raw, Class<E> type) {
        LinkedHashSet<E> values = new LinkedHashSet<>();
        if (raw == null) {
            return Set.of();
        }
        raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> Enum.valueOf(type, value.toUpperCase(Locale.ROOT)))
                .forEach(values::add);
        return Set.copyOf(values);
    }

    private static ApiRequestException invalid(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains invalid Inbox parameters",
                Map.of("field", field));
    }

    record Route(OrganizationId organizationId, TeamId teamId) {}
}
