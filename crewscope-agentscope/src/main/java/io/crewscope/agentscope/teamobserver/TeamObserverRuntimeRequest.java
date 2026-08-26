package io.crewscope.agentscope.teamobserver;

import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.Objects;

/** Complete trusted runtime graph plus untrusted, bounded member wording for one summary call. */
public record TeamObserverRuntimeRequest(
        AgentTemplateRuntimeDefinition definition,
        TeamObserverRuntimeSession session,
        TeamSummaryRequest summaryRequest,
        String instruction) {

    public static final int MAX_INSTRUCTION_LENGTH = 4_000;

    public TeamObserverRuntimeRequest {
        definition = Objects.requireNonNull(definition, "definition");
        session = Objects.requireNonNull(session, "session");
        summaryRequest = Objects.requireNonNull(summaryRequest, "summaryRequest");
        instruction = requireInstruction(instruction);
        if (!summaryRequest.organizationId().equals(session.organizationId())
                || !summaryRequest.teamId().equals(session.teamId())
                || !summaryRequest.requestingMemberId().equals(session.requestingMemberId())
                || !definition.profile().id().equals(session.observerProfileId())
                || definition.profile().version() != session.observerProfileVersion()
                || !definition.profile().agentPrincipalId().equals(session.observerPrincipalId())) {
            throw new IllegalArgumentException(
                    "Team Observer request, member, Team, Profile and Session must match exactly");
        }
    }

    private static String requireInstruction(String value) {
        String normalized = Objects.requireNonNull(value, "instruction").strip();
        if (normalized.isEmpty()
                || normalized.length() > MAX_INSTRUCTION_LENGTH
                || normalized.codePoints().anyMatch(character ->
                        Character.getType(character) == Character.FORMAT
                                || Character.isISOControl(character)
                                        && character != '\n'
                                        && character != '\t')) {
            throw new IllegalArgumentException("Team Observer instruction must be bounded safe text");
        }
        return normalized;
    }
}
