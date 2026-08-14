package io.crewscope.domain.task;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

final class TaskDomainFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-13T08:00:00Z");
    static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-13T09:00:00Z");

    final WorkItemScope scope = new WorkItemScope(
            OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
    final Principal owner = user("Owner");
    final Principal executor = agent("Executor", owner.id());
    final Principal reviewer = user("Reviewer");
    final WorkItem workItem = workItem(scope, "CRW-1");
    final Conversation conversation = conversation(scope, owner, executor);
    final Message message = message(conversation, owner);
    final ResponsibilityAssignment ownerAssignment = assignment(
            workItem, ResponsibilityRole.OWNER, owner, Optional.of(TeamMemberId.generate()));
    final ResponsibilityAssignment executorAssignment =
            assignment(workItem, ResponsibilityRole.EXECUTOR, executor, Optional.empty());
    final ResponsibilityAssignment reviewerAssignment = assignment(
            workItem, ResponsibilityRole.REVIEWER, reviewer, Optional.of(TeamMemberId.generate()));

    TaskResponsibilitySnapshot snapshot() {
        return TaskResponsibilitySnapshot.capture(
                workItem,
                List.of(ownerAssignment, executorAssignment, reviewerAssignment),
                CREATED_AT);
    }

    Task task() {
        return Task.create(
                TaskId.generate(),
                workItem,
                TaskSource.fromWorkItem(workItem),
                snapshot(),
                owner,
                CREATED_AT);
    }

    WorkItem workItem(WorkItemScope targetScope, String key) {
        return WorkItem.reconstitute(
                WorkItemId.generate(),
                targetScope,
                new WorkItemKey(key),
                "Task source",
                WorkItemStatus.READY,
                7,
                AuditMetadata.createdBy(owner.id(), CREATED_AT));
    }

    Principal user(String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(scope.organizationId(), scope.teamId()),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                CREATED_AT);
    }

    Principal agent(String name, PrincipalId ownerId) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(scope.organizationId(), scope.teamId()),
                PrincipalType.TEAM_AGENT,
                Optional.of(ownerId),
                name,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                CREATED_AT);
    }

    static Conversation conversation(
            WorkItemScope targetScope, Principal owner, Principal personalAgent) {
        ConversationScope conversationScope = new ConversationScope(
                targetScope.organizationId(), targetScope.teamId(), targetScope.workspaceId());
        return Conversation.reconstitute(
                ConversationId.generate(),
                conversationScope,
                TeamMemberId.generate(),
                owner.id(),
                personalAgent.id(),
                "Task conversation",
                ConversationVisibility.TEAM,
                ConversationStatus.ACTIVE,
                Optional.of(MessageSequence.first()),
                1,
                AuditMetadata.createdBy(owner.id(), CREATED_AT));
    }

    static Message message(Conversation conversation, Principal actor) {
        return Message.reconstitute(
                MessageId.generate(),
                conversation.scope(),
                conversation.id(),
                MessageSequence.first(),
                MessageType.SYSTEM_NOTICE,
                Optional.empty(),
                Optional.empty(),
                new MessageContent("Execute the accepted work"),
                AuditMetadata.createdBy(actor.id(), CREATED_AT));
    }

    static ResponsibilityAssignment assignment(
            WorkItem workItem,
            ResponsibilityRole role,
            Principal principal,
            Optional<TeamMemberId> memberId) {
        return ResponsibilityAssignment.reconstitute(
                ResponsibilityAssignmentId.generate(),
                workItem.scope(),
                workItem.id(),
                role,
                principal.id(),
                principal.type(),
                memberId,
                ResponsibilityAssignmentStatus.ACTIVE,
                principal.id(),
                CREATED_AT,
                CREATED_AT,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(principal.id(), CREATED_AT));
    }
}
