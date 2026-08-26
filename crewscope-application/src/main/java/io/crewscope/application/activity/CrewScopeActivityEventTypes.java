package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityPayloadSchema;
import io.crewscope.domain.activity.ActivityReferenceType;
import io.crewscope.domain.activity.ActivitySubjectType;
import io.crewscope.domain.activity.ActivityVisibility;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reviewed M0-M5 Team Activity catalog; raw payloads never cross this registry. */
public final class CrewScopeActivityEventTypes {

    private CrewScopeActivityEventTypes() {}

    public static ActivityEventTypeRegistry reviewedRegistry() {
        List<ActivityEventTypeDefinition> definitions = new ArrayList<>();
        registerTeamEvents(definitions);
        registerWorkItemEvents(definitions);
        registerTaskEvents(definitions);
        registerReviewEvents(definitions);
        registerActionEvents(definitions);
        registerProviderEvents(definitions);
        return new ActivityEventTypeRegistry(definitions);
    }

    private static void registerTeamEvents(List<ActivityEventTypeDefinition> target) {
        target.add(definition(
                "TEAM_CREATED", ActivityCategory.TEAM, ActivityVisibility.TEAM_MEMBERS,
                ActivitySubjectType.TEAM, ActivityIdentitySource.team(),
                fields(required("name")), references(teamReference())));
        target.add(definition(
                "TEAM_MEMBER_JOINED", ActivityCategory.TEAM, ActivityVisibility.TEAM_MEMBERS,
                ActivitySubjectType.TEAM, ActivityIdentitySource.team(),
                fields(required("joinMethod")), references(teamReference())));
        target.add(definition(
                "TEAM_INITIALIZATION_COMPLETED", ActivityCategory.TEAM,
                ActivityVisibility.TEAM_ADMINS, ActivitySubjectType.TEAM,
                ActivityIdentitySource.team(), List.of(), references(teamReference())));
    }

    private static void registerWorkItemEvents(List<ActivityEventTypeDefinition> target) {
        target.add(workItemAggregate(
                "WORK_ITEM_CREATED", required("itemKey"), required("title"), required("status")));
        target.add(workItemAggregate(
                "WORK_ITEM_STATUS_CHANGED", required("itemKey"),
                required("previousStatus"), required("status")));
        target.add(workItemPayload(
                "WORK_ITEM_COMMENT_ADDED", fields(required("source"))));
        target.add(workItemPayload(
                "WORK_ITEM_RESOURCE_LINKED",
                fields(required("resourceType"), optional("label"))));
        target.add(workItemPayload(
                "WORK_ITEM_OWNER_ASSIGNED", fields(required("role"))));
        target.add(workItemPayload(
                "WORK_ITEM_OWNER_REPLACED", fields(required("role"))));
        target.add(workItemPayload(
                "WORK_ITEM_EXECUTOR_ASSIGNED", fields(required("role"))));
        target.add(workItemPayload(
                "WORK_ITEM_ADVISORY_REVIEWER_ASSIGNED", fields(required("role"))));
        target.add(workItemPayload(
                "WORK_ITEM_GATE_REVIEWER_ASSIGNED", fields(required("eligibilityMode"))));
        target.add(workItemPayload(
                "WORK_ITEM_RESPONSIBILITY_RELEASED", fields(required("role"))));
    }

    private static void registerTaskEvents(List<ActivityEventTypeDefinition> target) {
        for (SchemaVersion sourceVersion : List.of(SchemaVersion.V1, SchemaVersion.V2)) {
            target.add(definition(
                    "TASK_DELEGATED_TO_AGENT", sourceVersion, ActivityCategory.TASK,
                    ActivityVisibility.WORK_ITEM_PARTICIPANTS, ActivitySubjectType.TASK,
                    ActivityIdentitySource.payload("taskId"),
                    fields(required("taskStatus"), required("executionStatus")),
                    references(
                            teamReference(),
                            ActivityReferenceMapping.required(
                                    ActivityReferenceType.WORK_ITEM,
                                    ActivityIdentitySource.payload("workItemId")))));
            for (String eventType : List.of(
                    "MEMBER_TASK_PAUSE_ACCEPTED",
                    "MEMBER_TASK_RESUME_ACCEPTED",
                    "MEMBER_TASK_CANCEL_ACCEPTED",
                    "MEMBER_TASK_RETRY_ACCEPTED")) {
                target.add(definition(
                        eventType, sourceVersion, ActivityCategory.TASK,
                        ActivityVisibility.TEAM_MEMBERS, ActivitySubjectType.TASK,
                        ActivityIdentitySource.payload("taskId"),
                        fields(required("operation"), required("taskStatus"),
                                required("executionStatus")),
                        references(teamReference())));
            }
        }
    }

    private static void registerReviewEvents(List<ActivityEventTypeDefinition> target) {
        target.add(review(
                "REVIEW_REQUEST_CREATED", "reviewRequestId",
                fields(required("attempt"), required("requestRevision"))));
        target.add(review(
                "REVIEW_REQUEST_STARTED", "reviewRequestId",
                fields(required("requestVersion"))));
        target.add(review(
                "REVIEW_REQUEST_COMPLETED", "reviewRequestId",
                fields(required("requestVersion"))));
        target.add(review(
                "REVIEW_REQUEST_INVALIDATED", "reviewRequestId",
                fields(required("reason"))));
        target.add(review(
                "REVIEW_FINDING_RECORDED", "reviewRequestId",
                fields(required("severity"), required("category"),
                        required("reviewerRelationship"))));
        target.add(review(
                "REVIEW_FINDING_DUPLICATE_OBSERVED", "reviewRequestId",
                fields(required("observationNumber"))));
        target.add(review(
                "REVIEW_DECISION_RECORDED", "reviewRequestId",
                fields(required("eligibilityMode"), required("decisionType"))));
        target.add(review(
                "REVIEW_MODIFICATION_ROUND_STARTED", "sourceReviewRequestId",
                fields(required("roundNumber"))));
    }

    private static void registerActionEvents(List<ActivityEventTypeDefinition> target) {
        target.add(action(
                "ACTION_BUNDLE_PLANNED", "actionBundleId",
                fields(required("validUntil"))));
        target.add(action(
                "ACTION_BUNDLE_CONFIRMED", "actionBundleId",
                fields(required("validUntil"))));
        target.add(action(
                "ACTION_CONFIRMATION_CANCELLED", "actionBundleId",
                fields(required("cancellationReason"), required("confirmationVersion"))));
        target.add(action(
                "ACTION_DISPATCH_TRANSITIONED", "plannedActionId",
                fields(required("status"), required("claimAttempts"),
                        required("reconciliationAttempts"))));
        target.add(action(
                "ACTION_RECEIPT_RECORDED", "plannedActionId",
                fields(required("result"), required("source"), required("evidenceCode"))));
        target.add(action(
                "EXTERNAL_RESULT_MERGED", "plannedActionId",
                fields(required("externalObjectType"), required("providerStatus"),
                        required("source"), required("mergeOutcome"))));
    }

    private static void registerProviderEvents(List<ActivityEventTypeDefinition> target) {
        target.add(provider(
                "GITHUB_CONNECTION_CREATED",
                fields(required("connectorKey"), required("ownerType"), required("status"))));
        target.add(provider(
                "GITHUB_CONNECTION_REVOKED",
                fields(required("connectorKey"), required("ownerType"), required("status"))));
        target.add(provider(
                "GITHUB_PROVIDER_BOUND",
                fields(required("providerType"), required("status"),
                        required("defaultUsage"), required("connectionBacked"))));
    }

    private static ActivityEventTypeDefinition workItemAggregate(
            String eventType, ActivityPayloadFieldMapping... fields) {
        return definition(
                eventType, ActivityCategory.WORK_ITEM,
                ActivityVisibility.WORK_ITEM_PARTICIPANTS,
                ActivitySubjectType.WORK_ITEM, ActivityIdentitySource.aggregate(),
                fields(fields),
                references(
                        teamReference(),
                        ActivityReferenceMapping.required(
                                ActivityReferenceType.WORK_ITEM,
                                ActivityIdentitySource.aggregate())));
    }

    private static ActivityEventTypeDefinition workItemPayload(
            String eventType, List<ActivityPayloadFieldMapping> fields) {
        return definition(
                eventType, ActivityCategory.WORK_ITEM,
                ActivityVisibility.WORK_ITEM_PARTICIPANTS,
                ActivitySubjectType.WORK_ITEM, ActivityIdentitySource.payload("workItemId"),
                fields,
                references(
                        teamReference(),
                        ActivityReferenceMapping.required(
                                ActivityReferenceType.WORK_ITEM,
                                ActivityIdentitySource.payload("workItemId"))));
    }

    private static ActivityEventTypeDefinition review(
            String eventType, String reviewIdPath, List<ActivityPayloadFieldMapping> fields) {
        return definition(
                eventType, ActivityCategory.REVIEW, ActivityVisibility.TEAM_MEMBERS,
                ActivitySubjectType.REVIEW, ActivityIdentitySource.payload(reviewIdPath),
                fields,
                references(
                        teamReference(),
                        ActivityReferenceMapping.optional(
                                ActivityReferenceType.TASK,
                                ActivityIdentitySource.payload("taskId"))));
    }

    private static ActivityEventTypeDefinition action(
            String eventType, String actionIdPath, List<ActivityPayloadFieldMapping> fields) {
        return definition(
                eventType, ActivityCategory.ACTION, ActivityVisibility.TEAM_MEMBERS,
                ActivitySubjectType.ACTION, ActivityIdentitySource.payload(actionIdPath),
                fields, references(teamReference()));
    }

    private static ActivityEventTypeDefinition provider(
            String eventType, List<ActivityPayloadFieldMapping> fields) {
        return definition(
                eventType, ActivityCategory.PROVIDER, ActivityVisibility.TEAM_ADMINS,
                ActivitySubjectType.PROVIDER_BINDING, ActivityIdentitySource.aggregate(),
                fields,
                references(
                        teamReference(),
                        ActivityReferenceMapping.required(
                                ActivityReferenceType.PROVIDER_BINDING,
                                ActivityIdentitySource.aggregate())));
    }

    private static ActivityEventTypeDefinition definition(
            String eventType,
            ActivityCategory category,
            ActivityVisibility visibility,
            ActivitySubjectType subjectType,
            ActivityIdentitySource subjectSource,
            List<ActivityPayloadFieldMapping> fields,
            List<ActivityReferenceMapping> references) {
        return definition(
                eventType, SchemaVersion.V1, category, visibility, subjectType,
                subjectSource, fields, references);
    }

    private static ActivityEventTypeDefinition definition(
            String eventType,
            SchemaVersion sourceVersion,
            ActivityCategory category,
            ActivityVisibility visibility,
            ActivitySubjectType subjectType,
            ActivityIdentitySource subjectSource,
            List<ActivityPayloadFieldMapping> fields,
            List<ActivityReferenceMapping> references) {
        Set<String> required = fields.stream()
                .filter(ActivityPayloadFieldMapping::required)
                .map(ActivityPayloadFieldMapping::publicField)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> optional = fields.stream()
                .filter(field -> !field.required())
                .map(ActivityPayloadFieldMapping::publicField)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String schemaName = "activity." + eventType.toLowerCase(Locale.ROOT).replace('_', '-');
        return new ActivityEventTypeDefinition(
                EventType.from(eventType), sourceVersion, category, visibility,
                subjectType, subjectSource,
                new ActivityPayloadSchema(schemaName, SchemaVersion.V1, required, optional),
                fields, references);
    }

    private static ActivityPayloadFieldMapping required(String field) {
        return new ActivityPayloadFieldMapping(field, field, true);
    }

    private static ActivityPayloadFieldMapping optional(String field) {
        return new ActivityPayloadFieldMapping(field, field, false);
    }

    private static List<ActivityPayloadFieldMapping> fields(
            ActivityPayloadFieldMapping... fields) {
        return List.copyOf(Arrays.asList(fields));
    }

    private static ActivityReferenceMapping teamReference() {
        return ActivityReferenceMapping.required(
                ActivityReferenceType.TEAM, ActivityIdentitySource.team());
    }

    private static List<ActivityReferenceMapping> references(
            ActivityReferenceMapping... references) {
        return List.copyOf(Arrays.asList(references));
    }
}
