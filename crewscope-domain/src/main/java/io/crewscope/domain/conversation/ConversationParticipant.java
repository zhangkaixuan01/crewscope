package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import java.util.Objects;
import java.util.Optional;

/** Durable USER or Agent participation fact in one Conversation. */
public final class ConversationParticipant {

    private final ConversationParticipantId id;
    private final ConversationScope scope;
    private final ConversationId conversationId;
    private final PrincipalId principalId;
    private final Optional<TeamMemberId> teamMemberId;
    private final ConversationParticipantRole role;
    private final ConversationParticipantStatus status;
    private final PrincipalId joinedByPrincipalId;
    private final UtcTimestamp joinedAt;
    private final Optional<UtcTimestamp> leftAt;
    private final long version;
    private final AuditMetadata audit;

    private ConversationParticipant(
            ConversationParticipantId id,
            ConversationScope scope,
            ConversationId conversationId,
            PrincipalId principalId,
            Optional<TeamMemberId> teamMemberId,
            ConversationParticipantRole role,
            ConversationParticipantStatus status,
            PrincipalId joinedByPrincipalId,
            UtcTimestamp joinedAt,
            Optional<UtcTimestamp> leftAt,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        requireStableId(this.id, this.conversationId, this.principalId);
        this.role = Objects.requireNonNull(role, "role");
        this.teamMemberId = requireMemberShape(this.role, teamMemberId);
        this.status = requireStatusForRole(this.role, status);
        this.joinedByPrincipalId = Objects.requireNonNull(
                joinedByPrincipalId, "joinedByPrincipalId");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        this.leftAt = requireLeftAt(this.status, this.joinedAt, leftAt, audit);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        requireCreationAudit(this.joinedByPrincipalId, this.joinedAt, this.audit);
    }

    /** Creates the member who owns a new Personal Agent Conversation. */
    public static ConversationParticipant joinOwner(
            Conversation conversation,
            TeamMember ownerMember,
            Principal ownerUser,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        PrincipalId ownerId = ConversationActorPolicy.requireActiveOwner(
                ownerMember,
                ownerUser,
                requiredConversation.scope(),
                "conversationParticipant.principalId");
        if (!requiredConversation.ownerMemberId().equals(ownerMember.id())
                || !requiredConversation.ownerPrincipalId().equals(ownerId)) {
            throw new DomainValidationException(
                    "conversationParticipant.principalId",
                    "must reference the owner of the Conversation");
        }
        return active(
                requiredConversation,
                ownerId,
                Optional.of(ownerMember.id()),
                ConversationParticipantRole.OWNER,
                ownerId,
                occurredAt);
    }

    /** Creates the owner's default Personal Agent participant. */
    public static ConversationParticipant joinPersonalAgent(
            Conversation conversation,
            PersonalAgentInitialization personalAgent,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        PersonalAgentInitialization requiredAgent =
                Objects.requireNonNull(personalAgent, "personalAgent");
        if (!requiredAgent
                        .agentPrincipal()
                        .id()
                        .equals(requiredConversation.personalAgentPrincipalId())
                || requiredAgent.agentProfile().ownerMemberId()
                        .filter(requiredConversation.ownerMemberId()::equals)
                        .isEmpty()
                || !requiredAgent
                        .agentProfile()
                        .workspaceId()
                        .equals(requiredConversation.scope().workspaceId())) {
            throw new DomainValidationException(
                    "conversationParticipant.principalId",
                    "must reference the Conversation owner's default Personal Agent");
        }
        PrincipalId agentId = ConversationActorPolicy.requireActiveInScope(
                requiredAgent.agentPrincipal(),
                requiredConversation.scope(),
                "conversationParticipant.principalId");
        return active(
                requiredConversation,
                agentId,
                Optional.empty(),
                ConversationParticipantRole.AGENT,
                requiredConversation.ownerPrincipalId(),
                occurredAt);
    }

    /** Adds an active Team member as a regular Conversation participant. */
    public static ConversationParticipant joinMember(
            Conversation conversation,
            TeamMember member,
            Principal user,
            Principal addedBy,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        PrincipalId memberPrincipalId = ConversationActorPolicy.requireActiveOwner(
                member,
                user,
                requiredConversation.scope(),
                "conversationParticipant.principalId");
        if (requiredConversation.ownerPrincipalId().equals(memberPrincipalId)) {
            throw new DomainValidationException(
                    "conversationParticipant.principalId",
                    "the Conversation owner must use the OWNER participation");
        }
        PrincipalId actorId = ConversationActorPolicy.requireActiveInScope(
                addedBy,
                requiredConversation.scope(),
                "conversationParticipant.joinedByPrincipalId");
        return active(
                requiredConversation,
                memberPrincipalId,
                Optional.of(member.id()),
                ConversationParticipantRole.MEMBER,
                actorId,
                occurredAt);
    }

    /** Marks a regular member as having left while retaining its historical read boundary. */
    public ConversationParticipant leave(Principal actor, UtcTimestamp occurredAt) {
        ensureMemberRole("leave");
        if (status == ConversationParticipantStatus.LEFT) {
            throw new DomainValidationException(
                    "conversationParticipant.status", "has already left the Conversation");
        }
        PrincipalId actorId = ConversationActorPolicy.requireActiveInScope(
                actor, scope, "conversationParticipant.updatedByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return copy(
                ConversationParticipantStatus.LEFT,
                Optional.of(requiredTime),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    /** Reactivates a former member after rechecking its current Team membership and USER. */
    public ConversationParticipant reactivateMember(
            Conversation conversation,
            TeamMember member,
            Principal user,
            Principal actor,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        requireSameConversation(requiredConversation);
        ensureMemberRole("reactivate");
        if (status != ConversationParticipantStatus.LEFT) {
            throw new DomainValidationException(
                    "conversationParticipant.status", "must be LEFT to reactivate");
        }
        PrincipalId currentUserId = ConversationActorPolicy.requireActiveOwner(
                member,
                user,
                requiredConversation.scope(),
                "conversationParticipant.principalId");
        if (!principalId.equals(currentUserId)
                || teamMemberId.filter(member.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "conversationParticipant.principalId",
                    "must preserve the original USER and TeamMember identity");
        }
        PrincipalId actorId = ConversationActorPolicy.requireActiveInScope(
                actor, scope, "conversationParticipant.updatedByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return copy(
                ConversationParticipantStatus.ACTIVE,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    /** Reconstitutes committed participation and validates terminal timestamp consistency. */
    public static ConversationParticipant reconstitute(
            ConversationParticipantId id,
            ConversationScope scope,
            ConversationId conversationId,
            PrincipalId principalId,
            Optional<TeamMemberId> teamMemberId,
            ConversationParticipantRole role,
            ConversationParticipantStatus status,
            PrincipalId joinedByPrincipalId,
            UtcTimestamp joinedAt,
            Optional<UtcTimestamp> leftAt,
            long version,
            AuditMetadata audit) {
        return new ConversationParticipant(
                id,
                scope,
                conversationId,
                principalId,
                teamMemberId,
                role,
                status,
                joinedByPrincipalId,
                joinedAt,
                leftAt,
                version,
                audit);
    }

    public boolean isActive() {
        return status == ConversationParticipantStatus.ACTIVE;
    }

    public ConversationParticipantId id() {
        return id;
    }

    public ConversationScope scope() {
        return scope;
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public PrincipalId principalId() {
        return principalId;
    }

    public Optional<TeamMemberId> teamMemberId() {
        return teamMemberId;
    }

    public ConversationParticipantRole role() {
        return role;
    }

    public ConversationParticipantStatus status() {
        return status;
    }

    public PrincipalId joinedByPrincipalId() {
        return joinedByPrincipalId;
    }

    public UtcTimestamp joinedAt() {
        return joinedAt;
    }

    public Optional<UtcTimestamp> leftAt() {
        return leftAt;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static ConversationParticipant active(
            Conversation conversation,
            PrincipalId principalId,
            Optional<TeamMemberId> teamMemberId,
            ConversationParticipantRole role,
            PrincipalId joinedByPrincipalId,
            UtcTimestamp occurredAt) {
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new ConversationParticipant(
                ConversationParticipantId.forPrincipal(conversation.id(), principalId),
                conversation.scope(),
                conversation.id(),
                principalId,
                teamMemberId,
                role,
                ConversationParticipantStatus.ACTIVE,
                joinedByPrincipalId,
                requiredTime,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(joinedByPrincipalId, requiredTime));
    }

    private ConversationParticipant copy(
            ConversationParticipantStatus targetStatus,
            Optional<UtcTimestamp> targetLeftAt,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new ConversationParticipant(
                id,
                scope,
                conversationId,
                principalId,
                teamMemberId,
                role,
                targetStatus,
                joinedByPrincipalId,
                joinedAt,
                targetLeftAt,
                targetVersion,
                targetAudit);
    }

    private void ensureMemberRole(String operation) {
        if (role != ConversationParticipantRole.MEMBER) {
            throw new DomainValidationException(
                    "conversationParticipant.role",
                    "only a MEMBER participant may " + operation);
        }
    }

    private void requireSameConversation(Conversation conversation) {
        if (!conversationId.equals(conversation.id()) || !scope.equals(conversation.scope())) {
            throw new DomainValidationException(
                    "conversationParticipant.conversationId",
                    "must reference this participant's Conversation");
        }
    }

    private static Conversation requireActiveConversation(Conversation conversation) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        if (!requiredConversation.acceptsMessages()) {
            throw new DomainValidationException(
                    "conversationParticipant.conversationId",
                    "must reference an active Conversation");
        }
        return requiredConversation;
    }

    private static Optional<TeamMemberId> requireMemberShape(
            ConversationParticipantRole role, Optional<TeamMemberId> teamMemberId) {
        ConversationParticipantRole requiredRole = Objects.requireNonNull(role, "role");
        Optional<TeamMemberId> requiredMember =
                Objects.requireNonNull(teamMemberId, "teamMemberId");
        if (requiredRole == ConversationParticipantRole.AGENT && requiredMember.isPresent()) {
            throw new DomainValidationException(
                    "conversationParticipant.teamMemberId",
                    "must be empty for an Agent participant");
        }
        if (requiredRole != ConversationParticipantRole.AGENT && requiredMember.isEmpty()) {
            throw new DomainValidationException(
                    "conversationParticipant.teamMemberId",
                    "is required for a USER participant");
        }
        return requiredMember;
    }

    private static ConversationParticipantStatus requireStatusForRole(
            ConversationParticipantRole role, ConversationParticipantStatus status) {
        ConversationParticipantStatus requiredStatus = Objects.requireNonNull(status, "status");
        if (role != ConversationParticipantRole.MEMBER
                && requiredStatus != ConversationParticipantStatus.ACTIVE) {
            throw new DomainValidationException(
                    "conversationParticipant.status",
                    "OWNER and AGENT participants must remain ACTIVE");
        }
        return requiredStatus;
    }

    private static Optional<UtcTimestamp> requireLeftAt(
            ConversationParticipantStatus status,
            UtcTimestamp joinedAt,
            Optional<UtcTimestamp> leftAt,
            AuditMetadata audit) {
        Optional<UtcTimestamp> requiredLeftAt = Objects.requireNonNull(leftAt, "leftAt");
        if (status == ConversationParticipantStatus.ACTIVE && requiredLeftAt.isPresent()) {
            throw new DomainValidationException(
                    "conversationParticipant.leftAt", "must be empty for an active participant");
        }
        if (status == ConversationParticipantStatus.LEFT && requiredLeftAt.isEmpty()) {
            throw new DomainValidationException(
                    "conversationParticipant.leftAt", "is required for a participant who left");
        }
        requiredLeftAt.ifPresent(left -> {
            if (left.compareTo(joinedAt) < 0) {
                throw new DomainValidationException(
                        "conversationParticipant.leftAt", "must not be before joinedAt");
            }
            AuditMetadata requiredAudit = Objects.requireNonNull(audit, "audit");
            if (requiredAudit.updatedAt().compareTo(left) < 0) {
                throw new DomainValidationException(
                        "conversationParticipant.audit.updatedAt",
                        "must not be before leftAt");
            }
        });
        return requiredLeftAt;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "conversationParticipant.version", "must not be negative");
        }
        return value;
    }

    private static void requireStableId(
            ConversationParticipantId id,
            ConversationId conversationId,
            PrincipalId principalId) {
        if (!id.equals(ConversationParticipantId.forPrincipal(conversationId, principalId))) {
            throw new DomainValidationException(
                    "conversationParticipant.id",
                    "must be the stable identity of the Conversation and Principal pair");
        }
    }

    private static void requireCreationAudit(
            PrincipalId joinedByPrincipalId, UtcTimestamp joinedAt, AuditMetadata audit) {
        if (audit.createdBy().filter(joinedByPrincipalId::equals).isEmpty()
                || !audit.createdAt().equals(joinedAt)) {
            throw new DomainValidationException(
                    "conversationParticipant.audit",
                    "must preserve the joining Principal and joinedAt timestamp");
        }
    }
}
