package io.crewscope.infrastructure.persistence.conversation;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.conversation.ConversationParticipantStatus;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.conversation.ConversationWorkItemLinkId;
import io.crewscope.domain.conversation.ConversationWorkItemLinkOrigin;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentDecision;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.TaskIntentResponsibility;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Lossless mapping between M2 conversation domain objects and scalar JPA snapshots. */
@Component
public class ConversationPersistenceMapper {

    ConversationEntity toEntity(Conversation value) {
        ConversationEntity row = new ConversationEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.ownerMemberId = value.ownerMemberId().value();
        row.ownerPrincipalId = value.ownerPrincipalId().value();
        row.personalAgentPrincipalId = value.personalAgentPrincipalId().value();
        row.title = value.title();
        row.visibility = value.visibility().name();
        row.status = value.status().name();
        row.lastMessageSequence = value.lastMessageSequence().map(MessageSequence::value).orElse(null);
        row.version = value.version();
        putAudit(row, value.audit());
        return row;
    }

    Conversation toDomain(ConversationEntity row) {
        return Conversation.reconstitute(
                new ConversationId(row.id),
                scope(row.organizationId, row.teamId, row.workspaceId),
                new TeamMemberId(row.ownerMemberId),
                new PrincipalId(row.ownerPrincipalId),
                new PrincipalId(row.personalAgentPrincipalId),
                row.title,
                ConversationVisibility.valueOf(row.visibility),
                ConversationStatus.valueOf(row.status),
                Optional.ofNullable(row.lastMessageSequence).map(MessageSequence::new),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    ConversationParticipantEntity toEntity(ConversationParticipant value) {
        ConversationParticipantEntity row = new ConversationParticipantEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.conversationId = value.conversationId().value();
        row.principalId = value.principalId().value();
        row.teamMemberId = value.teamMemberId().map(TeamMemberId::value).orElse(null);
        row.role = value.role().name();
        row.status = value.status().name();
        row.joinedByPrincipalId = value.joinedByPrincipalId().value();
        row.joinedAt = value.joinedAt().value();
        row.leftAt = value.leftAt().map(UtcTimestamp::value).orElse(null);
        row.version = value.version();
        row.createdAt = value.audit().createdAt().value();
        row.createdByPrincipalId = principal(value.audit().createdBy(), "participant.createdBy");
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "participant.updatedBy");
        return row;
    }

    ConversationParticipant toDomain(ConversationParticipantEntity row) {
        return ConversationParticipant.reconstitute(
                new ConversationParticipantId(row.id),
                scope(row.organizationId, row.teamId, row.workspaceId),
                new ConversationId(row.conversationId),
                new PrincipalId(row.principalId),
                Optional.ofNullable(row.teamMemberId).map(TeamMemberId::new),
                ConversationParticipantRole.valueOf(row.role),
                ConversationParticipantStatus.valueOf(row.status),
                new PrincipalId(row.joinedByPrincipalId),
                UtcTimestamp.from(row.joinedAt),
                Optional.ofNullable(row.leftAt).map(UtcTimestamp::from),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    MessageEntity toEntity(Message value, Optional<String> clientMessageKey) {
        MessageEntity row = new MessageEntity();
        row.id = value.id().value();
        row.organizationId = value.scope().organizationId().value();
        row.teamId = value.scope().teamId().value();
        row.workspaceId = value.scope().workspaceId().value();
        row.conversationId = value.conversationId().value();
        row.sequence = value.sequence().value();
        row.messageType = value.type().name();
        row.participantId = value.participantId().map(ConversationParticipantId::value).orElse(null);
        row.authorPrincipalId = value.authorPrincipalId().map(PrincipalId::value).orElse(null);
        row.contentMarkdown = value.content().markdown();
        row.clientMessageKey = clientMessageKey.orElse(null);
        row.moderationStatus = "VISIBLE";
        row.createdAt = value.audit().createdAt().value();
        row.createdByPrincipalId = principal(value.audit().createdBy(), "message.createdBy");
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "message.updatedBy");
        return row;
    }

    Message toDomain(MessageEntity row) {
        if (!"VISIBLE".equals(row.moderationStatus)) {
            throw new DomainValidationException(
                    "message.moderationStatus", "moderated content requires a dedicated read model");
        }
        return Message.reconstitute(
                new MessageId(row.id),
                scope(row.organizationId, row.teamId, row.workspaceId),
                new ConversationId(row.conversationId),
                new MessageSequence(row.sequence),
                MessageType.valueOf(row.messageType),
                Optional.ofNullable(row.participantId).map(ConversationParticipantId::new),
                Optional.ofNullable(row.authorPrincipalId).map(PrincipalId::new),
                new MessageContent(row.contentMarkdown),
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    TaskIntentEntity toEntity(TaskIntent value, Optional<WorkItemId> confirmedWorkItemId) {
        TaskIntentEntity row = new TaskIntentEntity();
        row.id = value.id().value();
        row.organizationId = value.scope().organizationId().value();
        row.teamId = value.scope().teamId().value();
        row.workspaceId = value.scope().workspaceId().value();
        row.conversationId = value.conversationId().value();
        row.proposedByPrincipalId = value.proposedByPrincipalId().value();
        row.proposalRevision = value.proposalRevision();
        TaskIntentProposal proposal = value.proposal();
        row.workProjectId = proposal.targetScope().projectId().value();
        row.objective = proposal.objective();
        row.acceptanceCriteria = proposal.acceptanceCriteria();
        putResponsibility(row, proposal.owner(), ResponsibilityRole.OWNER);
        proposal.executor().ifPresent(item -> putResponsibility(row, item, ResponsibilityRole.EXECUTOR));
        proposal.gateReviewer().ifPresent(item -> putResponsibility(row, item, ResponsibilityRole.REVIEWER));
        row.status = value.status().name();
        value.decision().ifPresent(decision -> {
            row.decidedByPrincipalId = decision.decidedByPrincipalId().value();
            row.decidedAt = decision.decidedAt().value();
            row.decisionReason = decision.reason().orElse(null);
        });
        row.confirmedWorkItemId = confirmedWorkItemId.map(WorkItemId::value).orElse(null);
        row.version = value.version();
        row.createdAt = value.audit().createdAt().value();
        row.createdByPrincipalId = principal(value.audit().createdBy(), "taskIntent.createdBy");
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "taskIntent.updatedBy");
        return row;
    }

    TaskIntent toDomain(TaskIntentEntity row) {
        WorkItemScope targetScope = new WorkItemScope(
                new OrganizationId(row.organizationId),
                new TeamId(row.teamId),
                new WorkspaceId(row.workspaceId),
                new WorkProjectId(row.workProjectId));
        TaskIntentProposal proposal = new TaskIntentProposal(
                targetScope,
                row.objective,
                row.acceptanceCriteria,
                responsibility(
                        ResponsibilityRole.OWNER,
                        row.ownerPrincipalId,
                        row.ownerPrincipalType,
                        row.ownerMemberId),
                optionalResponsibility(
                        ResponsibilityRole.EXECUTOR,
                        row.executorPrincipalId,
                        row.executorPrincipalType,
                        row.executorMemberId),
                optionalResponsibility(
                        ResponsibilityRole.REVIEWER,
                        row.gateReviewerPrincipalId,
                        row.gateReviewerPrincipalType,
                        row.gateReviewerMemberId));
        TaskIntentStatus status = TaskIntentStatus.valueOf(row.status);
        Optional<TaskIntentDecision> decision = Optional.ofNullable(row.decidedByPrincipalId)
                .map(ignored -> new TaskIntentDecision(
                        status,
                        new PrincipalId(row.decidedByPrincipalId),
                        UtcTimestamp.from(row.decidedAt),
                        Optional.ofNullable(row.decisionReason)));
        return TaskIntent.reconstitute(
                new TaskIntentId(row.id),
                scope(row.organizationId, row.teamId, row.workspaceId),
                new ConversationId(row.conversationId),
                new PrincipalId(row.proposedByPrincipalId),
                row.proposalRevision,
                proposal,
                status,
                decision,
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    ConversationWorkItemLinkEntity toEntity(ConversationWorkItemLink value) {
        ConversationWorkItemLinkEntity row = new ConversationWorkItemLinkEntity();
        row.id = value.id().value();
        row.organizationId = value.scope().organizationId().value();
        row.teamId = value.scope().teamId().value();
        row.workspaceId = value.scope().workspaceId().value();
        row.conversationId = value.conversationId().value();
        row.workProjectId = value.workProjectId().value();
        row.workItemId = value.workItemId().value();
        row.origin = value.origin().name();
        row.createdByPrincipalId = value.createdByPrincipalId().value();
        row.createdAt = value.audit().createdAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "link.updatedBy");
        row.updatedAt = value.audit().updatedAt().value();
        return row;
    }

    ConversationWorkItemLink toDomain(ConversationWorkItemLinkEntity row) {
        return ConversationWorkItemLink.reconstitute(
                new ConversationWorkItemLinkId(row.id),
                scope(row.organizationId, row.teamId, row.workspaceId),
                new ConversationId(row.conversationId),
                new WorkProjectId(row.workProjectId),
                new WorkItemId(row.workItemId),
                ConversationWorkItemLinkOrigin.valueOf(row.origin),
                new PrincipalId(row.createdByPrincipalId),
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    AgentRuntimeSessionEntity toEntity(AgentRuntimeSession value) {
        AgentRuntimeSessionEntity row = new AgentRuntimeSessionEntity();
        row.id = value.id().value();
        row.organizationId = value.scope().organizationId().value();
        row.teamId = value.scope().teamId().value();
        row.workspaceId = value.scope().workspaceId().value();
        row.conversationId = value.conversationId().value();
        row.ownerMemberId = value.ownerMemberId().value();
        row.ownerPrincipalId = value.ownerPrincipalId().value();
        row.personalAgentPrincipalId = value.personalAgentPrincipalId().value();
        row.agentProfileId = value.agentProfileId().value();
        row.agentProfileVersion = value.agentProfileVersion();
        row.agentScopeUserId = value.agentScopeKey().userId();
        row.agentScopeSessionId = value.agentScopeKey().sessionId();
        row.stateReference = value.stateReference().value();
        row.status = value.status().name();
        row.version = value.version();
        row.createdAt = value.audit().createdAt().value();
        row.createdByPrincipalId = principal(value.audit().createdBy(), "session.createdBy");
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "session.updatedBy");
        return row;
    }

    AgentRuntimeSession toDomain(AgentRuntimeSessionEntity row) {
        return AgentRuntimeSession.reconstitute(
                new AgentRuntimeSessionId(row.id),
                scope(row.organizationId, row.teamId, row.workspaceId),
                new ConversationId(row.conversationId),
                new TeamMemberId(row.ownerMemberId),
                new PrincipalId(row.ownerPrincipalId),
                new PrincipalId(row.personalAgentPrincipalId),
                new AgentProfileId(row.agentProfileId),
                row.agentProfileVersion,
                new AgentScopeSessionKey(row.agentScopeUserId, row.agentScopeSessionId),
                new AgentRuntimeStateReference(row.stateReference),
                AgentRuntimeSessionStatus.valueOf(row.status),
                row.version,
                audit(row.createdByPrincipalId, row.createdAt, row.updatedByPrincipalId, row.updatedAt));
    }

    private static ConversationScope scope(UUID organizationId, UUID teamId, UUID workspaceId) {
        return new ConversationScope(
                new OrganizationId(organizationId), new TeamId(teamId), new WorkspaceId(workspaceId));
    }

    private static void putScope(ConversationEntity row, ConversationScope scope) {
        row.organizationId = scope.organizationId().value();
        row.teamId = scope.teamId().value();
        row.workspaceId = scope.workspaceId().value();
    }

    private static void putScope(ConversationParticipantEntity row, ConversationScope scope) {
        row.organizationId = scope.organizationId().value();
        row.teamId = scope.teamId().value();
        row.workspaceId = scope.workspaceId().value();
    }

    private static void putAudit(
            ConversationEntity row, io.crewscope.domain.shared.audit.AuditMetadata audit) {
        row.createdAt = audit.createdAt().value();
        row.createdByPrincipalId = principal(audit.createdBy(), "conversation.createdBy");
        row.updatedAt = audit.updatedAt().value();
        row.updatedByPrincipalId = principal(audit.updatedBy(), "conversation.updatedBy");
    }

    private static UUID principal(Optional<PrincipalId> value, String field) {
        return value.orElseThrow(() -> new DomainValidationException(field, "must be present")).value();
    }

    private static void putResponsibility(
            TaskIntentEntity row, TaskIntentResponsibility value, ResponsibilityRole role) {
        UUID principalId = value.principalId().value();
        String principalType = value.principalType().name();
        UUID memberId = value.memberId().map(TeamMemberId::value).orElse(null);
        switch (role) {
            case OWNER -> {
                row.ownerPrincipalId = principalId;
                row.ownerPrincipalType = principalType;
                row.ownerMemberId = memberId;
            }
            case EXECUTOR -> {
                row.executorPrincipalId = principalId;
                row.executorPrincipalType = principalType;
                row.executorMemberId = memberId;
            }
            case REVIEWER -> {
                row.gateReviewerPrincipalId = principalId;
                row.gateReviewerPrincipalType = principalType;
                row.gateReviewerMemberId = memberId;
            }
        }
    }

    private static Optional<TaskIntentResponsibility> optionalResponsibility(
            ResponsibilityRole role, UUID principalId, String principalType, UUID memberId) {
        return Optional.ofNullable(principalId)
                .map(value -> responsibility(role, value, principalType, memberId));
    }

    private static TaskIntentResponsibility responsibility(
            ResponsibilityRole role, UUID principalId, String principalType, UUID memberId) {
        return new TaskIntentResponsibility(
                role,
                new PrincipalId(principalId),
                PrincipalType.valueOf(principalType),
                Optional.ofNullable(memberId).map(TeamMemberId::new));
    }
}
