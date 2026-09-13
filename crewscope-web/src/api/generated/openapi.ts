/* eslint-disable */
// GENERATED FILE. Runtime schemas are served by Springdoc at /v3/api-docs.
// openApiOperations retains every controller operation; paths follows OpenAPI's
// one-operation-per-path+method shape and therefore merges media-type overloads.
// Regenerate with: node scripts/generate-openapi-types.mjs
// Controller source SHA-256: 4c32a9cd7ec33db81f12b6cafce2e258b2b7cf6455dbbc2420bafca4ada44a5f

export interface OpenApiOperation {
  readonly operationId: string
  readonly tags: readonly string[]
}

export interface OpenApiDocument {
  readonly openapi: '3.1.0'
  readonly info: { readonly title: string; readonly version: string }
  readonly paths: Readonly<Record<string, Readonly<Record<string, OpenApiOperation>>>>
}

export const openApiDocument = {
  "openapi": "3.1.0",
  "info": {
    "title": "CrewScope API",
    "version": "0.1.0"
  },
  "paths": {
    "/api/internal/v1/worker/executions/{executionId}/complete": {
      "post": {
        "operationId": "WorkerTaskCommandController_complete",
        "tags": [
          "WorkerTaskCommandController"
        ]
      }
    },
    "/api/internal/v1/worker/executions/{executionId}/fail": {
      "post": {
        "operationId": "WorkerTaskCommandController_fail",
        "tags": [
          "WorkerTaskCommandController"
        ]
      }
    },
    "/api/internal/v1/worker/executions/{executionId}/heartbeat": {
      "post": {
        "operationId": "WorkerTaskCommandController_heartbeat",
        "tags": [
          "WorkerTaskCommandController"
        ]
      }
    },
    "/api/internal/v1/worker/executions/{executionId}/prepare": {
      "post": {
        "operationId": "WorkerTaskCommandController_prepare",
        "tags": [
          "WorkerTaskCommandController"
        ]
      }
    },
    "/api/internal/v1/worker/executions/{executionId}/progress": {
      "post": {
        "operationId": "WorkerTaskCommandController_progress",
        "tags": [
          "WorkerTaskCommandController"
        ]
      }
    },
    "/api/internal/v1/worker/executions/{executionId}/start": {
      "post": {
        "operationId": "WorkerTaskCommandController_start",
        "tags": [
          "WorkerTaskCommandController"
        ]
      }
    },
    "/api/v1/account": {
      "get": {
        "operationId": "CurrentAccountController_current",
        "tags": [
          "CurrentAccountController"
        ]
      },
      "patch": {
        "operationId": "CurrentAccountController_update",
        "tags": [
          "CurrentAccountController"
        ]
      }
    },
    "/api/v1/account/password": {
      "post": {
        "operationId": "CurrentAccountController_changePassword",
        "tags": [
          "CurrentAccountController"
        ]
      }
    },
    "/api/v1/account/sessions/revoke": {
      "post": {
        "operationId": "CurrentAccountController_revokeSessions",
        "tags": [
          "CurrentAccountController"
        ]
      }
    },
    "/api/v1/auth/login": {
      "post": {
        "operationId": "AuthenticationController_login",
        "tags": [
          "AuthenticationController"
        ]
      }
    },
    "/api/v1/auth/logout": {
      "post": {
        "operationId": "AuthenticationController_logout",
        "tags": [
          "AuthenticationController"
        ]
      }
    },
    "/api/v1/auth/register": {
      "post": {
        "operationId": "RegistrationController_register",
        "tags": [
          "RegistrationController"
        ]
      }
    },
    "/api/v1/auth/session": {
      "get": {
        "operationId": "AuthenticationController_session",
        "tags": [
          "AuthenticationController"
        ]
      }
    },
    "/api/v1/invitations/accept": {
      "post": {
        "operationId": "TeamInvitationController_accept",
        "tags": [
          "TeamInvitationController"
        ]
      }
    },
    "/api/v1/invitations/preview": {
      "post": {
        "operationId": "TeamInvitationController_preview",
        "tags": [
          "TeamInvitationController"
        ]
      }
    },
    "/api/v1/onboarding": {
      "get": {
        "operationId": "OnboardingController_status",
        "tags": [
          "OnboardingController"
        ]
      }
    },
    "/api/v1/onboarding/team": {
      "post": {
        "operationId": "OnboardingController_createFirstTeam",
        "tags": [
          "OnboardingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections": {
      "get": {
        "operationId": "GitHubConnectionController_list",
        "tags": [
          "GitHubConnectionController"
        ]
      },
      "post": {
        "operationId": "GitHubConnectionController_create",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}": {
      "get": {
        "operationId": "GitHubConnectionController_get",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/bindings": {
      "get": {
        "operationId": "GitHubConnectionController_listBindings",
        "tags": [
          "GitHubConnectionController"
        ]
      },
      "post": {
        "operationId": "GitHubConnectionController_bind",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/health": {
      "get": {
        "operationId": "GitHubConnectionController_health",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/repositories": {
      "get": {
        "operationId": "GitHubConnectionController_listCatalog",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/repositories/{externalRepositoryId}/preflight": {
      "post": {
        "operationId": "GitHubConnectionController_preflightRepository",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/repositories/synchronize": {
      "post": {
        "operationId": "GitHubConnectionController_synchronizeCatalog",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/revoke": {
      "post": {
        "operationId": "GitHubConnectionController_revoke",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/verify": {
      "post": {
        "operationId": "GitHubConnectionController_verify",
        "tags": [
          "GitHubConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-connections": {
      "get": {
        "operationId": "ModelConnectionController_list",
        "tags": [
          "ModelConnectionController"
        ]
      },
      "post": {
        "operationId": "ModelConnectionController_create",
        "tags": [
          "ModelConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-connections/{connectionId}": {
      "get": {
        "operationId": "ModelConnectionController_get",
        "tags": [
          "ModelConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/revoke": {
      "post": {
        "operationId": "ModelConnectionController_revoke",
        "tags": [
          "ModelConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/rotate": {
      "post": {
        "operationId": "ModelConnectionController_rotate",
        "tags": [
          "ModelConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/suspend": {
      "post": {
        "operationId": "ModelConnectionController_suspend",
        "tags": [
          "ModelConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/verify": {
      "post": {
        "operationId": "ModelConnectionController_verify",
        "tags": [
          "ModelConnectionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-providers": {
      "get": {
        "operationId": "ModelCatalogController_listProviders",
        "tags": [
          "ModelCatalogController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/model-providers/{providerKey}/catalog": {
      "get": {
        "operationId": "ModelCatalogController_listCatalog",
        "tags": [
          "ModelCatalogController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/diagnostics": {
      "get": {
        "operationId": "OperationsController_diagnostics",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/rebuilds/{rebuildJobId}/cancel": {
      "post": {
        "operationId": "OperationsController_cancelRebuild",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/rebuilds/{rebuildJobId}/fail": {
      "post": {
        "operationId": "OperationsController_failRebuild",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/switch": {
      "post": {
        "operationId": "OperationsController_switchGeneration",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/validate": {
      "post": {
        "operationId": "OperationsController_validateGeneration",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/rebuilds": {
      "post": {
        "operationId": "OperationsController_startRebuild",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/rebuilds/{rebuildJobId}/retry": {
      "post": {
        "operationId": "OperationsController_retryRebuild",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/operations/recoveries": {
      "post": {
        "operationId": "OperationsController_recover",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/runtime-health/operations/archive": {
      "post": {
        "operationId": "RuntimeMaintenanceController_archive",
        "tags": [
          "RuntimeMaintenanceController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/runtime-health/operations/reconcile": {
      "post": {
        "operationId": "RuntimeMaintenanceController_reconcile",
        "tags": [
          "RuntimeMaintenanceController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams": {
      "get": {
        "operationId": "TeamController_listTeams",
        "tags": [
          "TeamController"
        ]
      },
      "post": {
        "operationId": "TeamController_createTeam",
        "tags": [
          "TeamController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}": {
      "get": {
        "operationId": "TeamController_getTeam",
        "tags": [
          "TeamController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/activity": {
      "get": {
        "operationId": "TeamActivityController_history",
        "tags": [
          "TeamActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/activity/{activityEventId}": {
      "get": {
        "operationId": "TeamActivityController_detail",
        "tags": [
          "TeamActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/activity/events": {
      "get": {
        "operationId": "TeamActivityController_events",
        "tags": [
          "TeamActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/activity/snapshot": {
      "get": {
        "operationId": "TeamActivityController_snapshot",
        "tags": [
          "TeamActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles": {
      "get": {
        "operationId": "AgentManagementController_list",
        "tags": [
          "AgentManagementController"
        ]
      },
      "post": {
        "operationId": "AgentManagementController_create",
        "tags": [
          "AgentManagementController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}": {
      "get": {
        "operationId": "AgentManagementController_get",
        "tags": [
          "AgentManagementController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/activate": {
      "post": {
        "operationId": "AgentManagementController_activate",
        "tags": [
          "AgentManagementController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/archive": {
      "post": {
        "operationId": "AgentManagementController_archive",
        "tags": [
          "AgentManagementController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations": {
      "get": {
        "operationId": "AgentManagementController_configurations",
        "tags": [
          "AgentManagementController"
        ]
      },
      "post": {
        "operationId": "AgentConfigurationController_append",
        "tags": [
          "AgentConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations/{revision}": {
      "get": {
        "operationId": "AgentConfigurationController_revision",
        "tags": [
          "AgentConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations/current": {
      "get": {
        "operationId": "AgentConfigurationController_current",
        "tags": [
          "AgentConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/disable": {
      "post": {
        "operationId": "AgentManagementController_disable",
        "tags": [
          "AgentManagementController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/model-catalog": {
      "get": {
        "operationId": "AgentConfigurationController_catalog",
        "tags": [
          "AgentConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/model-preflight": {
      "post": {
        "operationId": "AgentConfigurationController_preflight",
        "tags": [
          "AgentConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-templates": {
      "get": {
        "operationId": "AgentManagementController_templates",
        "tags": [
          "AgentManagementController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/audit-events": {
      "get": {
        "operationId": "AuditController_history",
        "tags": [
          "AuditController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/audit-events/export": {
      "post": {
        "operationId": "AuditController_export",
        "tags": [
          "AuditController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/configuration-health": {
      "get": {
        "operationId": "TeamSetupReadinessController_configurationHealth",
        "tags": [
          "TeamSetupReadinessController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/configuration-search": {
      "get": {
        "operationId": "TeamSetupReadinessController_configurationSearch",
        "tags": [
          "TeamSetupReadinessController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations": {
      "get": {
        "operationId": "ConversationController_list",
        "tags": [
          "ConversationController"
        ]
      },
      "post": {
        "operationId": "ConversationController_create",
        "tags": [
          "ConversationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}": {
      "get": {
        "operationId": "ConversationController_get",
        "tags": [
          "ConversationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-configuration-refresh": {
      "post": {
        "operationId": "ConversationConfigurationController_refresh",
        "tags": [
          "ConversationConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-configuration": {
      "get": {
        "operationId": "ConversationConfigurationController_status",
        "tags": [
          "ConversationConfigurationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations": {
      "post": {
        "operationId": "PersonalAgentInvocationController_invoke",
        "tags": [
          "PersonalAgentInvocationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/cancel": {
      "post": {
        "operationId": "PersonalAgentInvocationController_cancel",
        "tags": [
          "PersonalAgentInvocationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/resume": {
      "post": {
        "operationId": "PersonalAgentInvocationController_resume",
        "tags": [
          "PersonalAgentInvocationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/delivery-cards": {
      "get": {
        "operationId": "TaskDeliverySummaryController_conversation",
        "tags": [
          "TaskDeliverySummaryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/events": {
      "get": {
        "operationId": "ConversationEventController_stream",
        "tags": [
          "ConversationEventController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/messages": {
      "get": {
        "operationId": "ConversationController_messages",
        "tags": [
          "ConversationController"
        ]
      },
      "post": {
        "operationId": "ConversationController_postMessage",
        "tags": [
          "ConversationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants": {
      "post": {
        "operationId": "ConversationController_addParticipant",
        "tags": [
          "ConversationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants/{participantId}": {
      "delete": {
        "operationId": "ConversationController_removeParticipant",
        "tags": [
          "ConversationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}": {
      "get": {
        "operationId": "TaskIntentController_get",
        "tags": [
          "TaskIntentController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmation-previews": {
      "post": {
        "operationId": "TaskIntentController_previewConfirmation",
        "tags": [
          "TaskIntentController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmations": {
      "post": {
        "operationId": "TaskIntentController_confirm",
        "tags": [
          "TaskIntentController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/rejections": {
      "post": {
        "operationId": "TaskIntentController_reject",
        "tags": [
          "TaskIntentController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/revisions": {
      "post": {
        "operationId": "TaskIntentController_revise",
        "tags": [
          "TaskIntentController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/tasks": {
      "get": {
        "operationId": "TaskAssociationController_byConversation",
        "tags": [
          "TaskAssociationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/work-items": {
      "get": {
        "operationId": "ConversationWorkItemLinkController_byConversation",
        "tags": [
          "ConversationWorkItemLinkController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/correlations/{correlationId}": {
      "get": {
        "operationId": "CorrelationController_find",
        "tags": [
          "CorrelationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox": {
      "get": {
        "operationId": "InboxController_list",
        "tags": [
          "InboxController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/{inboxItemId}": {
      "get": {
        "operationId": "InboxController_detail",
        "tags": [
          "InboxController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/{inboxItemId}/disposition": {
      "put": {
        "operationId": "InboxController_changeDisposition",
        "tags": [
          "InboxController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/{inboxItemId}/target": {
      "get": {
        "operationId": "InboxController_target",
        "tags": [
          "InboxController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/counts": {
      "get": {
        "operationId": "InboxController_counts",
        "tags": [
          "InboxController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/initialization": {
      "post": {
        "operationId": "TeamController_completeInitialization",
        "tags": [
          "TeamController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/invitations": {
      "get": {
        "operationId": "TeamInvitationController_list",
        "tags": [
          "TeamInvitationController"
        ]
      },
      "post": {
        "operationId": "TeamInvitationController_create",
        "tags": [
          "TeamInvitationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/invitations/{invitationId}/revoke": {
      "post": {
        "operationId": "TeamInvitationController_revoke",
        "tags": [
          "TeamInvitationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/bindings/{bindingId}/health": {
      "get": {
        "operationId": "LarkAdministrationController_health",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/bindings/{bindingId}/preflight": {
      "post": {
        "operationId": "LarkAdministrationController_preflight",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections": {
      "get": {
        "operationId": "LarkAdministrationController_listConnections",
        "tags": [
          "LarkAdministrationController"
        ]
      },
      "post": {
        "operationId": "LarkAdministrationController_createConnection",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections/{connectionId}": {
      "get": {
        "operationId": "LarkAdministrationController_getConnection",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections/{connectionId}/revoke": {
      "post": {
        "operationId": "LarkAdministrationController_revokeConnection",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections/{connectionId}/rotate": {
      "post": {
        "operationId": "LarkAdministrationController_rotateConnection",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-mappings": {
      "get": {
        "operationId": "LarkAdministrationController_listMappings",
        "tags": [
          "LarkAdministrationController"
        ]
      },
      "post": {
        "operationId": "LarkAdministrationController_confirmMapping",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-mappings/{mappingId}/revoke": {
      "post": {
        "operationId": "LarkAdministrationController_revokeMapping",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-verifications": {
      "post": {
        "operationId": "LarkAdministrationController_verifyMember",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-deliveries": {
      "get": {
        "operationId": "LarkAdministrationController_deliveries",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-deliveries/{deliveryId}": {
      "get": {
        "operationId": "LarkAdministrationController_delivery",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-deliveries/{deliveryId}/redeliver": {
      "post": {
        "operationId": "LarkAdministrationController_redeliver",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-preferences/{memberId}": {
      "get": {
        "operationId": "LarkAdministrationController_preference",
        "tags": [
          "LarkAdministrationController"
        ]
      },
      "put": {
        "operationId": "LarkAdministrationController_updatePreference",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-templates": {
      "get": {
        "operationId": "LarkAdministrationController_templates",
        "tags": [
          "LarkAdministrationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/members": {
      "get": {
        "operationId": "TeamController_listMembers",
        "tags": [
          "TeamController"
        ]
      },
      "post": {
        "operationId": "TeamController_addMember",
        "tags": [
          "TeamController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/operations/health": {
      "get": {
        "operationId": "OperationsController_health",
        "tags": [
          "OperationsController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/principals": {
      "get": {
        "operationId": "PrincipalDirectoryController_search",
        "tags": [
          "PrincipalDirectoryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/provider-bindings": {
      "get": {
        "operationId": "ProviderBindingController_getDefault",
        "tags": [
          "ProviderBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health": {
      "get": {
        "operationId": "RuntimeObservationController_summary",
        "tags": [
          "RuntimeObservationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health/operations": {
      "get": {
        "operationId": "RuntimeObservationController_operations",
        "tags": [
          "RuntimeObservationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/setup-readiness": {
      "get": {
        "operationId": "TeamSetupReadinessController_get",
        "tags": [
          "TeamSetupReadinessController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks": {
      "get": {
        "operationId": "TaskQueryController_list",
        "tags": [
          "TaskQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}": {
      "get": {
        "operationId": "TaskQueryController_get",
        "tags": [
          "TaskQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/associations": {
      "get": {
        "operationId": "TaskAssociationController_byTask",
        "tags": [
          "TaskAssociationController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts": {
      "get": {
        "operationId": "TaskQueryController_attempts",
        "tags": [
          "TaskQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles": {
      "get": {
        "operationId": "ActionDeliveryController_list",
        "tags": [
          "ActionDeliveryController"
        ]
      },
      "post": {
        "operationId": "ActionDeliveryController_plan",
        "tags": [
          "ActionDeliveryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles/{bundleId}": {
      "get": {
        "operationId": "ActionDeliveryController_get",
        "tags": [
          "ActionDeliveryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles/{bundleId}/confirmations": {
      "post": {
        "operationId": "ActionDeliveryController_confirm",
        "tags": [
          "ActionDeliveryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/confirmations/{confirmationId}/cancel": {
      "post": {
        "operationId": "ActionDeliveryController_cancel",
        "tags": [
          "ActionDeliveryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/dispatches/{dispatchId}/manual-resolution": {
      "post": {
        "operationId": "ActionDeliveryController_resolveManually",
        "tags": [
          "ActionDeliveryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/cancel": {
      "post": {
        "operationId": "TaskCommandController_cancel",
        "tags": [
          "TaskCommandController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding": {
      "get": {
        "operationId": "TaskCodingQueryController_attempt",
        "tags": [
          "TaskCodingQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/artifacts/patch": {
      "get": {
        "operationId": "CodingArtifactController_patch",
        "tags": [
          "CodingArtifactController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/commands": {
      "get": {
        "operationId": "TaskCodingQueryController_commands",
        "tags": [
          "TaskCodingQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/commands/{commandEvidenceId}/log": {
      "get": {
        "operationId": "CodingArtifactController_buildLog",
        "tags": [
          "CodingArtifactController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/test-evidence": {
      "get": {
        "operationId": "TaskCodingQueryController_testEvidence",
        "tags": [
          "TaskCodingQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/test-evidence/{testEvidenceId}/report": {
      "get": {
        "operationId": "CodingArtifactController_testReport",
        "tags": [
          "CodingArtifactController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/pause": {
      "post": {
        "operationId": "TaskCommandController_pause",
        "tags": [
          "TaskCommandController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/resume": {
      "post": {
        "operationId": "TaskCommandController_resume",
        "tags": [
          "TaskCommandController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/retry": {
      "post": {
        "operationId": "TaskCommandController_retry",
        "tags": [
          "TaskCommandController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews": {
      "get": {
        "operationId": "ReviewController_list",
        "tags": [
          "ReviewController"
        ]
      },
      "post": {
        "operationId": "ReviewController_create",
        "tags": [
          "ReviewController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}": {
      "get": {
        "operationId": "ReviewController_get",
        "tags": [
          "ReviewController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/decisions": {
      "post": {
        "operationId": "ReviewController_decision",
        "tags": [
          "ReviewController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/execute": {
      "post": {
        "operationId": "ReviewController_execute",
        "tags": [
          "ReviewController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/modifications": {
      "post": {
        "operationId": "ReviewController_modifications",
        "tags": [
          "ReviewController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/re-review": {
      "post": {
        "operationId": "ReviewController_reReview",
        "tags": [
          "ReviewController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/runtime-facts": {
      "get": {
        "operationId": "TaskQueryController_runtimeFacts",
        "tags": [
          "TaskQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/coding-attempts": {
      "get": {
        "operationId": "TaskCodingQueryController_attempts",
        "tags": [
          "TaskCodingQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/coding": {
      "get": {
        "operationId": "TaskCodingQueryController_current",
        "tags": [
          "TaskCodingQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/delivery-summary": {
      "get": {
        "operationId": "TaskDeliverySummaryController_task",
        "tags": [
          "TaskDeliverySummaryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events": {
      "get": {
        "operationId": "TaskEventController_stream",
        "tags": [
          "TaskEventController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions": {
      "post": {
        "operationId": "TeamObserverController_createSession",
        "tags": [
          "TeamObserverController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations": {
      "post": {
        "operationId": "TeamObserverController_invoke",
        "tags": [
          "TeamObserverController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/cancel": {
      "post": {
        "operationId": "TeamObserverController_cancel",
        "tags": [
          "TeamObserverController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/evidence/{evidenceIndex}": {
      "get": {
        "operationId": "TeamObserverController_evidence",
        "tags": [
          "TeamObserverController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/resume": {
      "post": {
        "operationId": "TeamObserverController_resume",
        "tags": [
          "TeamObserverController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/summary": {
      "get": {
        "operationId": "TeamObserverController_summary",
        "tags": [
          "TeamObserverController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk": {
      "get": {
        "operationId": "WorkDeskController_get",
        "tags": [
          "WorkDeskController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects": {
      "get": {
        "operationId": "WorkProjectController_list",
        "tags": [
          "WorkProjectController"
        ]
      },
      "post": {
        "operationId": "WorkProjectController_create",
        "tags": [
          "WorkProjectController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}": {
      "get": {
        "operationId": "WorkProjectController_get",
        "tags": [
          "WorkProjectController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports": {
      "post": {
        "operationId": "GitHubRepositoryImportController_create",
        "tags": [
          "GitHubRepositoryImportController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}": {
      "get": {
        "operationId": "GitHubRepositoryImportController_get",
        "tags": [
          "GitHubRepositoryImportController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}/cancel": {
      "post": {
        "operationId": "GitHubRepositoryImportController_cancel",
        "tags": [
          "GitHubRepositoryImportController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}/retry": {
      "post": {
        "operationId": "GitHubRepositoryImportController_retry",
        "tags": [
          "GitHubRepositoryImportController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings": {
      "get": {
        "operationId": "RepositoryBindingController_list",
        "tags": [
          "RepositoryBindingController"
        ]
      },
      "post": {
        "operationId": "RepositoryBindingController_create",
        "tags": [
          "RepositoryBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}": {
      "get": {
        "operationId": "RepositoryBindingController_get",
        "tags": [
          "RepositoryBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}/activate": {
      "post": {
        "operationId": "RepositoryBindingController_activate",
        "tags": [
          "RepositoryBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}/disable": {
      "post": {
        "operationId": "RepositoryBindingController_disable",
        "tags": [
          "RepositoryBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}/preflight": {
      "post": {
        "operationId": "RepositoryBindingController_preflightExisting",
        "tags": [
          "RepositoryBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/preflight": {
      "post": {
        "operationId": "RepositoryBindingController_preflightDraft",
        "tags": [
          "RepositoryBindingController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-catalog": {
      "get": {
        "operationId": "RepositoryCatalogController_list",
        "tags": [
          "RepositoryCatalogController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items": {
      "get": {
        "operationId": "WorkItemQueryController_list",
        "tags": [
          "WorkItemQueryController"
        ]
      },
      "post": {
        "operationId": "WorkItemController_create",
        "tags": [
          "WorkItemController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}": {
      "get": {
        "operationId": "WorkItemQueryController_get",
        "tags": [
          "WorkItemQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity": {
      "get": {
        "operationId": "WorkItemActivityController_history",
        "tags": [
          "WorkItemActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity/{activityEventId}": {
      "get": {
        "operationId": "WorkItemActivityController_detail",
        "tags": [
          "WorkItemActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity/snapshot": {
      "get": {
        "operationId": "WorkItemActivityController_snapshot",
        "tags": [
          "WorkItemActivityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/coding-target/build-profiles": {
      "get": {
        "operationId": "CodingTargetController_listBuildProfiles",
        "tags": [
          "CodingTargetController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/coding-target/preflight": {
      "post": {
        "operationId": "CodingTargetController_preflight",
        "tags": [
          "CodingTargetController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments": {
      "get": {
        "operationId": "WorkItemQueryController_comments",
        "tags": [
          "WorkItemQueryController"
        ]
      },
      "post": {
        "operationId": "WorkItemQueryController_addComment",
        "tags": [
          "WorkItemQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/conversations": {
      "get": {
        "operationId": "ConversationWorkItemLinkController_byWorkItem",
        "tags": [
          "ConversationWorkItemLinkController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links": {
      "get": {
        "operationId": "WorkItemQueryController_resourceLinks",
        "tags": [
          "WorkItemQueryController"
        ]
      },
      "post": {
        "operationId": "WorkItemQueryController_linkResource",
        "tags": [
          "WorkItemQueryController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities": {
      "get": {
        "operationId": "ResponsibilityController_list",
        "tags": [
          "ResponsibilityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/{assignmentId}/releases": {
      "post": {
        "operationId": "ResponsibilityController_release",
        "tags": [
          "ResponsibilityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/advisory-reviewers": {
      "post": {
        "operationId": "ResponsibilityController_assignAdvisoryReviewer",
        "tags": [
          "ResponsibilityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/executors": {
      "post": {
        "operationId": "ResponsibilityController_assignExecutor",
        "tags": [
          "ResponsibilityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/gate-reviewers": {
      "post": {
        "operationId": "ResponsibilityController_assignGateReviewer",
        "tags": [
          "ResponsibilityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/owner": {
      "post": {
        "operationId": "ResponsibilityController_replaceOwner",
        "tags": [
          "ResponsibilityController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks": {
      "get": {
        "operationId": "TaskAssociationController_byWorkItem",
        "tags": [
          "TaskAssociationController"
        ]
      },
      "post": {
        "operationId": "TaskController_create",
        "tags": [
          "TaskController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks/preflight": {
      "post": {
        "operationId": "TaskController_preflight",
        "tags": [
          "TaskController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/timeline": {
      "get": {
        "operationId": "WorkItemTimelineController_list",
        "tags": [
          "WorkItemTimelineController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions": {
      "post": {
        "operationId": "WorkItemController_transition",
        "tags": [
          "WorkItemController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions/availability": {
      "get": {
        "operationId": "WorkItemTransitionController_availability",
        "tags": [
          "WorkItemTransitionController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/keys/{projectKey}": {
      "get": {
        "operationId": "WorkProjectController_keyAvailability",
        "tags": [
          "WorkProjectController"
        ]
      }
    },
    "/api/v1/organizations/{organizationId}/teams/{teamId}/workspaces/default": {
      "get": {
        "operationId": "TeamController_getDefaultWorkspace",
        "tags": [
          "TeamController"
        ]
      }
    },
    "/api/v1/system/info": {
      "get": {
        "operationId": "SystemInfoController_info",
        "tags": [
          "SystemInfoController"
        ]
      }
    }
  }
} as const satisfies OpenApiDocument

export const openApiOperations = [
  {
    "method": "post",
    "path": "/api/internal/v1/worker/executions/{executionId}/complete",
    "operationId": "WorkerTaskCommandController_complete",
    "controller": "WorkerTaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/internal/v1/worker/executions/{executionId}/fail",
    "operationId": "WorkerTaskCommandController_fail",
    "controller": "WorkerTaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/internal/v1/worker/executions/{executionId}/heartbeat",
    "operationId": "WorkerTaskCommandController_heartbeat",
    "controller": "WorkerTaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/internal/v1/worker/executions/{executionId}/prepare",
    "operationId": "WorkerTaskCommandController_prepare",
    "controller": "WorkerTaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/internal/v1/worker/executions/{executionId}/progress",
    "operationId": "WorkerTaskCommandController_progress",
    "controller": "WorkerTaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/internal/v1/worker/executions/{executionId}/start",
    "operationId": "WorkerTaskCommandController_start",
    "controller": "WorkerTaskCommandController"
  },
  {
    "method": "get",
    "path": "/api/v1/account",
    "operationId": "CurrentAccountController_current",
    "controller": "CurrentAccountController"
  },
  {
    "method": "patch",
    "path": "/api/v1/account",
    "operationId": "CurrentAccountController_update",
    "controller": "CurrentAccountController"
  },
  {
    "method": "post",
    "path": "/api/v1/account/password",
    "operationId": "CurrentAccountController_changePassword",
    "controller": "CurrentAccountController"
  },
  {
    "method": "post",
    "path": "/api/v1/account/sessions/revoke",
    "operationId": "CurrentAccountController_revokeSessions",
    "controller": "CurrentAccountController"
  },
  {
    "method": "post",
    "path": "/api/v1/auth/login",
    "operationId": "AuthenticationController_login",
    "controller": "AuthenticationController"
  },
  {
    "method": "post",
    "path": "/api/v1/auth/logout",
    "operationId": "AuthenticationController_logout",
    "controller": "AuthenticationController"
  },
  {
    "method": "post",
    "path": "/api/v1/auth/register",
    "operationId": "RegistrationController_register",
    "controller": "RegistrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/auth/session",
    "operationId": "AuthenticationController_session",
    "controller": "AuthenticationController"
  },
  {
    "method": "post",
    "path": "/api/v1/invitations/accept",
    "operationId": "TeamInvitationController_accept",
    "controller": "TeamInvitationController"
  },
  {
    "method": "post",
    "path": "/api/v1/invitations/preview",
    "operationId": "TeamInvitationController_preview",
    "controller": "TeamInvitationController"
  },
  {
    "method": "get",
    "path": "/api/v1/onboarding",
    "operationId": "OnboardingController_status",
    "controller": "OnboardingController"
  },
  {
    "method": "post",
    "path": "/api/v1/onboarding/team",
    "operationId": "OnboardingController_createFirstTeam",
    "controller": "OnboardingController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/github-connections",
    "operationId": "GitHubConnectionController_list",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/github-connections",
    "operationId": "GitHubConnectionController_create",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}",
    "operationId": "GitHubConnectionController_get",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/bindings",
    "operationId": "GitHubConnectionController_listBindings",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/bindings",
    "operationId": "GitHubConnectionController_bind",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/health",
    "operationId": "GitHubConnectionController_health",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/repositories",
    "operationId": "GitHubConnectionController_listCatalog",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/repositories/{externalRepositoryId}/preflight",
    "operationId": "GitHubConnectionController_preflightRepository",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/repositories/synchronize",
    "operationId": "GitHubConnectionController_synchronizeCatalog",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/revoke",
    "operationId": "GitHubConnectionController_revoke",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/github-connections/{connectionId}/verify",
    "operationId": "GitHubConnectionController_verify",
    "controller": "GitHubConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/model-connections",
    "operationId": "ModelConnectionController_list",
    "controller": "ModelConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/model-connections",
    "operationId": "ModelConnectionController_create",
    "controller": "ModelConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/model-connections/{connectionId}",
    "operationId": "ModelConnectionController_get",
    "controller": "ModelConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/revoke",
    "operationId": "ModelConnectionController_revoke",
    "controller": "ModelConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/rotate",
    "operationId": "ModelConnectionController_rotate",
    "controller": "ModelConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/suspend",
    "operationId": "ModelConnectionController_suspend",
    "controller": "ModelConnectionController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/model-connections/{connectionId}/verify",
    "operationId": "ModelConnectionController_verify",
    "controller": "ModelConnectionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/model-providers",
    "operationId": "ModelCatalogController_listProviders",
    "controller": "ModelCatalogController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/model-providers/{providerKey}/catalog",
    "operationId": "ModelCatalogController_listCatalog",
    "controller": "ModelCatalogController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/operations/diagnostics",
    "operationId": "OperationsController_diagnostics",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/rebuilds/{rebuildJobId}/cancel",
    "operationId": "OperationsController_cancelRebuild",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/rebuilds/{rebuildJobId}/fail",
    "operationId": "OperationsController_failRebuild",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/switch",
    "operationId": "OperationsController_switchGeneration",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/validate",
    "operationId": "OperationsController_validateGeneration",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/rebuilds",
    "operationId": "OperationsController_startRebuild",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/projections/{projectionName}/rebuilds/{rebuildJobId}/retry",
    "operationId": "OperationsController_retryRebuild",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/operations/recoveries",
    "operationId": "OperationsController_recover",
    "controller": "OperationsController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/runtime-health/operations/archive",
    "operationId": "RuntimeMaintenanceController_archive",
    "controller": "RuntimeMaintenanceController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/runtime-health/operations/reconcile",
    "operationId": "RuntimeMaintenanceController_reconcile",
    "controller": "RuntimeMaintenanceController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams",
    "operationId": "TeamController_listTeams",
    "controller": "TeamController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams",
    "operationId": "TeamController_createTeam",
    "controller": "TeamController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}",
    "operationId": "TeamController_getTeam",
    "controller": "TeamController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/activity",
    "operationId": "TeamActivityController_history",
    "controller": "TeamActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/activity/{activityEventId}",
    "operationId": "TeamActivityController_detail",
    "controller": "TeamActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/activity/events",
    "operationId": "TeamActivityController_events",
    "controller": "TeamActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/activity/snapshot",
    "operationId": "TeamActivityController_snapshot",
    "controller": "TeamActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles",
    "operationId": "AgentManagementController_list",
    "controller": "AgentManagementController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles",
    "operationId": "AgentManagementController_create",
    "controller": "AgentManagementController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}",
    "operationId": "AgentManagementController_get",
    "controller": "AgentManagementController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/activate",
    "operationId": "AgentManagementController_activate",
    "controller": "AgentManagementController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/archive",
    "operationId": "AgentManagementController_archive",
    "controller": "AgentManagementController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations",
    "operationId": "AgentManagementController_configurations",
    "controller": "AgentManagementController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations",
    "operationId": "AgentConfigurationController_append",
    "controller": "AgentConfigurationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations/{revision}",
    "operationId": "AgentConfigurationController_revision",
    "controller": "AgentConfigurationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations/current",
    "operationId": "AgentConfigurationController_current",
    "controller": "AgentConfigurationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/disable",
    "operationId": "AgentManagementController_disable",
    "controller": "AgentManagementController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/model-catalog",
    "operationId": "AgentConfigurationController_catalog",
    "controller": "AgentConfigurationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/model-preflight",
    "operationId": "AgentConfigurationController_preflight",
    "controller": "AgentConfigurationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/agent-templates",
    "operationId": "AgentManagementController_templates",
    "controller": "AgentManagementController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/audit-events",
    "operationId": "AuditController_history",
    "controller": "AuditController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/audit-events/export",
    "operationId": "AuditController_export",
    "controller": "AuditController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/configuration-health",
    "operationId": "TeamSetupReadinessController_configurationHealth",
    "controller": "TeamSetupReadinessController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/configuration-search",
    "operationId": "TeamSetupReadinessController_configurationSearch",
    "controller": "TeamSetupReadinessController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations",
    "operationId": "ConversationController_list",
    "controller": "ConversationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations",
    "operationId": "ConversationController_create",
    "controller": "ConversationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}",
    "operationId": "ConversationController_get",
    "controller": "ConversationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-configuration-refresh",
    "operationId": "ConversationConfigurationController_refresh",
    "controller": "ConversationConfigurationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-configuration",
    "operationId": "ConversationConfigurationController_status",
    "controller": "ConversationConfigurationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations",
    "operationId": "PersonalAgentInvocationController_invoke",
    "controller": "PersonalAgentInvocationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/cancel",
    "operationId": "PersonalAgentInvocationController_cancel",
    "controller": "PersonalAgentInvocationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/resume",
    "operationId": "PersonalAgentInvocationController_resume",
    "controller": "PersonalAgentInvocationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/delivery-cards",
    "operationId": "TaskDeliverySummaryController_conversation",
    "controller": "TaskDeliverySummaryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/events",
    "operationId": "ConversationEventController_history",
    "controller": "ConversationEventController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/events",
    "operationId": "ConversationEventController_stream",
    "controller": "ConversationEventController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/messages",
    "operationId": "ConversationController_messages",
    "controller": "ConversationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/messages",
    "operationId": "ConversationController_postMessage",
    "controller": "ConversationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants",
    "operationId": "ConversationController_addParticipant",
    "controller": "ConversationController"
  },
  {
    "method": "delete",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants/{participantId}",
    "operationId": "ConversationController_removeParticipant",
    "controller": "ConversationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}",
    "operationId": "TaskIntentController_get",
    "controller": "TaskIntentController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmation-previews",
    "operationId": "TaskIntentController_previewConfirmation",
    "controller": "TaskIntentController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmations",
    "operationId": "TaskIntentController_confirm",
    "controller": "TaskIntentController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/rejections",
    "operationId": "TaskIntentController_reject",
    "controller": "TaskIntentController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/revisions",
    "operationId": "TaskIntentController_revise",
    "controller": "TaskIntentController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/tasks",
    "operationId": "TaskAssociationController_byConversation",
    "controller": "TaskAssociationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/work-items",
    "operationId": "ConversationWorkItemLinkController_byConversation",
    "controller": "ConversationWorkItemLinkController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/correlations/{correlationId}",
    "operationId": "CorrelationController_find",
    "controller": "CorrelationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox",
    "operationId": "InboxController_list",
    "controller": "InboxController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/{inboxItemId}",
    "operationId": "InboxController_detail",
    "controller": "InboxController"
  },
  {
    "method": "put",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/{inboxItemId}/disposition",
    "operationId": "InboxController_changeDisposition",
    "controller": "InboxController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/{inboxItemId}/target",
    "operationId": "InboxController_target",
    "controller": "InboxController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/inbox/counts",
    "operationId": "InboxController_counts",
    "controller": "InboxController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/initialization",
    "operationId": "TeamController_completeInitialization",
    "controller": "TeamController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/invitations",
    "operationId": "TeamInvitationController_list",
    "controller": "TeamInvitationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/invitations",
    "operationId": "TeamInvitationController_create",
    "controller": "TeamInvitationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/invitations/{invitationId}/revoke",
    "operationId": "TeamInvitationController_revoke",
    "controller": "TeamInvitationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/bindings/{bindingId}/health",
    "operationId": "LarkAdministrationController_health",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/bindings/{bindingId}/preflight",
    "operationId": "LarkAdministrationController_preflight",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections",
    "operationId": "LarkAdministrationController_listConnections",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections",
    "operationId": "LarkAdministrationController_createConnection",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections/{connectionId}",
    "operationId": "LarkAdministrationController_getConnection",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections/{connectionId}/revoke",
    "operationId": "LarkAdministrationController_revokeConnection",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/connections/{connectionId}/rotate",
    "operationId": "LarkAdministrationController_rotateConnection",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-mappings",
    "operationId": "LarkAdministrationController_listMappings",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-mappings",
    "operationId": "LarkAdministrationController_confirmMapping",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-mappings/{mappingId}/revoke",
    "operationId": "LarkAdministrationController_revokeMapping",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/member-verifications",
    "operationId": "LarkAdministrationController_verifyMember",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-deliveries",
    "operationId": "LarkAdministrationController_deliveries",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-deliveries/{deliveryId}",
    "operationId": "LarkAdministrationController_delivery",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-deliveries/{deliveryId}/redeliver",
    "operationId": "LarkAdministrationController_redeliver",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-preferences/{memberId}",
    "operationId": "LarkAdministrationController_preference",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "put",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-preferences/{memberId}",
    "operationId": "LarkAdministrationController_updatePreference",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/lark/notification-templates",
    "operationId": "LarkAdministrationController_templates",
    "controller": "LarkAdministrationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/members",
    "operationId": "TeamController_listMembers",
    "controller": "TeamController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/members",
    "operationId": "TeamController_addMember",
    "controller": "TeamController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/operations/health",
    "operationId": "OperationsController_health",
    "controller": "OperationsController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/principals",
    "operationId": "PrincipalDirectoryController_search",
    "controller": "PrincipalDirectoryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/provider-bindings",
    "operationId": "ProviderBindingController_getDefault",
    "controller": "ProviderBindingController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health",
    "operationId": "RuntimeObservationController_summary",
    "controller": "RuntimeObservationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health/operations",
    "operationId": "RuntimeObservationController_operations",
    "controller": "RuntimeObservationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/setup-readiness",
    "operationId": "TeamSetupReadinessController_get",
    "controller": "TeamSetupReadinessController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks",
    "operationId": "TaskQueryController_list",
    "controller": "TaskQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}",
    "operationId": "TaskQueryController_get",
    "controller": "TaskQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/associations",
    "operationId": "TaskAssociationController_byTask",
    "controller": "TaskAssociationController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts",
    "operationId": "TaskQueryController_attempts",
    "controller": "TaskQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles",
    "operationId": "ActionDeliveryController_list",
    "controller": "ActionDeliveryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles",
    "operationId": "ActionDeliveryController_plan",
    "controller": "ActionDeliveryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles/{bundleId}",
    "operationId": "ActionDeliveryController_get",
    "controller": "ActionDeliveryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/bundles/{bundleId}/confirmations",
    "operationId": "ActionDeliveryController_confirm",
    "controller": "ActionDeliveryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/confirmations/{confirmationId}/cancel",
    "operationId": "ActionDeliveryController_cancel",
    "controller": "ActionDeliveryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions/dispatches/{dispatchId}/manual-resolution",
    "operationId": "ActionDeliveryController_resolveManually",
    "controller": "ActionDeliveryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/cancel",
    "operationId": "TaskCommandController_cancel",
    "controller": "TaskCommandController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding",
    "operationId": "TaskCodingQueryController_attempt",
    "controller": "TaskCodingQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/artifacts/patch",
    "operationId": "CodingArtifactController_patch",
    "controller": "CodingArtifactController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/commands",
    "operationId": "TaskCodingQueryController_commands",
    "controller": "TaskCodingQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/commands/{commandEvidenceId}/log",
    "operationId": "CodingArtifactController_buildLog",
    "controller": "CodingArtifactController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/test-evidence",
    "operationId": "TaskCodingQueryController_testEvidence",
    "controller": "TaskCodingQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/test-evidence/{testEvidenceId}/report",
    "operationId": "CodingArtifactController_testReport",
    "controller": "CodingArtifactController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/pause",
    "operationId": "TaskCommandController_pause",
    "controller": "TaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/resume",
    "operationId": "TaskCommandController_resume",
    "controller": "TaskCommandController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/retry",
    "operationId": "TaskCommandController_retry",
    "controller": "TaskCommandController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews",
    "operationId": "ReviewController_list",
    "controller": "ReviewController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews",
    "operationId": "ReviewController_create",
    "controller": "ReviewController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}",
    "operationId": "ReviewController_get",
    "controller": "ReviewController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/decisions",
    "operationId": "ReviewController_decision",
    "controller": "ReviewController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/execute",
    "operationId": "ReviewController_execute",
    "controller": "ReviewController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/modifications",
    "operationId": "ReviewController_modifications",
    "controller": "ReviewController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/re-review",
    "operationId": "ReviewController_reReview",
    "controller": "ReviewController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/runtime-facts",
    "operationId": "TaskQueryController_runtimeFacts",
    "controller": "TaskQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/coding-attempts",
    "operationId": "TaskCodingQueryController_attempts",
    "controller": "TaskCodingQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/coding",
    "operationId": "TaskCodingQueryController_current",
    "controller": "TaskCodingQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/delivery-summary",
    "operationId": "TaskDeliverySummaryController_task",
    "controller": "TaskDeliverySummaryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events",
    "operationId": "TaskEventController_history",
    "controller": "TaskEventController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events",
    "operationId": "TaskEventController_stream",
    "controller": "TaskEventController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions",
    "operationId": "TeamObserverController_createSession",
    "controller": "TeamObserverController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations",
    "operationId": "TeamObserverController_invoke",
    "controller": "TeamObserverController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/cancel",
    "operationId": "TeamObserverController_cancel",
    "controller": "TeamObserverController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/evidence/{evidenceIndex}",
    "operationId": "TeamObserverController_evidence",
    "controller": "TeamObserverController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/resume",
    "operationId": "TeamObserverController_resume",
    "controller": "TeamObserverController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions/{sessionId}/invocations/{invocationId}/summary",
    "operationId": "TeamObserverController_summary",
    "controller": "TeamObserverController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk",
    "operationId": "WorkDeskController_get",
    "controller": "WorkDeskController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects",
    "operationId": "WorkProjectController_list",
    "controller": "WorkProjectController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects",
    "operationId": "WorkProjectController_create",
    "controller": "WorkProjectController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}",
    "operationId": "WorkProjectController_get",
    "controller": "WorkProjectController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports",
    "operationId": "GitHubRepositoryImportController_create",
    "controller": "GitHubRepositoryImportController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}",
    "operationId": "GitHubRepositoryImportController_get",
    "controller": "GitHubRepositoryImportController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}/cancel",
    "operationId": "GitHubRepositoryImportController_cancel",
    "controller": "GitHubRepositoryImportController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}/retry",
    "operationId": "GitHubRepositoryImportController_retry",
    "controller": "GitHubRepositoryImportController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings",
    "operationId": "RepositoryBindingController_list",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings",
    "operationId": "RepositoryBindingController_create",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}",
    "operationId": "RepositoryBindingController_get",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}/activate",
    "operationId": "RepositoryBindingController_activate",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}/disable",
    "operationId": "RepositoryBindingController_disable",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/{bindingId}/preflight",
    "operationId": "RepositoryBindingController_preflightExisting",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings/preflight",
    "operationId": "RepositoryBindingController_preflightDraft",
    "controller": "RepositoryBindingController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-catalog",
    "operationId": "RepositoryCatalogController_list",
    "controller": "RepositoryCatalogController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items",
    "operationId": "WorkItemQueryController_list",
    "controller": "WorkItemQueryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items",
    "operationId": "WorkItemController_create",
    "controller": "WorkItemController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}",
    "operationId": "WorkItemQueryController_get",
    "controller": "WorkItemQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity",
    "operationId": "WorkItemActivityController_history",
    "controller": "WorkItemActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity/{activityEventId}",
    "operationId": "WorkItemActivityController_detail",
    "controller": "WorkItemActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity/snapshot",
    "operationId": "WorkItemActivityController_snapshot",
    "controller": "WorkItemActivityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/coding-target/build-profiles",
    "operationId": "CodingTargetController_listBuildProfiles",
    "controller": "CodingTargetController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/coding-target/preflight",
    "operationId": "CodingTargetController_preflight",
    "controller": "CodingTargetController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments",
    "operationId": "WorkItemQueryController_comments",
    "controller": "WorkItemQueryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments",
    "operationId": "WorkItemQueryController_addComment",
    "controller": "WorkItemQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/conversations",
    "operationId": "ConversationWorkItemLinkController_byWorkItem",
    "controller": "ConversationWorkItemLinkController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links",
    "operationId": "WorkItemQueryController_resourceLinks",
    "controller": "WorkItemQueryController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links",
    "operationId": "WorkItemQueryController_linkResource",
    "controller": "WorkItemQueryController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities",
    "operationId": "ResponsibilityController_list",
    "controller": "ResponsibilityController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/{assignmentId}/releases",
    "operationId": "ResponsibilityController_release",
    "controller": "ResponsibilityController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/advisory-reviewers",
    "operationId": "ResponsibilityController_assignAdvisoryReviewer",
    "controller": "ResponsibilityController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/executors",
    "operationId": "ResponsibilityController_assignExecutor",
    "controller": "ResponsibilityController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/gate-reviewers",
    "operationId": "ResponsibilityController_assignGateReviewer",
    "controller": "ResponsibilityController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/owner",
    "operationId": "ResponsibilityController_replaceOwner",
    "controller": "ResponsibilityController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks",
    "operationId": "TaskAssociationController_byWorkItem",
    "controller": "TaskAssociationController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks",
    "operationId": "TaskController_create",
    "controller": "TaskController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks/preflight",
    "operationId": "TaskController_preflight",
    "controller": "TaskController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/timeline",
    "operationId": "WorkItemTimelineController_list",
    "controller": "WorkItemTimelineController"
  },
  {
    "method": "post",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions",
    "operationId": "WorkItemController_transition",
    "controller": "WorkItemController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions/availability",
    "operationId": "WorkItemTransitionController_availability",
    "controller": "WorkItemTransitionController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/keys/{projectKey}",
    "operationId": "WorkProjectController_keyAvailability",
    "controller": "WorkProjectController"
  },
  {
    "method": "get",
    "path": "/api/v1/organizations/{organizationId}/teams/{teamId}/workspaces/default",
    "operationId": "TeamController_getDefaultWorkspace",
    "controller": "TeamController"
  },
  {
    "method": "get",
    "path": "/api/v1/system/info",
    "operationId": "SystemInfoController_info",
    "controller": "SystemInfoController"
  }
] as const
export const openApiOperationCount = 212 as const
