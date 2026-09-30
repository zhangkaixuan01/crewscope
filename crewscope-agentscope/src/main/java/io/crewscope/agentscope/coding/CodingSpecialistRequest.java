package io.crewscope.agentscope.coding;

import io.agentscope.core.tool.Toolkit;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import java.util.Objects;
import java.util.Optional;

/** One prepared Coding invocation; the caller retains ownership of its guarded Tool sessions. */
public record CodingSpecialistRequest(
        TaskAgentRuntimeSession runtimeSession,
        Toolkit toolkit,
        String instruction,
        Optional<ResolvedAgentExecutionConfiguration> pinnedExecution) {

    public CodingSpecialistRequest {
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        toolkit = Objects.requireNonNull(toolkit, "toolkit");
        instruction = requireInstruction(instruction);
        pinnedExecution = Objects.requireNonNull(pinnedExecution, "pinnedExecution");
    }

    public CodingSpecialistRequest(
            TaskAgentRuntimeSession runtimeSession, Toolkit toolkit, String instruction) {
        this(runtimeSession, toolkit, instruction, Optional.empty());
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
