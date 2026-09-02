package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationVariableSpec;
import io.crewscope.domain.notification.TrustedNotificationOrigin;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pure JSON codec for notification projection facts; no JDBC or authorization policy access. */
final class NotificationProjectionJsonCodec {

    private static final String FIXED_TEMPLATE_CAPABILITY = "collaboration.notification.send-fixed-template";
    private static final Set<String> SAFE_VARIABLES = Set.of(
            "itemType", "sourceType", "sourceId", "sourceRevision", "priority", "deadline",
            "workItemTitle", "inboxUrl", "reviewUrl", "confirmationUrl", "taskUrl", "sourceUrl");

    private final ObjectMapper mapper;

    NotificationProjectionJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    Set<TrustedNotificationOrigin> trustedOrigins(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isArray() || root.isEmpty()) throw new IllegalArgumentException();
            Set<TrustedNotificationOrigin> origins = new java.util.LinkedHashSet<>();
            for (JsonNode value : root) {
                if (value.isString()) {
                    URI uri = URI.create(value.stringValue());
                    origins.add(new TrustedNotificationOrigin(uri.getScheme(), uri.getHost(), uri.getPort()));
                } else if (value.isObject()) {
                    origins.add(new TrustedNotificationOrigin(value.path("scheme").stringValue(), value.path("host").stringValue(), value.path("port").isMissingNode() ? -1 : value.path("port").intValue()));
                } else throw new IllegalArgumentException();
            }
            return Set.copyOf(origins);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Trusted origins are invalid", exception);
        }
    }

    Set<InboxItemType> itemTypes(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isArray()) throw new IllegalArgumentException();
            EnumSet<InboxItemType> values = EnumSet.noneOf(InboxItemType.class);
            for (JsonNode value : root) {
                if (!value.isString()) throw new IllegalArgumentException();
                values.add(InboxItemType.valueOf(value.stringValue()));
            }
            return Set.copyOf(values);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Enabled Inbox item types are invalid", exception);
        }
    }

    Map<String, String> variableValues(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject() || root.size() > SAFE_VARIABLES.size()) throw new IllegalArgumentException();
            Map<String, String> values = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                if (!SAFE_VARIABLES.contains(entry.getKey()) || !entry.getValue().isString() || values.put(entry.getKey(), entry.getValue().stringValue()) != null) throw new IllegalArgumentException();
            });
            return Map.copyOf(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Notification variables are invalid", exception);
        }
    }

    String serialize(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (RuntimeException exception) { throw new IllegalStateException("Notification variables cannot be serialized", exception); }
    }

    String capabilityJson() { return serialize(List.of(FIXED_TEMPLATE_CAPABILITY)); }

    boolean isSafeVariable(String name) { return SAFE_VARIABLES.contains(name); }
}
