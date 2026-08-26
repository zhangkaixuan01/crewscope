package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditRedactedSummary;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.audit.AuditSummarySchema;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact reviewed policy for one Audit DomainEvent payload coordinate. */
public final class AuditEventTypeDefinition {

    private final EventType eventType;
    private final SchemaVersion sourceSchemaVersion;
    private final AuditEventCategory category;
    private final AuditOutcome outcome;
    private final AuditRetentionLevel retentionLevel;
    private final Optional<String> outcomeSourcePath;
    private final Map<String, AuditOutcome> outcomeOverrides;
    private final Set<String> allowedSourceFields;
    private final List<AuditPayloadFieldMapping> summaryFields;
    private final AuditSummarySchema summarySchema;

    public AuditEventTypeDefinition(
            EventType eventType,
            SchemaVersion sourceSchemaVersion,
            AuditEventCategory category,
            AuditOutcome outcome,
            AuditRetentionLevel retentionLevel,
            Optional<String> outcomeSourcePath,
            Map<String, AuditOutcome> outcomeOverrides,
            Set<String> allowedSourceFields,
            List<AuditPayloadFieldMapping> summaryFields) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.sourceSchemaVersion = Objects.requireNonNull(
                sourceSchemaVersion, "sourceSchemaVersion");
        this.category = Objects.requireNonNull(category, "category");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.retentionLevel = Objects.requireNonNull(retentionLevel, "retentionLevel");
        this.outcomeSourcePath = Objects.requireNonNull(
                outcomeSourcePath, "outcomeSourcePath");
        this.outcomeOverrides = Map.copyOf(
                Objects.requireNonNull(outcomeOverrides, "outcomeOverrides"));
        this.allowedSourceFields = Set.copyOf(
                Objects.requireNonNull(allowedSourceFields, "allowedSourceFields"));
        this.summaryFields = List.copyOf(Objects.requireNonNull(summaryFields, "summaryFields"));
        if (this.allowedSourceFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "Audit definitions require reviewed source fields");
        }
        if (this.outcomeSourcePath.isEmpty() != this.outcomeOverrides.isEmpty()) {
            throw new IllegalArgumentException(
                    "Audit outcome overrides require one scalar source path");
        }
        this.outcomeSourcePath.ifPresent(path -> {
            String root = path.split("\\.", 2)[0];
            if (!this.allowedSourceFields.contains(root)) {
                throw new IllegalArgumentException(
                        "Audit outcome mapping must use an allowed source field");
            }
        });
        Set<String> outputNames = new HashSet<>();
        for (AuditPayloadFieldMapping field : this.summaryFields) {
            String root = field.sourcePath().split("\\.", 2)[0];
            if (!this.allowedSourceFields.contains(root)) {
                throw new IllegalArgumentException(
                        "Audit summary mappings must use an allowed source field");
            }
            if (!outputNames.add(field.summaryField())) {
                throw new IllegalArgumentException("Audit summary field names must be unique");
            }
        }
        Set<String> required = this.summaryFields.stream()
                .filter(AuditPayloadFieldMapping::required)
                .map(AuditPayloadFieldMapping::summaryField)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> optional = this.summaryFields.stream()
                .filter(field -> !field.required())
                .map(AuditPayloadFieldMapping::summaryField)
                .collect(Collectors.toUnmodifiableSet());
        this.summarySchema = new AuditSummarySchema(
                this.eventType, this.sourceSchemaVersion, this.category, required, optional);
    }

    public AuditRedactedSummary projectSummary(Map<String, String> values) {
        return summarySchema.project(values);
    }

    public EventType eventType() {
        return eventType;
    }

    public SchemaVersion sourceSchemaVersion() {
        return sourceSchemaVersion;
    }

    public AuditEventCategory category() {
        return category;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public AuditOutcome resolveOutcome(Optional<String> sourceValue) {
        Optional<String> value = Objects.requireNonNull(sourceValue, "sourceValue");
        if (outcomeSourcePath.isEmpty()) {
            return outcome;
        }
        return value.map(candidate -> outcomeOverrides.getOrDefault(candidate, outcome))
                .orElse(outcome);
    }

    public Optional<String> outcomeSourcePath() {
        return outcomeSourcePath;
    }

    public AuditRetentionLevel retentionLevel() {
        return retentionLevel;
    }

    public Set<String> allowedSourceFields() {
        return allowedSourceFields;
    }

    public List<AuditPayloadFieldMapping> summaryFields() {
        return summaryFields;
    }
}
