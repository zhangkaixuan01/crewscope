package io.crewscope.agentscope;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Optional W3C-compatible trace identifiers captured at the model-call boundary. */
public record AgentCallTraceContext(Optional<String> traceId, Optional<String> spanId) {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    public AgentCallTraceContext {
        traceId = validate(Objects.requireNonNull(traceId, "traceId"), TRACE_ID, "traceId");
        spanId = validate(Objects.requireNonNull(spanId, "spanId"), SPAN_ID, "spanId");
        if (traceId.isEmpty() != spanId.isEmpty()) {
            throw new IllegalArgumentException("traceId and spanId must both be present or absent");
        }
    }

    public static AgentCallTraceContext empty() {
        return new AgentCallTraceContext(Optional.empty(), Optional.empty());
    }

    private static Optional<String> validate(
            Optional<String> value, Pattern pattern, String field) {
        return value.map(candidate -> {
            if (!pattern.matcher(candidate).matches() || candidate.chars().allMatch(c -> c == '0')) {
                throw new IllegalArgumentException(field + " must be a non-zero lowercase hex id");
            }
            return candidate;
        });
    }
}
