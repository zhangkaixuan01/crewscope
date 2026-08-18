package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Objects;

/** Typed argv command definition; no shell source string is accepted at this boundary. */
public record BuildCommand(
        String toolKey,
        List<String> argv,
        String workingDirectory,
        int defaultTimeoutSeconds,
        int maxTimeoutSeconds,
        CommandSelectorPolicy selectorPolicy) {

    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_ARGUMENT_LENGTH = 1_024;
    private static final int MAX_TIMEOUT_SECONDS = 3_600;
    private static final String TOOL_KEY_REGEX = "[a-z][A-Za-z0-9._-]{0,127}";

    public BuildCommand {
        if (toolKey == null || !toolKey.matches(TOOL_KEY_REGEX)) {
            throw new DomainValidationException(
                    "buildCommand.toolKey", "must match " + TOOL_KEY_REGEX);
        }
        List<String> supplied = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (supplied.isEmpty() || supplied.size() > MAX_ARGUMENTS) {
            throw new DomainValidationException("buildCommand.argv", "must contain 1 to 64 arguments");
        }
        supplied.forEach(BuildCommand::requireArgument);
        argv = supplied;
        workingDirectory = requireWorkingDirectory(workingDirectory);
        selectorPolicy = Objects.requireNonNull(selectorPolicy, "selectorPolicy");
        if (defaultTimeoutSeconds < 1
                || maxTimeoutSeconds < defaultTimeoutSeconds
                || maxTimeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new DomainValidationException(
                    "buildCommand.timeoutSeconds",
                    "default and maximum timeout must form a positive bounded range");
        }
    }

    public BuildCommand(
            String toolKey,
            List<String> argv,
            String workingDirectory,
            int defaultTimeoutSeconds,
            int maxTimeoutSeconds) {
        this(
                toolKey,
                argv,
                workingDirectory,
                defaultTimeoutSeconds,
                maxTimeoutSeconds,
                CommandSelectorPolicy.none());
    }

    void validateFor(BuildTool tool) {
        if (!Objects.requireNonNull(tool, "tool").accepts(argv.get(0))) {
            throw new DomainValidationException(
                    "buildCommand.argv[0]", "must use the BuildProfile executable");
        }
        if (tool == BuildTool.PROJECT_SCRIPT) {
            // Reuse the canonical path rules after removing the required executable prefix.
            new CodingTargetAllowedPaths(List.of(argv.get(0).substring(2)));
        }
    }

    private static void requireArgument(String argument) {
        if (argument == null
                || argument.isEmpty()
                || argument.length() > MAX_ARGUMENT_LENGTH
                || argument.indexOf('\0') >= 0
                || argument.indexOf('\r') >= 0
                || argument.indexOf('\n') >= 0) {
            throw new DomainValidationException(
                    "buildCommand.argv", "arguments must be non-empty, bounded single-line values");
        }
    }

    private static String requireWorkingDirectory(String value) {
        CodingTargetAllowedPaths paths = new CodingTargetAllowedPaths(List.of(value));
        if (paths.values().size() != 1 || !paths.values().get(0).equals(value)) {
            throw new DomainValidationException(
                    "buildCommand.workingDirectory", "must be one canonical repository-relative path");
        }
        return value;
    }
}
