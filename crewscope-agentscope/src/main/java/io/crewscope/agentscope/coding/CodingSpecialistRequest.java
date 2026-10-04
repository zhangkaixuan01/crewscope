package io.crewscope.agentscope.coding;

import io.agentscope.core.tool.Toolkit;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One prepared Coding invocation; the caller retains ownership of its guarded Tool sessions.
 * The dynamic Team Skills (A03b) arrive already resolved for exactly this attempt —
 * empty means the invocation runs on the built-in repository alone (the pre-A03b shape).
 */
public record CodingSpecialistRequest(
        TaskAgentRuntimeSession runtimeSession,
        Toolkit toolkit,
        String instruction,
        Optional<ResolvedAgentExecutionConfiguration> pinnedExecution,
        List<TeamSkillExecutionSource.PublishedTeamSkill> dynamicTeamSkills) {

    public CodingSpecialistRequest {
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        toolkit = Objects.requireNonNull(toolkit, "toolkit");
        instruction = requireInstruction(instruction);
        pinnedExecution = Objects.requireNonNull(pinnedExecution, "pinnedExecution");
        dynamicTeamSkills = dynamicTeamSkills == null
                ? List.of()
                : List.copyOf(dynamicTeamSkills);
    }

    public CodingSpecialistRequest(
            TaskAgentRuntimeSession runtimeSession,
            Toolkit toolkit,
            String instruction,
            Optional<ResolvedAgentExecutionConfiguration> pinnedExecution) {
        this(runtimeSession, toolkit, instruction, pinnedExecution, List.of());
    }

    public CodingSpecialistRequest(
            TaskAgentRuntimeSession runtimeSession, Toolkit toolkit, String instruction) {
        this(runtimeSession, toolkit, instruction, Optional.empty(), List.of());
    }

    private static String requireInstruction(String value) {
        String required = Objects.requireNonNull(value, "instruction").strip();
        boolean invalidControl = required.chars().anyMatch(character ->
                Character.isISOControl(character) && character != '\n' && character != '\t');
        if (required.isEmpty() || required.length() > 30_000 || invalidControl) {
            throw new IllegalArgumentException("instruction contains invalid text");
        }
        return required;
    }
}
