package io.crewscope.server.api;

import io.crewscope.application.audit.AuditQuery;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Strict parsing for the bounded Audit Explorer filter and route vocabulary. */
final class AuditApiSupport {

    static final int DEFAULT_LIMIT = 50;

    private AuditApiSupport() {}

    static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException failure) {
            throw invalid("route");
        }
    }

    static AuditQueryFilter filter(
            String occurredFrom,
            String occurredBefore,
            List<String> categories,
            List<String> outcomes,
            List<String> initiatorIds,
            List<String> actorIds,
            List<String> agentPrincipalIds,
            String subjectType,
            String subjectId,
            String providerBindingId,
            String correlationId) {
        try {
            return new AuditQueryFilter(
                    timestamp(occurredFrom),
                    timestamp(occurredBefore),
                    enums(categories, AuditEventCategory.class),
                    enums(outcomes, AuditOutcome.class),
                    identifiers(initiatorIds),
                    identifiers(actorIds),
                    identifiers(agentPrincipalIds),
                    subject(subjectType, subjectId),
                    optional(providerBindingId).map(ProviderBindingId::from),
                    optional(correlationId)
                            .map(value -> AggregateId.parseCanonical(value, "correlationId")));
        } catch (DateTimeException | IllegalArgumentException failure) {
            throw invalid("filters");
        }
    }

    static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > AuditQuery.MAX_LIMIT) {
            throw invalid("limit");
        }
        return requested;
    }

    private static Optional<UtcTimestamp> timestamp(String value) {
        return optional(value).map(candidate -> UtcTimestamp.from(Instant.parse(candidate)));
    }

    private static Optional<AggregateReference> subject(String type, String id) {
        Optional<String> subjectType = optional(type);
        Optional<String> subjectId = optional(id);
        if (subjectType.isPresent() != subjectId.isPresent()) {
            throw new IllegalArgumentException("subjectType and subjectId must be supplied together");
        }
        return subjectType.map(value -> new AggregateReference(
                value.toUpperCase(Locale.ROOT),
                AggregateId.parseCanonical(subjectId.orElseThrow(), "subjectId")));
    }

    private static Set<PrincipalId> identifiers(List<String> raw) {
        LinkedHashSet<PrincipalId> values = new LinkedHashSet<>();
        split(raw).map(PrincipalId::from).forEach(values::add);
        return Set.copyOf(values);
    }

    private static <E extends Enum<E>> Set<E> enums(List<String> raw, Class<E> type) {
        LinkedHashSet<E> values = new LinkedHashSet<>();
        split(raw)
                .map(value -> Enum.valueOf(type, value.toUpperCase(Locale.ROOT)))
                .forEach(values::add);
        return Set.copyOf(values);
    }

    private static java.util.stream.Stream<String> split(List<String> raw) {
        if (raw == null) {
            return java.util.stream.Stream.empty();
        }
        return raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isBlank());
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value).map(String::strip).filter(candidate -> !candidate.isEmpty());
    }

    static ApiRequestException unavailable() {
        return new ApiRequestException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "audit_explorer_unavailable",
                "Audit Explorer is unavailable on this server",
                Map.of());
    }

    private static ApiRequestException invalid(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains invalid Audit parameters",
                Map.of("field", field));
    }

    record Route(OrganizationId organizationId, TeamId teamId) {}
}
