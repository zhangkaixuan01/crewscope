package io.crewscope.agentscope.coding;

import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/** One invocation window prepared from the current guarded Workspace and repair history. */
public record CodingSpecialistRound(int number, Toolkit toolkit, String instruction) {

    public CodingSpecialistRound {
        if (number < 1) {
            throw new IllegalArgumentException("number must be positive");
        }
        toolkit = Objects.requireNonNull(toolkit, "toolkit");
        instruction = Objects.requireNonNull(instruction, "instruction").strip();
        if (instruction.isEmpty() || instruction.length() > 30_000) {
            throw new IllegalArgumentException("instruction must be non-blank and bounded");
        }
    }
}
