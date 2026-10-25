package io.crewscope.application.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;

/**
 * Seeds the first-run experience of one complete Team: a starter Conversation whose opening
 * Agent message greets the owner and names the first move. New accounts otherwise land on
 * three empty lists with no reason to stay; the starter turns the first minute into the
 * product's own onboarding pitch (M13i first-run guidance).
 */
@FunctionalInterface
public interface StarterConversationInitializer {

    void initialize(
            Team team,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent);
}
