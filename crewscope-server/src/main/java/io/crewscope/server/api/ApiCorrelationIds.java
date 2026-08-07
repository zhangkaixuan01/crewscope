package io.crewscope.server.api;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.List;
import java.util.UUID;
import org.springframework.web.server.ServerWebExchange;

/** Resolves one safe request correlation identifier and caches its provenance. */
public final class ApiCorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = ApiCorrelationIds.class.getName() + ".correlationId";
    public static final String SOURCE_ATTRIBUTE =
            ApiCorrelationIds.class.getName() + ".correlationIdSource";

    private ApiCorrelationIds() {}

    public static UUID resolve(ServerWebExchange exchange) {
        Object existing = exchange.getAttribute(ATTRIBUTE);
        if (existing instanceof UUID correlationId) {
            return correlationId;
        }
        List<String> values = exchange.getRequest().getHeaders().get(HEADER);
        UUID accepted = values != null && values.size() == 1 ? parse(values.get(0)) : null;
        UUID resolved = accepted == null ? UUID.randomUUID() : accepted;
        exchange.getAttributes().put(ATTRIBUTE, resolved);
        exchange.getAttributes()
                .put(
                        SOURCE_ATTRIBUTE,
                        accepted == null ? Source.GENERATED : Source.ACCEPTED);
        return resolved;
    }

    /** Returns whether the final identifier was accepted from the request or generated. */
    public static Source source(ServerWebExchange exchange) {
        resolve(exchange);
        return exchange.getAttributeOrDefault(SOURCE_ATTRIBUTE, Source.GENERATED);
    }

    private static UUID parse(String value) {
        if (value != null) {
            try {
                UUID parsed = UUID.fromString(value);
                if (!AggregateId.NIL_UUID.equals(parsed) && parsed.toString().equals(value)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // An untrusted malformed header never enters error details or logs.
            }
        }
        return null;
    }

    /** Low-cardinality provenance used by the correlation metric. */
    public enum Source {
        ACCEPTED,
        GENERATED
    }
}
