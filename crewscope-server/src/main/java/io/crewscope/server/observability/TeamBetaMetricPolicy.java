package io.crewscope.server.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Enforces the ADR-023 low-cardinality registry for every M6 custom metric. */
public final class TeamBetaMetricPolicy {

    public static final String PREFIX = "crewscope.m6.";
    public static final int MAXIMUM_METRIC_SERIES = 256;
    public static final int MAXIMUM_TOTAL_SERIES = 2_000;

    private static final Map<String, Integer> LABEL_CARDINALITY = Map.ofEntries(
            Map.entry("outcome", 6),
            Map.entry("status", 8),
            Map.entry("type", 12),
            Map.entry("providerKey", 4),
            Map.entry("projectionName", 12),
            Map.entry("workerRole", 4),
            Map.entry("operation", 8),
            Map.entry("errorCode", 24),
            Map.entry("streamType", 3),
            Map.entry("result", 4));

    private static final Set<String> FORBIDDEN_LABELS = Set.of(
            "organizationid",
            "teamid",
            "memberid",
            "conversationid",
            "workitemid",
            "taskid",
            "agentrunid",
            "actionid",
            "notificationid",
            "eventid",
            "correlationid",
            "traceid",
            "messageid",
            "uri",
            "repository",
            "branch",
            "exceptionmessage",
            "credential",
            "secret");

    private final Map<String, MetricDefinition> definitions;
    private final Map<String, Set<String>> labelValues;
    private final int totalSeriesUpperBound;

    public TeamBetaMetricPolicy() {
        this.labelValues = allowedValues();
        this.definitions = definitions();
        this.totalSeriesUpperBound = validateDefinitions(definitions.values());
    }

    /** Returns the frozen theoretical upper bound used by the Team Beta release gate. */
    public int totalSeriesUpperBound() {
        return totalSeriesUpperBound;
    }

    /** Validates one prospective definition before any Meter is registered. */
    public int validateDefinition(String name, Set<String> labels) {
        MetricDefinition definition = new MetricDefinition(name, labels);
        return validateDefinition(definition);
    }

    /** Denies undeclared M6 meters, label sets and enum values without affecting other meters. */
    public MeterFilter meterFilter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (!id.getName().startsWith(PREFIX)) {
                    return MeterFilterReply.NEUTRAL;
                }
                return accepts(id) ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY;
            }
        };
    }

    private boolean accepts(Meter.Id id) {
        MetricDefinition definition = definitions.get(id.getName());
        if (definition == null) {
            return false;
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (Tag tag : id.getTags()) {
            if (actual.put(tag.getKey(), tag.getValue()) != null) {
                return false;
            }
        }
        if (!actual.keySet().equals(definition.labels())) {
            return false;
        }
        return actual.entrySet().stream().allMatch(entry -> labelValues
                .getOrDefault(entry.getKey(), Set.of())
                .contains(entry.getValue()));
    }

    private static int validateDefinitions(Iterable<MetricDefinition> values) {
        int total = 0;
        for (MetricDefinition definition : values) {
            total = Math.addExact(total, validateDefinition(definition));
        }
        if (total > MAXIMUM_TOTAL_SERIES) {
            throw new IllegalArgumentException("M6 custom metric series budget exceeds 2000");
        }
        return total;
    }

    private static int validateDefinition(MetricDefinition definition) {
        if (!definition.name().startsWith(PREFIX)) {
            throw new IllegalArgumentException("M6 metric must use the crewscope.m6 prefix");
        }
        int product = 1;
        for (String label : definition.labels()) {
            String canonical = canonical(label);
            if (FORBIDDEN_LABELS.contains(canonical)) {
                throw new IllegalArgumentException("dynamic identity metric label is forbidden");
            }
            Integer cardinality = LABEL_CARDINALITY.get(label);
            if (cardinality == null) {
                throw new IllegalArgumentException("metric label is not registered: " + label);
            }
            product = Math.multiplyExact(product, cardinality);
        }
        if (product > MAXIMUM_METRIC_SERIES) {
            throw new IllegalArgumentException("single metric series budget exceeds 256");
        }
        return product;
    }

    private static Map<String, MetricDefinition> definitions() {
        Map<String, MetricDefinition> result = new LinkedHashMap<>();
        define(result, TeamBetaOperationalTelemetry.OUTBOX_DURATION, "outcome");
        define(result, TeamBetaOperationalTelemetry.PROJECTION_DURATION,
                "projectionName", "outcome");
        define(result, TeamBetaOperationalTelemetry.SSE_DURATION, "streamType", "outcome");
        define(result, TeamBetaOperationalTelemetry.INBOX_DURATION, "outcome");
        define(result, TeamBetaOperationalTelemetry.NOTIFICATION_DURATION,
                "providerKey", "operation", "outcome");
        define(result, TeamBetaOperationalTelemetry.PROVIDER_DURATION,
                "providerKey", "operation", "outcome");
        define(result, TeamBetaOperationalTelemetry.PROVIDER_ERRORS,
                "providerKey", "errorCode");
        define(result, TeamBetaOperationalTelemetry.TEAM_OBSERVER_DURATION, "outcome");
        define(result, TeamBetaOperationalTelemetry.OPERATIONS_HEALTH, "type", "status");
        define(result, TeamBetaOperationalTelemetry.TELEMETRY_DROPPED, "result");
        return Map.copyOf(result);
    }

    private static void define(
            Map<String, MetricDefinition> definitions, String name, String... labels) {
        MetricDefinition previous = definitions.put(
                name, new MetricDefinition(name, Set.of(labels)));
        if (previous != null) {
            throw new IllegalStateException("duplicate M6 metric definition");
        }
    }

    private static Map<String, Set<String>> allowedValues() {
        return Map.ofEntries(
                Map.entry("outcome", values(
                        "success", "retry", "failure", "cancelled", "rejected", "degraded")),
                Map.entry("status", values(
                        "healthy", "degraded", "attention_required", "unavailable")),
                Map.entry("type", values(
                        "outbox", "projection", "sse", "inbox", "notification", "provider",
                        "agent", "operations", "dead_letter", "cursor")),
                Map.entry("providerKey", values("none", "lark", "github", "model")),
                Map.entry("projectionName", values(
                        "team_activity", "member_inbox", "other")),
                Map.entry("workerRole", values("api", "worker", "scheduler", "provider")),
                Map.entry("operation", values(
                        "publish", "replay", "stream", "query", "dispatch", "reconcile",
                        "redeliver", "summarize")),
                Map.entry("errorCode", values(
                        "none", "timeout", "unavailable", "authentication", "permission",
                        "rate_limited", "invalid_response", "identity_mismatch", "fenced",
                        "conflict", "invalid_input", "cancelled", "unknown", "dropped",
                        "budget_exceeded", "lease_rejected", "handler_missing",
                        "transport_failure", "ack_unconfirmed", "retry_exhausted",
                        "authorization_drift", "credential_unavailable", "output_invalid",
                        "internal")),
                Map.entry("streamType", values("conversation", "task", "team")),
                Map.entry("result", values("trace", "metric", "log", "baggage")));
    }

    private static Set<String> values(String... values) {
        return Set.of(values);
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record MetricDefinition(String name, Set<String> labels) {
        private MetricDefinition {
            name = Objects.requireNonNull(name, "name");
            labels = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(labels, "labels")));
        }
    }
}
