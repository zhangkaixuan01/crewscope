package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Versioned, reviewable proposal that can atomically create one WorkItem after confirmation. */
public final class TaskIntent {

    public static final int SCHEMA_VERSION = 1;

    private final TaskIntentId id;
    private final ConversationScope scope;
    private final ConversationId conversationId;
    private final PrincipalId proposedByPrincipalId;
    private final int proposalRevision;
    private final TaskIntentProposal proposal;
    private final TaskIntentStatus status;
    private final Optional<TaskIntentDecision> decision;
    private final long version;
    private final AuditMetadata audit;

    private TaskIntent(
            TaskIntentId id,
            ConversationScope scope,
            ConversationId conversationId,
            PrincipalId proposedByPrincipalId,
            int proposalRevision,
            TaskIntentProposal proposal,
            TaskIntentStatus status,
            Optional<TaskIntentDecision> decision,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.proposedByPrincipalId = Objects.requireNonNull(
                proposedByPrincipalId, "proposedByPrincipalId");
        this.proposalRevision = requireProposalRevision(proposalRevision);
        this.proposal = requireProposalScope(scope, proposal);
        this.status = Objects.requireNonNull(status, "status");
        this.decision = requireDecision(this.status, decision);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        requireAudit(this.proposedByPrincipalId, this.decision, this.audit);
    }

    /** Creates revision one from an explicit active Agent participant of an active Conversation. */
    public static TaskIntent draft(
            TaskIntentId id,
            Conversation conversation,
            ConversationParticipant proposerParticipant,
            Principal proposer,
            TaskIntentProposal proposal,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        PrincipalId proposerId = requireAgentProposer(
                requiredConversation, proposerParticipant, proposer);
        return new TaskIntent(
                id,
                requiredConversation.scope(),
                requiredConversation.id(),
                proposerId,
                1,
                proposal,
                TaskIntentStatus.DRAFT,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(proposerId, occurredAt));
    }

    /** Reconstitutes a persisted TaskIntent and revalidates proposal, decision and audit shape. */
    public static TaskIntent reconstitute(
            TaskIntentId id,
            ConversationScope scope,
            ConversationId conversationId,
            PrincipalId proposedByPrincipalId,
            int proposalRevision,
            TaskIntentProposal proposal,
            TaskIntentStatus status,
            Optional<TaskIntentDecision> decision,
            long version,
            AuditMetadata audit) {
        return new TaskIntent(
                id,
                scope,
                conversationId,
                proposedByPrincipalId,
                proposalRevision,
                proposal,
                status,
                decision,
                version,
                audit);
    }

    /** Replaces editable content, increments proposal revision and requires readiness again. */
    public TaskIntent revise(
            TaskIntentProposal replacement,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureEditable(TaskIntentStatus.DRAFT);
        TaskIntentProposal requiredReplacement = requireProposalScope(scope, replacement);
        if (proposal.equals(requiredReplacement)) {
            throw new DomainValidationException(
                    "taskIntent.proposal", "must differ from the current proposal");
        }
        PrincipalId actorId = requireActor(actor);
        return copy(
                proposalRevision + 1,
                requiredReplacement,
                TaskIntentStatus.DRAFT,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Marks the current proposal revision as complete enough for owner confirmation. */
    public TaskIntent markReady(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != TaskIntentStatus.DRAFT) {
            throw new InvalidStateTransitionException(
                    "TaskIntent", id, status, TaskIntentStatus.READY);
        }
        PrincipalId actorId = requireActor(actor);
        return copy(
                proposalRevision,
                proposal,
                TaskIntentStatus.READY,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Confirms a READY proposal only through its proposed human Owner. */
    public TaskIntent confirm(
            long expectedVersion,
            TaskIntentProposal currentValidatedProposal,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != TaskIntentStatus.READY) {
            throw new InvalidStateTransitionException(
                    "TaskIntent", id, status, TaskIntentStatus.CONFIRMED);
        }
        TaskIntentProposal requiredCurrent =
                requireProposalScope(scope, currentValidatedProposal);
        if (!proposal.equals(requiredCurrent)) {
            throw new DomainValidationException(
                    "taskIntent.proposal",
                    "must match the current server-validated responsibility facts");
        }
        PrincipalId actorId = requireActor(actor);
        if (!proposal.owner().principalId().equals(actorId)) {
            throw new DomainValidationException(
                    "taskIntent.confirmedByPrincipalId",
                    "must match the proposed human Owner");
        }
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        TaskIntentDecision confirmed = TaskIntentDecision.confirmed(actorId, requiredTime);
        return copy(
                proposalRevision,
                proposal,
                TaskIntentStatus.CONFIRMED,
                Optional.of(confirmed),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    /** Permanently rejects an editable proposal with a user-visible reason. */
    public TaskIntent reject(
            long expectedVersion,
            Principal actor,
            String reason,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureEditable(TaskIntentStatus.REJECTED);
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        TaskIntentDecision rejected = TaskIntentDecision.rejected(actorId, reason, requiredTime);
        return copy(
                proposalRevision,
                proposal,
                TaskIntentStatus.REJECTED,
                Optional.of(rejected),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    /** Permanently expires an editable proposal when its current facts are no longer usable. */
    public TaskIntent expire(
            long expectedVersion,
            Principal actor,
            String reason,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureEditable(TaskIntentStatus.EXPIRED);
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        TaskIntentDecision expired = TaskIntentDecision.expired(actorId, reason, requiredTime);
        return copy(
                proposalRevision,
                proposal,
                TaskIntentStatus.EXPIRED,
                Optional.of(expired),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    public TaskIntentId id() {
        return id;
    }

    public ConversationScope scope() {
        return scope;
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public PrincipalId proposedByPrincipalId() {
        return proposedByPrincipalId;
    }

    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    public int proposalRevision() {
        return proposalRevision;
    }

    public TaskIntentProposal proposal() {
        return proposal;
    }

    public TaskIntentStatus status() {
        return status;
    }

    public Optional<TaskIntentDecision> decision() {
        return decision;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private PrincipalId requireActor(Principal actor) {
        return ConversationActorPolicy.requireActiveInScope(
                actor, scope, "taskIntent.updatedByPrincipalId");
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "TaskIntent", id, expectedVersion, version);
        }
    }

    private void ensureEditable(TaskIntentStatus target) {
        if (status != TaskIntentStatus.DRAFT && status != TaskIntentStatus.READY) {
            throw new InvalidStateTransitionException("TaskIntent", id, status, target);
        }
    }

    private TaskIntent copy(
            int targetProposalRevision,
            TaskIntentProposal targetProposal,
            TaskIntentStatus targetStatus,
            Optional<TaskIntentDecision> targetDecision,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new TaskIntent(
                id,
                scope,
                conversationId,
                proposedByPrincipalId,
                targetProposalRevision,
                targetProposal,
                targetStatus,
                targetDecision,
                targetVersion,
                targetAudit);
    }

    private static Conversation requireActiveConversation(Conversation conversation) {
        Conversation required = Objects.requireNonNull(conversation, "conversation");
        if (!required.acceptsMessages()) {
            throw new DomainValidationException(
                    "taskIntent.conversationId", "must reference an active Conversation");
        }
        return required;
    }

    private static PrincipalId requireAgentProposer(
            Conversation conversation,
            ConversationParticipant participant,
            Principal proposer) {
        ConversationParticipant requiredParticipant =
                Objects.requireNonNull(participant, "proposerParticipant");
        Principal requiredProposer = Objects.requireNonNull(proposer, "proposer");
        PrincipalId proposerId = ConversationActorPolicy.requireActiveInScope(
                requiredProposer,
                conversation.scope(),
                "taskIntent.proposedByPrincipalId");
        if (!requiredProposer.type().isAgent()
                || !requiredParticipant.isActive()
                || requiredParticipant.role() != ConversationParticipantRole.AGENT
                || !requiredParticipant.principalId().equals(proposerId)
                || !requiredParticipant.conversationId().equals(conversation.id())
                || !requiredParticipant.scope().equals(conversation.scope())) {
            throw new DomainValidationException(
                    "taskIntent.proposedByPrincipalId",
                    "must match an active Agent participant of the Conversation");
        }
        return proposerId;
    }

    private static TaskIntentProposal requireProposalScope(
            ConversationScope scope, TaskIntentProposal proposal) {
        TaskIntentProposal required = Objects.requireNonNull(proposal, "proposal");
        if (!required.targetScope().organizationId().equals(scope.organizationId())
                || !required.targetScope().teamId().equals(scope.teamId())
                || !required.targetScope().workspaceId().equals(scope.workspaceId())) {
            throw new DomainValidationException(
                    "taskIntent.workProjectId",
                    "must belong to the TaskIntent Conversation scope");
        }
        return required;
    }

    private static Optional<TaskIntentDecision> requireDecision(
            TaskIntentStatus status, Optional<TaskIntentDecision> decision) {
        Optional<TaskIntentDecision> required = Objects.requireNonNull(decision, "decision");
        if (status.isTerminal()
                && required.filter(value -> value.status() == status).isEmpty()) {
            throw new DomainValidationException(
                    "taskIntent.decision", "must match the terminal TaskIntent status");
        }
        if (!status.isTerminal() && required.isPresent()) {
            throw new DomainValidationException(
                    "taskIntent.decision", "must be empty for an editable TaskIntent");
        }
        return required;
    }

    private static void requireAudit(
            PrincipalId proposedByPrincipalId,
            Optional<TaskIntentDecision> decision,
            AuditMetadata audit) {
        if (audit.createdBy().filter(proposedByPrincipalId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "taskIntent.audit.createdBy",
                    "must preserve the proposing Agent Principal");
        }
        decision.ifPresent(value -> {
            if (audit.updatedBy().filter(value.decidedByPrincipalId()::equals).isEmpty()
                    || audit.updatedAt().compareTo(value.decidedAt()) < 0) {
                throw new DomainValidationException(
                        "taskIntent.audit",
                        "must preserve the terminal decision actor and timestamp");
            }
        });
    }

    private static int requireProposalRevision(int value) {
        if (value < 1) {
            throw new DomainValidationException(
                    "taskIntent.proposalRevision", "must be positive");
        }
        return value;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("taskIntent.version", "must not be negative");
        }
        return value;
    }
}
