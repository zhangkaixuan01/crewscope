package io.crewscope.application.audit;

import io.crewscope.domain.action.event.ActionBundleConfirmed;
import io.crewscope.domain.action.event.ActionBundlePlanned;
import io.crewscope.domain.action.event.ActionConfirmationCancelled;
import io.crewscope.domain.action.event.ActionDispatchTransitioned;
import io.crewscope.domain.action.event.ActionReceiptRecorded;
import io.crewscope.domain.action.event.ExternalResultMerged;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.coding.event.ExecutionWorkspaceChanged;
import io.crewscope.domain.coding.event.FinalDiffArtifactPublished;
import io.crewscope.domain.coding.event.RepositoryBindingChanged;
import io.crewscope.domain.coding.event.TestEvidencePublished;
import io.crewscope.domain.coding.event.WorkspaceDiffChanged;
import io.crewscope.domain.conversation.event.AgentRuntimeConfigurationRefreshed;
import io.crewscope.domain.conversation.event.ConversationCreated;
import io.crewscope.domain.conversation.event.ConversationMessagePosted;
import io.crewscope.domain.conversation.event.ConversationParticipantChanged;
import io.crewscope.domain.conversation.event.TaskIntentConfirmed;
import io.crewscope.domain.conversation.event.TaskIntentProposed;
import io.crewscope.domain.conversation.event.TaskIntentRejected;
import io.crewscope.domain.conversation.event.TaskIntentRevised;
import io.crewscope.domain.identity.event.UserIdentityMapped;
import io.crewscope.domain.model.event.ModelConnectionCredentialChanged;
import io.crewscope.domain.projection.ProjectionLifecycleEvent;
import io.crewscope.domain.provider.event.ConnectionLifecycleChanged;
import io.crewscope.domain.provider.event.ProviderBindingChanged;
import io.crewscope.domain.responsibility.event.GateReviewerAssigned;
import io.crewscope.domain.responsibility.event.ResponsibilityAssigned;
import io.crewscope.domain.responsibility.event.ResponsibilityReleased;
import io.crewscope.domain.review.event.ReviewDecisionRecorded;
import io.crewscope.domain.review.event.ReviewFindingDuplicateObserved;
import io.crewscope.domain.review.event.ReviewFindingRecorded;
import io.crewscope.domain.review.event.ReviewModificationRoundStarted;
import io.crewscope.domain.review.event.ReviewRequestCompleted;
import io.crewscope.domain.review.event.ReviewRequestCreated;
import io.crewscope.domain.review.event.ReviewRequestInvalidated;
import io.crewscope.domain.review.event.ReviewRequestStarted;
import io.crewscope.domain.runtime.event.RuntimeMaintenanceCompleted;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.task.event.AgentRunEventRecorded;
import io.crewscope.domain.task.event.AgentRunResumed;
import io.crewscope.domain.task.event.MemberTaskCommandAccepted;
import io.crewscope.domain.task.event.TaskDelegatedToAgent;
import io.crewscope.domain.task.event.TaskExecutionRecoveryStarted;
import io.crewscope.domain.task.event.WorkerTaskCommandAccepted;
import io.crewscope.domain.team.event.TeamCreated;
import io.crewscope.domain.team.event.TeamInitializationCompleted;
import io.crewscope.domain.team.event.TeamMemberJoined;
import io.crewscope.domain.workitem.event.WorkItemCommentAdded;
import io.crewscope.domain.workitem.event.WorkItemCreated;
import io.crewscope.domain.workitem.event.WorkItemResourceLinked;
import io.crewscope.domain.workitem.event.WorkItemStatusChanged;
import io.crewscope.domain.workitem.event.WorkProjectCreated;
import io.crewscope.domain.workspace.event.AgentConfigurationChanged;
import io.crewscope.domain.workspace.event.AgentProfileChanged;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Reviewed M0-M6 Audit catalog; raw payloads never become query summaries. */
public final class CrewScopeAuditEventTypes {

    private CrewScopeAuditEventTypes() {}

    public static AuditEventTypeRegistry reviewedRegistry() {
        List<AuditEventTypeDefinition> definitions = new ArrayList<>();
        registerFoundationEvents(definitions);
        registerRuntimeEvents(definitions);
        registerCodingEvents(definitions);
        registerAgentAndModelEvents(definitions);
        registerReviewEvents(definitions);
        registerActionAndProviderEvents(definitions);
        registerM6SecurityEvents(definitions);
        return new AuditEventTypeRegistry(definitions);
    }

    private static void registerFoundationEvents(List<AuditEventTypeDefinition> target) {
        register(
                target,
                List.of("USER_IDENTITY_MAPPED"),
                List.of(SchemaVersion.V1),
                UserIdentityMapped.class,
                AuditEventCategory.IDENTITY,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("provider"));
        register(
                target,
                List.of("TEAM_CREATED"),
                List.of(SchemaVersion.V1),
                TeamCreated.class,
                AuditEventCategory.TEAM,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED);
        register(
                target,
                List.of("TEAM_MEMBER_JOINED"),
                List.of(SchemaVersion.V1),
                TeamMemberJoined.class,
                AuditEventCategory.TEAM,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("joinMethod"));
        register(
                target,
                List.of("TEAM_INITIALIZATION_COMPLETED"),
                List.of(SchemaVersion.V1),
                TeamInitializationCompleted.class,
                AuditEventCategory.TEAM,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED);
        register(
                target,
                List.of("WORK_PROJECT_CREATED"),
                List.of(SchemaVersion.V1),
                WorkProjectCreated.class,
                AuditEventCategory.WORK,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("projectKey"),
                required("status"));
        register(
                target,
                List.of("WORK_ITEM_CREATED"),
                List.of(SchemaVersion.V1),
                WorkItemCreated.class,
                AuditEventCategory.WORK,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("itemKey"),
                required("status"));
        register(
                target,
                List.of("WORK_ITEM_STATUS_CHANGED"),
                List.of(SchemaVersion.V1),
                WorkItemStatusChanged.class,
                AuditEventCategory.WORK,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("itemKey"),
                required("previousStatus"),
                required("status"));
        register(
                target,
                List.of("WORK_ITEM_COMMENT_ADDED"),
                List.of(SchemaVersion.V1),
                WorkItemCommentAdded.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("source"));
        register(
                target,
                List.of("WORK_ITEM_RESOURCE_LINKED"),
                List.of(SchemaVersion.V1),
                WorkItemResourceLinked.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("resourceType"));
        register(
                target,
                List.of(
                        "WORK_ITEM_OWNER_ASSIGNED",
                        "WORK_ITEM_OWNER_REPLACED",
                        "WORK_ITEM_EXECUTOR_ASSIGNED",
                        "WORK_ITEM_ADVISORY_REVIEWER_ASSIGNED"),
                List.of(SchemaVersion.V1),
                ResponsibilityAssigned.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("role"));
        register(
                target,
                List.of("WORK_ITEM_GATE_REVIEWER_ASSIGNED"),
                List.of(SchemaVersion.V1),
                GateReviewerAssigned.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("eligibilityMode"));
        register(
                target,
                List.of("WORK_ITEM_RESPONSIBILITY_RELEASED"),
                List.of(SchemaVersion.V1),
                ResponsibilityReleased.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("role"));
        register(
                target,
                List.of("CONVERSATION_CREATED"),
                List.of(SchemaVersion.V1),
                ConversationCreated.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("visibility"));
        register(
                target,
                List.of(
                        "CONVERSATION_PARTICIPANT_JOINED",
                        "CONVERSATION_PARTICIPANT_REACTIVATED",
                        "CONVERSATION_PARTICIPANT_LEFT"),
                List.of(SchemaVersion.V1),
                ConversationParticipantChanged.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("role"),
                required("status"));
        register(
                target,
                List.of("CONVERSATION_MESSAGE_POSTED"),
                List.of(SchemaVersion.V1),
                ConversationMessagePosted.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("sequence"),
                required("messageType"));
        register(
                target,
                List.of("AGENT_RUNTIME_CONFIGURATION_REFRESHED"),
                List.of(SchemaVersion.V1),
                AgentRuntimeConfigurationRefreshed.class,
                AuditEventCategory.AGENT,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("configurationRevision"),
                required("version"));
        register(
                target,
                List.of("TASK_INTENT_PROPOSED"),
                List.of(SchemaVersion.V1),
                TaskIntentProposed.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("proposalRevision"),
                required("status"));
        register(
                target,
                List.of("TASK_INTENT_REVISED"),
                List.of(SchemaVersion.V1),
                TaskIntentRevised.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("previousProposalRevision"),
                required("proposalRevision"),
                required("status"));
        register(
                target,
                List.of("TASK_INTENT_REJECTED"),
                List.of(SchemaVersion.V1),
                TaskIntentRejected.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("proposalRevision"));
        register(
                target,
                List.of("TASK_INTENT_CONFIRMED"),
                List.of(SchemaVersion.V1),
                TaskIntentConfirmed.class,
                AuditEventCategory.COLLABORATION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("workItemKey"),
                required("status"));
    }

    private static void registerRuntimeEvents(List<AuditEventTypeDefinition> target) {
        register(
                target,
                List.of("TASK_DELEGATED_TO_AGENT"),
                List.of(SchemaVersion.V1, SchemaVersion.V2),
                TaskDelegatedToAgent.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("taskStatus"),
                required("executionStatus"),
                optional("executionScope", "agentExecutionScope"),
                optional("configurationRevision", "agentConfigurationRevision"),
                optional("modelBindingSource", "agentModelBindingSource"));
        register(
                target,
                List.of(
                        "MEMBER_TASK_PAUSE_ACCEPTED",
                        "MEMBER_TASK_RESUME_ACCEPTED",
                        "MEMBER_TASK_CANCEL_ACCEPTED",
                        "MEMBER_TASK_RETRY_ACCEPTED"),
                List.of(SchemaVersion.V1, SchemaVersion.V2),
                MemberTaskCommandAccepted.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("operation"),
                required("taskStatus"),
                required("executionStatus"),
                required("attempt", "targetAttempt"),
                optional("successorAttempt"),
                optional("successorScope", "successorExecutionScope"));
        register(
                target,
                List.of(
                        "WORKER_TASK_PREPARE_ACCEPTED",
                        "WORKER_TASK_START_ACCEPTED",
                        "WORKER_TASK_HEARTBEAT_ACCEPTED",
                        "WORKER_TASK_PROGRESS_ACCEPTED",
                        "WORKER_TASK_COMPLETE_ACCEPTED",
                        "WORKER_TASK_FAIL_ACCEPTED"),
                List.of(SchemaVersion.V1),
                WorkerTaskCommandAccepted.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("operation"),
                required("attempt"),
                optional("progressPercent"),
                optional("failureClass"),
                optional("failureCode"));
        registerWithOutcome(
                target,
                "AGENT_RUN_EVENT_RECORDED",
                AgentRunEventRecorded.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                "eventKind",
                Map.of("RUN_ERROR", AuditOutcome.FAILED),
                AuditRetentionLevel.STANDARD,
                required("eventKind"),
                required("attempt"),
                required("segmentSequence"),
                required("eventSequence"),
                optional("status"),
                optional("succeeded"),
                optional("failureCategory", "failure.category"),
                optional("retryable", "failure.retryable"),
                optional("runtimeCode", "failure.runtimeCode"));
        register(
                target,
                List.of("AGENT_RUN_RESUMED"),
                List.of(SchemaVersion.V1),
                AgentRunResumed.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("segmentSequence", "resumedSegmentSequence"));
        register(
                target,
                List.of("TASK_EXECUTION_RECOVERY_STARTED"),
                List.of(SchemaVersion.V1),
                TaskExecutionRecoveryStarted.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("attempt"),
                required("expiredPhase"));
        register(
                target,
                List.of("CODING_RUNTIME_RECONCILE_COMPLETED", "CODING_RUNTIME_ARCHIVE_COMPLETED"),
                List.of(SchemaVersion.V1),
                RuntimeMaintenanceCompleted.class,
                AuditEventCategory.SYSTEM,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("operation"),
                required("environment"),
                required("health"),
                required("failedWorkspaces"),
                required("archiveFailures"),
                required("capacityLimited"));
    }

    private static void registerCodingEvents(List<AuditEventTypeDefinition> target) {
        register(
                target,
                List.of(
                        "REPOSITORY_BINDING_REGISTERED",
                        "REPOSITORY_BINDING_ACTIVATED",
                        "REPOSITORY_BINDING_DISABLED"),
                List.of(SchemaVersion.V1),
                RepositoryBindingChanged.class,
                AuditEventCategory.PROVIDER,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("kind"),
                required("status"));
        register(
                target,
                List.of("EXECUTION_WORKSPACE_CHANGED"),
                List.of(SchemaVersion.V1),
                ExecutionWorkspaceChanged.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("status"),
                required("attempt"),
                required("recoveryGeneration"),
                optional("recoveryTargetStatus"),
                optional("completionReason"),
                optional("failureCode"));
        register(
                target,
                List.of("WORKSPACE_DIFF_RESET", "WORKSPACE_DIFF_DELTA"),
                List.of(SchemaVersion.V1),
                WorkspaceDiffChanged.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                required("changeKind"),
                required("sequence"),
                required("diffGeneration"),
                required("changedFileCount", "upserts"),
                required("removedFileCount", "removals"));
        register(
                target,
                List.of("TEST_EVIDENCE_PUBLISHED"),
                List.of(SchemaVersion.V1),
                TestEvidencePublished.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("succeeded"),
                required("total"),
                required("passed"),
                required("failed"),
                required("errors"),
                required("skipped"));
        register(
                target,
                List.of("FINAL_DIFF_ARTIFACT_PUBLISHED"),
                List.of(SchemaVersion.V1),
                FinalDiffArtifactPublished.class,
                AuditEventCategory.EXECUTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("attempt"),
                required("diffGeneration"),
                required("fileCount"),
                required("additions"),
                required("deletions"));
    }

    private static void registerAgentAndModelEvents(List<AuditEventTypeDefinition> target) {
        register(
                target,
                List.of(
                        "AGENT_PROFILE_CREATED",
                        "AGENT_PROFILE_ACTIVATED",
                        "AGENT_PROFILE_DISABLED",
                        "AGENT_PROFILE_ARCHIVED"),
                List.of(SchemaVersion.V1),
                AgentProfileChanged.class,
                AuditEventCategory.AGENT,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("ownershipType"),
                required("runtimeRole"),
                required("status"),
                required("version"));
        register(
                target,
                List.of("AGENT_CONFIGURATION_APPENDED"),
                List.of(SchemaVersion.V1),
                AgentConfigurationChanged.class,
                AuditEventCategory.AGENT,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("revision"),
                required("templateKey"),
                required("templateVersion"));
        register(
                target,
                List.of(
                        "MODEL_CONNECTION_CREATED",
                        "MODEL_CONNECTION_CREDENTIAL_ROTATED",
                        "MODEL_CONNECTION_REVOKED",
                        "MODEL_CONNECTION_SUSPENDED",
                        "MODEL_CONNECTION_VERIFIED",
                        "MODEL_CONNECTION_HANDLE_ISSUED"),
                List.of(SchemaVersion.V1),
                ModelConnectionCredentialChanged.class,
                AuditEventCategory.MODEL,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("operation"),
                required("providerKey"),
                required("connectionStatus"),
                optional("failureCode"));
        register(
                target,
                List.of("MODEL_CONNECTION_VERIFICATION_FAILED"),
                List.of(SchemaVersion.V1),
                ModelConnectionCredentialChanged.class,
                AuditEventCategory.MODEL,
                AuditOutcome.FAILED,
                AuditRetentionLevel.EXTENDED,
                required("operation"),
                required("providerKey"),
                required("connectionStatus"),
                required("failureCode"));
    }

    private static void registerReviewEvents(List<AuditEventTypeDefinition> target) {
        registerOne(target, "REVIEW_REQUEST_CREATED", ReviewRequestCreated.class,
                required("attempt"), required("requestRevision"));
        registerOne(target, "REVIEW_REQUEST_STARTED", ReviewRequestStarted.class,
                required("requestVersion"));
        registerOne(target, "REVIEW_REQUEST_COMPLETED", ReviewRequestCompleted.class,
                required("requestVersion"));
        registerOne(target, "REVIEW_REQUEST_INVALIDATED", ReviewRequestInvalidated.class,
                required("reason"));
        registerOne(target, "REVIEW_FINDING_RECORDED", ReviewFindingRecorded.class,
                required("severity"), required("category"), required("reviewerRelationship"));
        registerOne(
                target,
                "REVIEW_FINDING_DUPLICATE_OBSERVED",
                ReviewFindingDuplicateObserved.class,
                required("observationNumber"));
        registerOne(target, "REVIEW_DECISION_RECORDED", ReviewDecisionRecorded.class,
                required("eligibilityMode"), required("decisionType"));
        registerOne(
                target,
                "REVIEW_MODIFICATION_ROUND_STARTED",
                ReviewModificationRoundStarted.class,
                required("roundNumber"));
    }

    private static void registerActionAndProviderEvents(
            List<AuditEventTypeDefinition> target) {
        registerOne(target, "ACTION_BUNDLE_PLANNED", ActionBundlePlanned.class,
                required("actionCount", "actionKinds"));
        registerOne(target, "ACTION_BUNDLE_CONFIRMED", ActionBundleConfirmed.class,
                required("actionCount", "actionDigests"));
        registerOne(
                target,
                "ACTION_CONFIRMATION_CANCELLED",
                ActionConfirmationCancelled.class,
                required("cancellationReason"),
                required("confirmationVersion"));
        registerOne(
                target,
                "ACTION_DISPATCH_TRANSITIONED",
                ActionDispatchTransitioned.class,
                required("status"),
                required("claimAttempts"),
                required("reconciliationAttempts"));
        registerWithOutcome(
                target,
                "ACTION_RECEIPT_RECORDED",
                ActionReceiptRecorded.class,
                AuditEventCategory.ACTION,
                AuditOutcome.SUCCEEDED,
                "result",
                Map.of("FAILED", AuditOutcome.FAILED, "MANUALLY_FAILED", AuditOutcome.FAILED),
                AuditRetentionLevel.EXTENDED,
                required("result"),
                required("source"),
                required("evidenceCode"));
        registerOne(
                target,
                "EXTERNAL_RESULT_MERGED",
                ExternalResultMerged.class,
                required("externalObjectType"),
                required("providerStatus"),
                required("source"),
                required("mergeOutcome"));
        register(
                target,
                List.of("GITHUB_CONNECTION_CREATED", "GITHUB_CONNECTION_REVOKED"),
                List.of(SchemaVersion.V1),
                ConnectionLifecycleChanged.class,
                AuditEventCategory.PROVIDER,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("connectorKey"),
                required("ownerType"),
                required("status"));
        register(
                target,
                List.of("GITHUB_PROVIDER_BOUND"),
                List.of(SchemaVersion.V1),
                ProviderBindingChanged.class,
                AuditEventCategory.PROVIDER,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                required("providerType"),
                required("status"),
                required("defaultUsage"),
                required("connectionBacked"));
    }

    private static void registerM6SecurityEvents(List<AuditEventTypeDefinition> target) {
        for (String type : List.of(
                "PROJECTION_REBUILD_STARTED",
                "PROJECTION_REBUILD_RETRIED",
                "PROJECTION_VALIDATION_PASSED",
                "PROJECTION_GENERATION_SWITCHED",
                "PROJECTION_REBUILD_CANCELLED")) {
            register(
                    target,
                    List.of(type),
                    List.of(SchemaVersion.V1),
                    ProjectionLifecycleEvent.class,
                    AuditEventCategory.PROJECTION,
                    AuditOutcome.SUCCEEDED,
                    AuditRetentionLevel.EXTENDED,
                    required("projectionName", "projectionName.value"),
                    required("definitionVersion", "definitionVersion.value"),
                    required("generation", "generation.value"),
                    required("lifecycleEvent", "eventType"),
                    required("generationStatus"),
                    required("rebuildStatus"),
                    optional("previousGeneration", "previousActiveGeneration.value"),
                    optional("failureCode", "failureCode.value"));
        }
        for (String type : List.of(
                "PROJECTION_VALIDATION_FAILED", "PROJECTION_REBUILD_FAILED")) {
            register(
                    target,
                    List.of(type),
                    List.of(SchemaVersion.V1),
                    ProjectionLifecycleEvent.class,
                    AuditEventCategory.PROJECTION,
                    AuditOutcome.FAILED,
                    AuditRetentionLevel.EXTENDED,
                    required("projectionName", "projectionName.value"),
                    required("generation", "generation.value"),
                    required("lifecycleEvent", "eventType"),
                    required("generationStatus"),
                    required("rebuildStatus"),
                    optional("failureCode", "failureCode.value"));
        }

        registerExplicit(
                target,
                "OUTBOX_DEAD_LETTER_REPLAY_REQUESTED",
                AuditEventCategory.SYSTEM,
                AuditOutcome.SUCCEEDED,
                Set.of(
                        "commandId", "outboxEventId", "domainEventId", "expectedVersion",
                        "recoveryAction", "status"),
                required("recoveryAction"), required("status"));
        registerExplicit(
                target,
                "PROJECTION_DEAD_LETTER_REPLAY_REQUESTED",
                AuditEventCategory.PROJECTION,
                AuditOutcome.SUCCEEDED,
                Set.of(
                        "commandId", "projectionName", "generation", "deadLetterId",
                        "domainEventId", "expectedGenerationVersion", "recoveryAction", "status"),
                required("projectionName"), required("generation"),
                required("recoveryAction"), required("status"));

        registerExplicit(
                target,
                "NOTIFICATION_DELIVERY_TRANSITIONED",
                AuditEventCategory.NOTIFICATION,
                AuditOutcome.SUCCEEDED,
                Set.of(
                        "notificationDeliveryId", "plannedActionId", "status", "attemptCount",
                        "failureCode", "providerBindingId", "connectionId",
                        "externalOperationHash"),
                required("status"), optional("attemptCount"), optional("failureCode"));
        registerExplicit(
                target,
                "NOTIFICATION_DELIVERY_FAILED",
                AuditEventCategory.NOTIFICATION,
                AuditOutcome.FAILED,
                Set.of(
                        "notificationDeliveryId", "plannedActionId", "status", "attemptCount",
                        "failureCode", "providerBindingId", "connectionId",
                        "externalOperationHash"),
                required("status"), required("failureCode"));
        registerExplicit(
                target,
                "NOTIFICATION_REDELIVERY_REQUESTED",
                AuditEventCategory.NOTIFICATION,
                AuditOutcome.SUCCEEDED,
                Set.of("originalDeliveryId", "replacementDeliveryId", "reasonCode"),
                required("reasonCode"));
        registerExplicit(
                target,
                "LARK_EXTERNAL_TENANT_VERIFIED",
                AuditEventCategory.PROVIDER,
                AuditOutcome.SUCCEEDED,
                Set.of("externalTenantId", "status", "providerBindingId", "connectionId"),
                required("status"));
        registerExplicit(
                target,
                "LARK_MEMBER_MAPPING_CONFIRMED",
                AuditEventCategory.SECURITY,
                AuditOutcome.SUCCEEDED,
                Set.of(
                        "memberMappingId", "teamMemberId", "status", "providerBindingId",
                        "connectionId", "externalOperationHash"),
                required("status"));
        registerExplicit(
                target,
                "LARK_MEMBER_MAPPING_REVOKED",
                AuditEventCategory.SECURITY,
                AuditOutcome.SUCCEEDED,
                Set.of(
                        "memberMappingId", "teamMemberId", "status", "reasonCode",
                        "providerBindingId", "connectionId"),
                required("status"), required("reasonCode"));
    }

    private static void registerOne(
            List<AuditEventTypeDefinition> target,
            String eventType,
            Class<? extends DomainEvent> payloadType,
            AuditPayloadFieldMapping... summaryFields) {
        register(
                target,
                List.of(eventType),
                List.of(SchemaVersion.V1),
                payloadType,
                eventType.startsWith("REVIEW_")
                        ? AuditEventCategory.REVIEW
                        : AuditEventCategory.ACTION,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.EXTENDED,
                summaryFields);
    }

    private static void register(
            List<AuditEventTypeDefinition> target,
            List<String> eventTypes,
            List<SchemaVersion> versions,
            Class<? extends DomainEvent> payloadType,
            AuditEventCategory category,
            AuditOutcome outcome,
            AuditRetentionLevel retention,
            AuditPayloadFieldMapping... summaryFields) {
        Set<String> allowedFields = sourceFields(payloadType);
        for (String eventType : eventTypes) {
            for (SchemaVersion version : versions) {
                target.add(new AuditEventTypeDefinition(
                        EventType.from(eventType),
                        version,
                        category,
                        outcome,
                        retention,
                        java.util.Optional.empty(),
                        Map.of(),
                        allowedFields,
                        List.copyOf(Arrays.asList(summaryFields))));
            }
        }
    }

    private static void registerWithOutcome(
            List<AuditEventTypeDefinition> target,
            String eventType,
            Class<? extends DomainEvent> payloadType,
            AuditEventCategory category,
            AuditOutcome outcome,
            String outcomeSourcePath,
            Map<String, AuditOutcome> overrides,
            AuditRetentionLevel retention,
            AuditPayloadFieldMapping... summaryFields) {
        target.add(new AuditEventTypeDefinition(
                EventType.from(eventType),
                SchemaVersion.V1,
                category,
                outcome,
                retention,
                java.util.Optional.of(outcomeSourcePath),
                overrides,
                sourceFields(payloadType),
                List.copyOf(Arrays.asList(summaryFields))));
    }

    private static void registerExplicit(
            List<AuditEventTypeDefinition> target,
            String eventType,
            AuditEventCategory category,
            AuditOutcome outcome,
            Set<String> allowedFields,
            AuditPayloadFieldMapping... summaryFields) {
        target.add(new AuditEventTypeDefinition(
                EventType.from(eventType),
                SchemaVersion.V1,
                category,
                outcome,
                AuditRetentionLevel.EXTENDED,
                java.util.Optional.empty(),
                Map.of(),
                allowedFields,
                List.copyOf(Arrays.asList(summaryFields))));
    }

    private static Set<String> sourceFields(Class<? extends DomainEvent> payloadType) {
        if (!payloadType.isRecord()) {
            throw new IllegalArgumentException("Audit payload type must be a Java record");
        }
        return Arrays.stream(payloadType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static AuditPayloadFieldMapping required(String field) {
        return required(field, field);
    }

    private static AuditPayloadFieldMapping required(String summaryField, String sourcePath) {
        return new AuditPayloadFieldMapping(summaryField, sourcePath, true);
    }

    private static AuditPayloadFieldMapping optional(String field) {
        return optional(field, field);
    }

    private static AuditPayloadFieldMapping optional(String summaryField, String sourcePath) {
        return new AuditPayloadFieldMapping(summaryField, sourcePath, false);
    }
}
