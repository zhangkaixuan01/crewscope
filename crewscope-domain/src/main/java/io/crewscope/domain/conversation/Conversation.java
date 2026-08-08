package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Objects;
import java.util.Optional;

/** Durable conversation boundary owned by one Team member and served by a Personal Agent. */
public final class Conversation {

    public static final int MAX_TITLE_LENGTH = 200;

    private final ConversationId id;
    private final ConversationScope scope;
    private final TeamMemberId ownerMemberId;
    private final PrincipalId ownerPrincipalId;
    private final PrincipalId personalAgentPrincipalId;
    private final String title;
    private final ConversationVisibility visibility;
    private final ConversationStatus status;
    private final Optional<MessageSequence> lastMessageSequence;
    private final long version;
    private final AuditMetadata audit;

    private Conversation(
            ConversationId id,
            ConversationScope scope,
            TeamMemberId ownerMemberId,
            PrincipalId ownerPrincipalId,
            PrincipalId personalAgentPrincipalId,
            String title,
            ConversationVisibility visibility,
            ConversationStatus status,
            Optional<MessageSequence> lastMessageSequence,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        this.ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
        this.personalAgentPrincipalId = requireDistinctAgent(
                ownerPrincipalId, personalAgentPrincipalId);
        this.title = requireTitle(title);
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.status = Objects.requireNonNull(status, "status");
        this.lastMessageSequence = Objects.requireNonNull(
                lastMessageSequence, "lastMessageSequence");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        if (this.audit.createdBy().filter(this.ownerPrincipalId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "conversation.audit.createdBy",
                    "must preserve the Conversation owner Principal");
        }
    }

    /** Starts a Personal Agent Conversation after closing all member and Workspace references. */
    public static Conversation startPersonal(
            ConversationId id,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            String title,
            ConversationVisibility visibility,
            UtcTimestamp occurredAt) {
        Workspace requiredWorkspace = requireActiveTeamWorkspace(workspace, ownerMember);
        ConversationScope scope = new ConversationScope(
                requiredWorkspace.scope().organizationId(),
                requiredWorkspace.scope().teamId().orElseThrow(),
                requiredWorkspace.id());
        PrincipalId ownerId = ConversationActorPolicy.requireActiveOwner(
                ownerMember, ownerUser, scope, "conversation.ownerMemberId");
        PersonalAgentInitialization requiredPersonalAgent =
                Objects.requireNonNull(personalAgent, "personalAgent")
                        .requireDefaultFor(ownerMember, requiredWorkspace);
        requireActivePersonalAgent(requiredPersonalAgent, ownerId, scope);
        return new Conversation(
                id,
                scope,
                ownerMember.id(),
                ownerId,
                requiredPersonalAgent.agentPrincipal().id(),
                title,
                visibility,
                ConversationStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(ownerId, occurredAt));
    }

    /** Reconstitutes a committed Conversation without applying a lifecycle transition. */
    public static Conversation reconstitute(
            ConversationId id,
            ConversationScope scope,
            TeamMemberId ownerMemberId,
            PrincipalId ownerPrincipalId,
            PrincipalId personalAgentPrincipalId,
            String title,
            ConversationVisibility visibility,
            ConversationStatus status,
            Optional<MessageSequence> lastMessageSequence,
            long version,
            AuditMetadata audit) {
        return new Conversation(
                id,
                scope,
                ownerMemberId,
                ownerPrincipalId,
                personalAgentPrincipalId,
                title,
                visibility,
                status,
                lastMessageSequence,
                version,
                audit);
    }

    /** Appends an authored Message and advances sequence, aggregate version and activity time. */
    public ConversationMessageAppend appendMessage(
            MessageId messageId,
            ConversationParticipant participant,
            Principal author,
            MessageContent content,
            UtcTimestamp occurredAt) {
        MessageSequence sequence = nextMessageSequence();
        Message message = Message.post(
                messageId, this, participant, author, sequence, content, occurredAt);
        PrincipalId actorId = message.authorPrincipalId().orElseThrow();
        return new ConversationMessageAppend(
                copy(
                        title,
                        visibility,
                        status,
                        Optional.of(sequence),
                        version + 1,
                        audit.modifiedBy(actorId, occurredAt)),
                message);
    }

    /** Appends a system notice without assigning a participant or forged Message author. */
    public ConversationMessageAppend appendSystemNotice(
            MessageId messageId,
            Principal actor,
            MessageContent content,
            UtcTimestamp occurredAt) {
        MessageSequence sequence = nextMessageSequence();
        Message message = Message.systemNotice(
                messageId, this, actor, sequence, content, occurredAt);
        PrincipalId actorId = message.audit().createdBy().orElseThrow();
        return new ConversationMessageAppend(
                copy(
                        title,
                        visibility,
                        status,
                        Optional.of(sequence),
                        version + 1,
                        audit.modifiedBy(actorId, occurredAt)),
                message);
    }

    /** Changes discoverability while preserving the Conversation owner and participant facts. */
    public Conversation changeVisibility(
            ConversationVisibility target, Principal actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        ensureActiveForVisibilityChange();
        if (visibility == target) {
            throw new DomainValidationException(
                    "conversation.visibility", "already has the requested visibility");
        }
        PrincipalId actorId = ConversationActorPolicy.requireActiveInScope(
                actor, scope, "conversation.updatedByPrincipalId");
        return copy(
                title,
                target,
                status,
                lastMessageSequence,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Archives the Conversation while preserving historical read visibility. */
    public Conversation archive(Principal actor, UtcTimestamp occurredAt) {
        if (status == ConversationStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "Conversation", id, ConversationStatus.ARCHIVED, ConversationStatus.ARCHIVED);
        }
        PrincipalId actorId = ConversationActorPolicy.requireActiveInScope(
                actor, scope, "conversation.updatedByPrincipalId");
        return copy(
                title,
                visibility,
                ConversationStatus.ARCHIVED,
                lastMessageSequence,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean acceptsMessages() {
        return status == ConversationStatus.ACTIVE;
    }

    public ConversationId id() {
        return id;
    }

    public ConversationScope scope() {
        return scope;
    }

    public TeamMemberId ownerMemberId() {
        return ownerMemberId;
    }

    public PrincipalId ownerPrincipalId() {
        return ownerPrincipalId;
    }

    public PrincipalId personalAgentPrincipalId() {
        return personalAgentPrincipalId;
    }

    public String title() {
        return title;
    }

    public ConversationVisibility visibility() {
        return visibility;
    }

    public ConversationStatus status() {
        return status;
    }

    public Optional<MessageSequence> lastMessageSequence() {
        return lastMessageSequence;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private MessageSequence nextMessageSequence() {
        if (!acceptsMessages()) {
            throw new DomainValidationException(
                    "conversation.status", "must be ACTIVE to append a Message");
        }
        return lastMessageSequence.map(MessageSequence::next).orElseGet(MessageSequence::first);
    }

    private void ensureActiveForVisibilityChange() {
        if (!acceptsMessages()) {
            throw new DomainValidationException(
                    "conversation.status", "must be ACTIVE to change visibility");
        }
    }

    private Conversation copy(
            String targetTitle,
            ConversationVisibility targetVisibility,
            ConversationStatus targetStatus,
            Optional<MessageSequence> targetLastMessageSequence,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new Conversation(
                id,
                scope,
                ownerMemberId,
                ownerPrincipalId,
                personalAgentPrincipalId,
                targetTitle,
                targetVisibility,
                targetStatus,
                targetLastMessageSequence,
                targetVersion,
                targetAudit);
    }

    private static Workspace requireActiveTeamWorkspace(
            Workspace workspace, TeamMember ownerMember) {
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
        if (requiredWorkspace.type() != WorkspaceType.TEAM
                || requiredWorkspace.status() != WorkspaceStatus.ACTIVE
                || !requiredWorkspace
                        .scope()
                        .organizationId()
                        .equals(requiredMember.scope().organizationId())
                || requiredWorkspace.scope().teamId()
                        .filter(requiredMember.scope().teamId()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "conversation.workspaceId",
                    "must reference the active Team Workspace of the owner member");
        }
        return requiredWorkspace;
    }

    private static void requireActivePersonalAgent(
            PersonalAgentInitialization personalAgent,
            PrincipalId ownerPrincipalId,
            ConversationScope scope) {
        Principal agent = personalAgent.agentPrincipal();
        AgentProfile profile = personalAgent.agentProfile();
        if (agent.type() != PrincipalType.PERSONAL_AGENT
                || agent.status() != PrincipalStatus.ACTIVE
                || agent.ownerPrincipalId().filter(ownerPrincipalId::equals).isEmpty()
                || !agent.scope().organizationId().equals(scope.organizationId())
                || agent.scope().teamId().filter(scope.teamId()::equals).isEmpty()
                || !profile.workspaceId().equals(scope.workspaceId())) {
            throw new DomainValidationException(
                    "conversation.personalAgentPrincipalId",
                    "must reference the owner's active default Personal Agent in this Workspace");
        }
    }

    private static PrincipalId requireDistinctAgent(
            PrincipalId ownerPrincipalId, PrincipalId personalAgentPrincipalId) {
        PrincipalId requiredAgent =
                Objects.requireNonNull(personalAgentPrincipalId, "personalAgentPrincipalId");
        if (requiredAgent.equals(ownerPrincipalId)) {
            throw new DomainValidationException(
                    "conversation.personalAgentPrincipalId",
                    "must differ from the owner USER Principal");
        }
        return requiredAgent;
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("conversation.title", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "conversation.title",
                    "must contain at most " + MAX_TITLE_LENGTH + " characters");
        }
        return normalized;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("conversation.version", "must not be negative");
        }
        return value;
    }
}
