package io.crewscope.domain.notification;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned registry entry containing only a server template key and exact variable schema. */
public final class NotificationTemplate {

    private final NotificationTemplateRef ref;
    private final String serverTemplateKey;
    private final Map<String, NotificationVariableSpec> schema;
    private final NotificationTemplateStatus status;

    public NotificationTemplate(
            NotificationTemplateRef ref,
            String serverTemplateKey,
            Map<String, NotificationVariableSpec> schema,
            NotificationTemplateStatus status) {
        this.ref = Objects.requireNonNull(ref, "ref");
        if (serverTemplateKey == null
                || !serverTemplateKey.matches("[a-z][a-z0-9._-]{2,127}")) {
            throw new DomainValidationException(
                    "notificationTemplate.serverTemplateKey", "must be a stable registry key");
        }
        this.serverTemplateKey = serverTemplateKey;
        Map<String, NotificationVariableSpec> copy = new HashMap<>();
        Objects.requireNonNull(schema, "schema").forEach((name, spec) -> {
            if (!Objects.requireNonNull(name, "schema.name").equals(
                    Objects.requireNonNull(spec, "schema.spec").name())
                    || copy.put(name, spec) != null) {
                throw new DomainValidationException(
                        "notificationTemplate.schema", "keys must exactly match unique spec names");
            }
        });
        this.schema = Map.copyOf(copy);
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Accepts only an exact variable set; arbitrary body fields cannot enter the model. */
    public NotificationVariables validateVariables(Map<String, String> values) {
        if (status != NotificationTemplateStatus.PUBLISHED) {
            throw new DomainValidationException(
                    "notificationTemplate.status", "must be PUBLISHED");
        }
        Map<String, String> required = Map.copyOf(Objects.requireNonNull(values, "values"));
        if (!required.keySet().equals(schema.keySet())) {
            throw new DomainValidationException(
                    "notificationVariables", "keys must exactly equal the template schema");
        }
        schema.forEach((name, spec) -> spec.validate(required.get(name)));
        return new NotificationVariables(required);
    }

    public NotificationTemplate retire() {
        return status == NotificationTemplateStatus.RETIRED
                ? this
                : new NotificationTemplate(ref, serverTemplateKey, schema, NotificationTemplateStatus.RETIRED);
    }

    public NotificationTemplateRef ref() { return ref; }
    public String serverTemplateKey() { return serverTemplateKey; }
    public Map<String, NotificationVariableSpec> schema() { return schema; }
    public NotificationTemplateStatus status() { return status; }
}
