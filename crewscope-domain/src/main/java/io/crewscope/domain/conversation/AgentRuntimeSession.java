package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Objects;
import java.util.Optional;

/** Durable trusted binding between a Personal Agent Conversation and AgentScope state. */
public final class AgentRuntimeSession {

    private final AgentRuntimeSessionId id;
    private final ConversationScope scope;
    private final ConversationId conversationId;
    private final TeamMemberId ownerMemberId;
    private final PrincipalId ownerPrincipalId;
    private final PrincipalId personalAgentPrincipalId;
    private final AgentProfileId agentProfileId;
    private final long agentProfileVersion;
    private final Optional<AgentRuntimeConfigurationPin> configurationPin;
    private final AgentScopeSessionKey agentScopeKey;
    private final AgentRuntimeStateReference stateReference;
    private final AgentRuntimeSessionStatus status;
    private final long version;
    private final AuditMetadata audit;

    private AgentRuntimeSession(
            AgentRuntimeSessionId id,
            ConversationScope scope,
            ConversationId conversationId,
            TeamMemberId ownerMemberId,
            PrincipalId ownerPrincipalId,
            PrincipalId personalAgentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Optional<AgentRuntimeConfigurationPin> configurationPin,
            AgentScopeSessionKey agentScopeKey,
            AgentRuntimeStateReference stateReference,
            AgentRuntimeSessionStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        this.ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
        this.personalAgentPrincipalId = requireDistinctAgent(
                ownerPrincipalId, personalAgentPrincipalId);
        requireDerivedIdentity();
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        this.agentProfileVersion = requireVersion(
                agentProfileVersion, "agentRuntimeSession.agentProfileVersion");
        this.configurationPin = Objects.requireNonNull(configurationPin, "configurationPin");
        this.agentScopeKey = requireDerivedAgentScopeKey(agentScopeKey);
        this.stateReference = requireDerivedStateReference(stateReference);
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version, "agentRuntimeSession.version");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (this.audit.createdBy().filter(this.ownerPrincipalId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "agentRuntimeSession.audit.createdBy",
                    "must preserve the owning USER Principal");
        }
    }

    /** Creates a deterministic candidate after validating the complete Personal Agent binding. */
    public static AgentRuntimeSession initializePersonal(
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            UtcTimestamp occurredAt) {
        return initializePersonal(
                conversation,
                workspace,
                ownerMember,
                ownerUser,
                personalAgent,
                Optional.empty(),
                occurredAt);
    }

    /** Creates a new M5 Session with the current configuration pinned when one exists. */
    public static AgentRuntimeSession initializePersonal(
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            Optional<AgentConfigurationVersion> configuration,
            UtcTimestamp occurredAt) {
        BindingFacts facts = BindingFacts.resolve(
                conversation, workspace, ownerMember, ownerUser, personalAgent, true);
        AgentRuntimeSessionId id = AgentRuntimeSessionId.forPersonalConversation(
                facts.conversation().id(),
                facts.ownerMember().id(),
                facts.personalAgent().agentPrincipal().id());
        AgentScopeSessionKey key = AgentScopeSessionKey.forPersonalConversation(
                facts.conversation().scope().organizationId(),
                facts.ownerMember().id(),
                facts.personalAgent().agentPrincipal().id(),
                facts.conversation().id(),
                id);
        return new AgentRuntimeSession(
                id,
                facts.conversation().scope(),
                facts.conversation().id(),
                facts.ownerMember().id(),
                facts.ownerUser().id(),
                facts.personalAgent().agentPrincipal().id(),
                facts.personalAgent().agentProfile().id(),
                facts.personalAgent().agentProfile().version(),
                Optional.of(AgentRuntimeConfigurationPin.from(
                        facts.personalAgent().agentProfile(), configuration)),
                key,
                AgentRuntimeStateReference.forSession(id),
                AgentRuntimeSessionStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(facts.ownerUser().id(), occurredAt));
    }

    /** Reconstitutes a committed binding and rejects forged AgentScope or state references. */
    public static AgentRuntimeSession reconstitute(
            AgentRuntimeSessionId id,
            ConversationScope scope,
            ConversationId conversationId,
            TeamMemberId ownerMemberId,
            PrincipalId ownerPrincipalId,
            PrincipalId personalAgentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            AgentScopeSessionKey agentScopeKey,
            AgentRuntimeStateReference stateReference,
            AgentRuntimeSessionStatus status,
            long version,
            AuditMetadata audit) {
        return new AgentRuntimeSession(
                id,
                scope,
                conversationId,
                ownerMemberId,
                ownerPrincipalId,
                personalAgentPrincipalId,
                agentProfileId,
                agentProfileVersion,
                Optional.empty(),
                agentScopeKey,
                stateReference,
                status,
                version,
                audit);
    }

    /** Reconstitutes the V20 identity/configuration projection, retaining legacy-null rows. */
    public static AgentRuntimeSession reconstitute(
            AgentRuntimeSessionId id,
            ConversationScope scope,
            ConversationId conversationId,
            TeamMemberId ownerMemberId,
            PrincipalId ownerPrincipalId,
            PrincipalId personalAgentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Optional<AgentRuntimeConfigurationPin> configurationPin,
            AgentScopeSessionKey agentScopeKey,
            AgentRuntimeStateReference stateReference,
            AgentRuntimeSessionStatus status,
            long version,
            AuditMetadata audit) {
        return new AgentRuntimeSession(
                id,
                scope,
                conversationId,
                ownerMemberId,
                ownerPrincipalId,
                personalAgentPrincipalId,
                agentProfileId,
                agentProfileVersion,
                configurationPin,
                agentScopeKey,
                stateReference,
                status,
                version,
                audit);
    }

    /** Fails closed when persistence returns a session belonging to other trusted facts. */
    public AgentRuntimeSession requireBinding(
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent) {
        BindingFacts facts = BindingFacts.resolve(
                conversation, workspace, ownerMember, ownerUser, personalAgent, false);
        AgentRuntimeSessionId expectedId = AgentRuntimeSessionId.forPersonalConversation(
                facts.conversation().id(),
                facts.ownerMember().id(),
                facts.personalAgent().agentPrincipal().id());
        if (!id.equals(expectedId)
                || !scope.equals(facts.conversation().scope())
                || !conversationId.equals(facts.conversation().id())
                || !ownerMemberId.equals(facts.ownerMember().id())
                || !ownerPrincipalId.equals(facts.ownerUser().id())
                || !personalAgentPrincipalId.equals(facts.personalAgent().agentPrincipal().id())
                || !agentProfileId.equals(facts.personalAgent().agentProfile().id())
                || agentProfileVersion > facts.personalAgent().agentProfile().version()
                || configurationPin.filter(pin ->
                                pin.ownershipType()
                                                != facts.personalAgent().agentProfile().ownership().type()
                                        || pin.runtimeRole()
                                                != facts.personalAgent().agentProfile().runtimeRole()
                                        || !pin.templateVersion().equals(
                                                facts.personalAgent().agentProfile().templateVersion()))
                        .isPresent()
                || (status == AgentRuntimeSessionStatus.ARCHIVED
                        && facts.conversation().status() != ConversationStatus.ARCHIVED)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.binding",
                    "must match the expected Conversation and Personal Agent facts");
        }
        return this;
    }

    /** Temporarily blocks invocation while preserving the state reference for later recovery. */
    public AgentRuntimeSession disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRuntimeSessionStatus.ACTIVE, AgentRuntimeSessionStatus.DISABLED);
        PrincipalId actorId = requireActor(actor);
        return copy(
                agentProfileVersion,
                configurationPin,
                AgentRuntimeSessionStatus.DISABLED,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Reactivates only after re-resolving the same currently active server-side binding facts. */
    public AgentRuntimeSession activate(
            long expectedVersion,
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRuntimeSessionStatus.DISABLED, AgentRuntimeSessionStatus.ACTIVE);
        requireBinding(conversation, workspace, ownerMember, ownerUser, personalAgent);
        PrincipalId actorId = requireActor(actor);
        return copy(
                personalAgent.agentProfile().version(),
                configurationPin,
                AgentRuntimeSessionStatus.ACTIVE,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Advances the pinned AgentProfile version without replacing this Conversation state slot. */
    public AgentRuntimeSession refreshConfiguration(
            long expectedVersion,
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRuntimeSessionStatus.ACTIVE, AgentRuntimeSessionStatus.ACTIVE);
        requireBinding(conversation, workspace, ownerMember, ownerUser, personalAgent);
        long currentProfileVersion = personalAgent.agentProfile().version();
        if (currentProfileVersion <= agentProfileVersion) {
            throw new DomainValidationException(
                    "agentRuntimeSession.agentProfileVersion",
                    "must advance to a newer active AgentProfile version");
        }
        PrincipalId actorId = requireActor(actor);
        return copy(
                currentProfileVersion,
                configurationPin,
                AgentRuntimeSessionStatus.ACTIVE,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Advances only the pinned append-only configuration at an externally verified safe point. */
    public AgentRuntimeSession refreshConfigurationVersion(
            long expectedVersion,
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            AgentConfigurationVersion configuration,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRuntimeSessionStatus.ACTIVE, AgentRuntimeSessionStatus.ACTIVE);
        requireBinding(conversation, workspace, ownerMember, ownerUser, personalAgent);
        AgentRuntimeConfigurationPin current = configurationPin.orElseGet(() ->
                AgentRuntimeConfigurationPin.from(
                        personalAgent.agentProfile(), Optional.empty()));
        AgentRuntimeConfigurationPin refreshed = current.refresh(
                personalAgent.agentProfile(), configuration);
        PrincipalId actorId = requireActor(actor);
        return copy(
                personalAgent.agentProfile().version(),
                Optional.of(refreshed),
                AgentRuntimeSessionStatus.ACTIVE,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Permanently archives the binding only after the bound Conversation is archived. */
    public AgentRuntimeSession archiveForConversation(
            long expectedVersion,
            Conversation conversation,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        if (!conversationId.equals(requiredConversation.id())
                || !scope.equals(requiredConversation.scope())
                || requiredConversation.status() != ConversationStatus.ARCHIVED) {
            throw new DomainValidationException(
                    "agentRuntimeSession.conversationId",
                    "must reference this session's archived Conversation");
        }
        if (status == AgentRuntimeSessionStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "AgentRuntimeSession", id, status, AgentRuntimeSessionStatus.ARCHIVED);
        }
        PrincipalId actorId = requireActor(actor);
        return copy(
                agentProfileVersion,
                configurationPin,
                AgentRuntimeSessionStatus.ARCHIVED,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean canInvoke() {
        return status == AgentRuntimeSessionStatus.ACTIVE;
    }

    public AgentRuntimeSessionId id() {
        return id;
    }

    public ConversationScope scope() {
        return scope;
    }

    public ConversationId conversationId() {
        return conversationId;
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

    public AgentProfileId agentProfileId() {
        return agentProfileId;
    }

    public long agentProfileVersion() {
        return agentProfileVersion;
    }

    public Optional<AgentRuntimeConfigurationPin> configurationPin() {
        return configurationPin;
    }

    public AgentScopeSessionKey agentScopeKey() {
        return agentScopeKey;
    }

    public AgentRuntimeStateReference stateReference() {
        return stateReference;
    }

    public AgentRuntimeSessionStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private AgentRuntimeSession copy(
            long targetProfileVersion,
            Optional<AgentRuntimeConfigurationPin> targetConfigurationPin,
            AgentRuntimeSessionStatus targetStatus,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new AgentRuntimeSession(
                id,
                scope,
                conversationId,
                ownerMemberId,
                ownerPrincipalId,
                personalAgentPrincipalId,
                agentProfileId,
                targetProfileVersion,
                targetConfigurationPin,
                agentScopeKey,
                stateReference,
                targetStatus,
                targetVersion,
                targetAudit);
    }

    private PrincipalId requireActor(Principal actor) {
        return ConversationActorPolicy.requireActiveInScope(
                actor, scope, "agentRuntimeSession.updatedByPrincipalId");
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "AgentRuntimeSession", id, expectedVersion, version);
        }
    }

    private void ensureStatus(
            AgentRuntimeSessionStatus required, AgentRuntimeSessionStatus target) {
        if (status != required) {
            throw new InvalidStateTransitionException(
                    "AgentRuntimeSession", id, status, target);
        }
    }

    private AgentScopeSessionKey requireDerivedAgentScopeKey(AgentScopeSessionKey value) {
        AgentScopeSessionKey required = Objects.requireNonNull(value, "agentScopeKey");
        AgentScopeSessionKey expected = AgentScopeSessionKey.forPersonalConversation(
                scope.organizationId(),
                ownerMemberId,
                personalAgentPrincipalId,
                conversationId,
                id);
        if (!required.equals(expected)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.agentScopeKey",
                    "must be derived from the bound server-side identities");
        }
        return required;
    }

    private void requireDerivedIdentity() {
        AgentRuntimeSessionId expected = AgentRuntimeSessionId.forPersonalConversation(
                conversationId, ownerMemberId, personalAgentPrincipalId);
        if (!id.equals(expected)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.id",
                    "must be derived from the bound Conversation, member and Personal Agent");
        }
    }

    private AgentRuntimeStateReference requireDerivedStateReference(
            AgentRuntimeStateReference value) {
        AgentRuntimeStateReference required = Objects.requireNonNull(value, "stateReference");
        if (!required.belongsTo(id)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.stateReference",
                    "must be derived from this AgentRuntimeSession");
        }
        return required;
    }

    private static PrincipalId requireDistinctAgent(
            PrincipalId ownerPrincipalId, PrincipalId agentPrincipalId) {
        PrincipalId required = Objects.requireNonNull(
                agentPrincipalId, "personalAgentPrincipalId");
        if (required.equals(ownerPrincipalId)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.personalAgentPrincipalId",
                    "must differ from the owning USER Principal");
        }
        return required;
    }

    private static long requireVersion(long value, String field) {
        if (value < 0) {
            throw new DomainValidationException(field, "must not be negative");
        }
        return value;
    }

    private record BindingFacts(
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent) {

        private static BindingFacts resolve(
                Conversation conversation,
                Workspace workspace,
                TeamMember ownerMember,
                Principal ownerUser,
                PersonalAgentInitialization personalAgent,
                boolean requireActiveConversation) {
            Conversation requiredConversation = Objects.requireNonNull(
                    conversation, "conversation");
            Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
            TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
            Principal requiredOwner = Objects.requireNonNull(ownerUser, "ownerUser");
            PersonalAgentInitialization requiredAgent = Objects.requireNonNull(
                    personalAgent, "personalAgent");
            if ((requireActiveConversation && !requiredConversation.acceptsMessages())
                    || requiredWorkspace.type() != WorkspaceType.TEAM
                    || requiredWorkspace.status() != WorkspaceStatus.ACTIVE
                    || !requiredConversation.scope().workspaceId().equals(requiredWorkspace.id())
                    || !requiredConversation.scope().organizationId()
                            .equals(requiredWorkspace.scope().organizationId())
                    || requiredWorkspace.scope().teamId()
                            .filter(requiredConversation.scope().teamId()::equals)
                            .isEmpty()) {
                throw new DomainValidationException(
                        "agentRuntimeSession.conversationId",
                        "must reference an active Conversation in the active Team Workspace");
            }
            PrincipalId ownerId = ConversationActorPolicy.requireActiveOwner(
                    requiredMember,
                    requiredOwner,
                    requiredConversation.scope(),
                    "agentRuntimeSession.ownerMemberId");
            requiredAgent.requireDefaultFor(requiredMember, requiredWorkspace);
            if (!requiredConversation.ownerMemberId().equals(requiredMember.id())
                    || !requiredConversation.ownerPrincipalId().equals(ownerId)
                    || !requiredConversation.personalAgentPrincipalId()
                            .equals(requiredAgent.agentPrincipal().id())) {
                throw new DomainValidationException(
                        "agentRuntimeSession.binding",
                        "must match the Conversation owner and Personal Agent");
            }
            return new BindingFacts(
                    requiredConversation,
                    requiredWorkspace,
                    requiredMember,
                    requiredOwner,
                    requiredAgent);
        }
    }
}
