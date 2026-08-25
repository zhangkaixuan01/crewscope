package io.crewscope.domain.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Server-owned registry that refuses unknown event types and Payload schema versions. */
public final class AuditSummarySchemaRegistry {

    private final Map<SchemaKey, AuditSummarySchema> schemas;

    public AuditSummarySchemaRegistry(Collection<AuditSummarySchema> schemas) {
        HashMap<SchemaKey, AuditSummarySchema> indexed = new HashMap<>();
        for (AuditSummarySchema schema : Objects.requireNonNull(schemas, "schemas")) {
            AuditSummarySchema required = Objects.requireNonNull(schema, "schema");
            SchemaKey key = new SchemaKey(required.eventType(), required.sourceSchemaVersion());
            if (indexed.put(key, required) != null) {
                throw new DomainValidationException(
                        "auditSummarySchema", "event type and schema version must be unique");
            }
        }
        this.schemas = Map.copyOf(indexed);
    }

    /** Projects only a registered event/schema pair; raw or future Payload shapes remain private. */
    public AuditRedactedSummary project(
            EventType eventType,
            SchemaVersion sourceSchemaVersion,
            Map<String, String> sourceFields) {
        AuditSummarySchema schema = schemas.get(new SchemaKey(
                Objects.requireNonNull(eventType, "eventType"),
                Objects.requireNonNull(sourceSchemaVersion, "sourceSchemaVersion")));
        if (schema == null) {
            throw new DomainValidationException(
                    "auditSummarySchema",
                    "event type and Payload schema version are not registered");
        }
        return schema.project(sourceFields);
    }

    private record SchemaKey(EventType eventType, SchemaVersion sourceSchemaVersion) {}
}
