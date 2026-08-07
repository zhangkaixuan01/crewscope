package io.crewscope.domain.workspace;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Principal and AgentProfile that together form one member's default Personal Agent. */
public record PersonalAgentInitialization(
        Principal agentPrincipal, AgentProfile agentProfile) {

    private static final String PRINCIPAL_ID_NAMESPACE =
            "io.crewscope/default-personal-agent/principal/";
    private static final String PROFILE_ID_NAMESPACE =
            "io.crewscope/default-personal-agent/profile/";
    private static final String DISPLAY_NAME_SUFFIX = " · Personal Agent";

    public PersonalAgentInitialization {
        agentPrincipal = Objects.requireNonNull(agentPrincipal, "agentPrincipal");
        agentProfile = Objects.requireNonNull(agentProfile, "agentProfile");
        validatePair(agentPrincipal, agentProfile);
    }

    /** Creates stable IDs so retries for the same durable TeamMember propose the same Agent pair. */
    public static PersonalAgentInitialization createDefault(
            TeamMember ownerMember,
            Workspace workspace,
            Principal ownerUser,
            UtcTimestamp occurredAt) {
        TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        Principal requiredOwner = Objects.requireNonNull(ownerUser, "ownerUser");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");

        // AgentProfile validates membership, Workspace and USER state before either candidate can
        // cross an application persistence Port.
        Principal personalAgent = Principal.create(
                stablePrincipalId(requiredMember.id()),
                PrincipalScope.team(
                        requiredMember.scope().organizationId(),
                        requiredMember.scope().teamId()),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(requiredOwner.id()),
                displayName(requiredOwner.displayName()),
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                requiredTime);
        AgentProfile profile = AgentProfile.createDefaultPersonal(
                stableProfileId(requiredMember.id()),
                requiredWorkspace,
                requiredMember,
                requiredOwner,
                personalAgent,
                requiredTime);
        return new PersonalAgentInitialization(personalAgent, profile);
    }

    /** Fails closed when a Repository returns a default Agent for another member or Workspace. */
    public PersonalAgentInitialization requireDefaultFor(
            TeamMember ownerMember, Workspace workspace) {
        TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (agentProfile.ownerMemberId().filter(requiredMember.id()::equals).isEmpty()
                || !agentProfile.workspaceId().equals(requiredWorkspace.id())
                || !agentProfile.scope().equals(requiredWorkspace.scope())
                || agentPrincipal.ownerPrincipalId()
                        .filter(requiredMember.userPrincipalId()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "personalAgentInitialization.agentProfile",
                    "must be the default Personal Agent of the expected member and Workspace");
        }
        return this;
    }

    public static PrincipalId stablePrincipalId(TeamMemberId memberId) {
        return new PrincipalId(stableUuid(PRINCIPAL_ID_NAMESPACE, memberId));
    }

    public static AgentProfileId stableProfileId(TeamMemberId memberId) {
        return new AgentProfileId(stableUuid(PROFILE_ID_NAMESPACE, memberId));
    }

    private static UUID stableUuid(String namespace, TeamMemberId memberId) {
        String source = namespace + Objects.requireNonNull(memberId, "memberId");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static String displayName(String ownerDisplayName) {
        String normalized = Objects.requireNonNull(ownerDisplayName, "ownerDisplayName").strip();
        int ownerLimit = Principal.MAX_DISPLAY_NAME_LENGTH - DISPLAY_NAME_SUFFIX.length();
        String ownerPart = normalized.length() <= ownerLimit
                ? normalized
                : normalized.substring(0, ownerLimit).stripTrailing();
        return ownerPart + DISPLAY_NAME_SUFFIX;
    }

    private static void validatePair(Principal agent, AgentProfile profile) {
        if (agent.type() != PrincipalType.PERSONAL_AGENT
                || agent.status() != PrincipalStatus.ACTIVE
                || agent.ownerPrincipalId().isEmpty()
                || agent.visibility() != PrincipalVisibility.PRIVATE
                || !profile.agentPrincipalId().equals(agent.id())
                || !profile.scope().organizationId().equals(agent.scope().organizationId())
                || !profile.scope().teamId().equals(agent.scope().teamId())
                || !profile.isActiveDefaultPersonal()) {
            throw new DomainValidationException(
                    "personalAgentInitialization.agentProfile",
                    "must match one active private default Personal Agent Principal");
        }
    }
}
