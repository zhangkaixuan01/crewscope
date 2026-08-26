package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityPayloadSchema;
import io.crewscope.domain.activity.ActivitySubjectType;
import io.crewscope.domain.activity.ActivityVisibility;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact reviewed mapping from one DomainEvent schema to a public Activity shape. */
public record ActivityEventTypeDefinition(
        EventType eventType,
        SchemaVersion sourceSchemaVersion,
        ActivityCategory category,
        ActivityVisibility visibility,
        ActivitySubjectType subjectType,
        ActivityIdentitySource subjectSource,
        ActivityPayloadSchema payloadSchema,
        List<ActivityPayloadFieldMapping> payloadFields,
        List<ActivityReferenceMapping> references) {

    public ActivityEventTypeDefinition {
        eventType = Objects.requireNonNull(eventType, "eventType");
        sourceSchemaVersion = Objects.requireNonNull(sourceSchemaVersion, "sourceSchemaVersion");
        category = Objects.requireNonNull(category, "category");
        visibility = Objects.requireNonNull(visibility, "visibility");
        subjectType = Objects.requireNonNull(subjectType, "subjectType");
        subjectSource = Objects.requireNonNull(subjectSource, "subjectSource");
        payloadSchema = Objects.requireNonNull(payloadSchema, "payloadSchema");
        payloadFields = List.copyOf(Objects.requireNonNull(payloadFields, "payloadFields"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));

        Set<String> publicFields = new HashSet<>();
        Set<String> requiredFields = new HashSet<>();
        for (ActivityPayloadFieldMapping field : payloadFields) {
            if (!publicFields.add(field.publicField())) {
                throw new IllegalArgumentException("Activity public payload fields must be unique");
            }
            if (field.required()) {
                requiredFields.add(field.publicField());
            }
        }
        if (!publicFields.equals(payloadSchema.allowedFields())
                || !requiredFields.equals(payloadSchema.requiredFields())) {
            throw new IllegalArgumentException(
                    "Activity payload mappings must exactly match their public Schema");
        }
        if (new HashSet<>(references).size() != references.size()) {
            throw new IllegalArgumentException("Activity reference mappings must be unique");
        }
    }
}
