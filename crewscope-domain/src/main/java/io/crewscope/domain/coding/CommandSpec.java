package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact typed argv, policy, image and timeout facts executed by the trusted command runner. */
public final class CommandSpec {

    public static final int MAX_ARGUMENTS = 128;
    public static final int MAX_ARGUMENT_BYTES = 4_096;

    private final WorkspacePolicyReference workspacePolicy;
    private final BuildProfileReference buildProfile;
    private final CommandKind commandKind;
    private final String toolKey;
    private final List<String> argv;
    private final String workingDirectory;
    private final int timeoutSeconds;
    private final SandboxImageReference sandboxImage;
    private final TaskFactHash specHash;

    private CommandSpec(
            WorkspacePolicyReference workspacePolicy,
            BuildProfileReference buildProfile,
            CommandKind commandKind,
            String toolKey,
            List<String> argv,
            String workingDirectory,
            int timeoutSeconds,
            SandboxImageReference sandboxImage,
            Optional<TaskFactHash> expectedHash) {
        this.workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
        this.buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        this.commandKind = Objects.requireNonNull(commandKind, "commandKind");
        this.toolKey = requireToolKey(toolKey);
        this.argv = requireArgv(argv);
        this.workingDirectory = requireWorkingDirectory(workingDirectory);
        if (timeoutSeconds < 1 || timeoutSeconds > 3_600) {
            throw new DomainValidationException(
                    "commandSpec.timeoutSeconds", "must be from 1 to 3600 seconds");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.sandboxImage = Objects.requireNonNull(sandboxImage, "sandboxImage");
        this.specHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.specHash)) {
                throw new DomainValidationException(
                        "commandSpec.specHash", "must match the canonical command facts");
            }
        });
    }

    /** Captures the exact argv produced by the trusted runner from one authorized command slot. */
    public static CommandSpec capture(
            WorkspacePolicy policy,
            BuildProfile profile,
            CommandKind commandKind,
            List<String> executedArgv,
            int timeoutSeconds) {
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "workspacePolicy");
        BuildProfile requiredProfile = Objects.requireNonNull(profile, "buildProfile");
        if (!requiredPolicy.buildProfile().equals(requiredProfile.reference())) {
            throw new DomainValidationException(
                    "commandSpec.buildProfile", "must match the WorkspacePolicy exact profile");
        }
        BuildCommand policyCommand = requiredPolicy.commandCatalog()
                .commands()
                .get(Objects.requireNonNull(commandKind, "commandKind"));
        BuildCommand profileCommand = requiredProfile.commandCatalog().commands().get(commandKind);
        if (policyCommand == null || !policyCommand.equals(profileCommand)) {
            throw new DomainValidationException(
                    "commandSpec.commandKind", "must identify an unchanged authorized command slot");
        }
        List<String> argv = requireArgv(executedArgv);
        if (!startsWith(argv, policyCommand.argv())) {
            throw new DomainValidationException(
                    "commandSpec.argv", "must preserve the authorized fixed argv prefix");
        }
        CommandSelectorPolicy selectors = policyCommand.selectorPolicy();
        int maximumResolvedArguments = policyCommand.argv().size()
                + selectors.maxModuleSelectors()
                + selectors.maxTestSelectors()
                + 4;
        if (argv.size() > maximumResolvedArguments
                || (selectors.maxModuleSelectors() == 0
                        && selectors.maxTestSelectors() == 0
                        && !argv.equals(policyCommand.argv()))) {
            throw new DomainValidationException(
                    "commandSpec.argv", "contains arguments outside the bounded selector envelope");
        }
        if (timeoutSeconds < policyCommand.defaultTimeoutSeconds()
                || timeoutSeconds > policyCommand.maxTimeoutSeconds()
                || timeoutSeconds > requiredPolicy.sandboxBudget().maxCommandDurationSeconds()) {
            throw new DomainValidationException(
                    "commandSpec.timeoutSeconds", "must remain within command and Sandbox limits");
        }
        return new CommandSpec(
                requiredPolicy.reference(),
                requiredProfile.reference(),
                commandKind,
                policyCommand.toolKey(),
                argv,
                policyCommand.workingDirectory(),
                timeoutSeconds,
                requiredProfile.sandboxImage(),
                Optional.empty());
    }

    public static CommandSpec reconstitute(
            WorkspacePolicyReference workspacePolicy,
            BuildProfileReference buildProfile,
            CommandKind commandKind,
            String toolKey,
            List<String> argv,
            String workingDirectory,
            int timeoutSeconds,
            SandboxImageReference sandboxImage,
            TaskFactHash specHash) {
        return new CommandSpec(
                workspacePolicy,
                buildProfile,
                commandKind,
                toolKey,
                argv,
                workingDirectory,
                timeoutSeconds,
                sandboxImage,
                Optional.of(Objects.requireNonNull(specHash, "specHash")));
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("command-spec-v1");
        append(canonical, workspacePolicy.id().toString());
        append(canonical, workspacePolicy.policyHash().toString());
        append(canonical, buildProfile.key());
        append(canonical, Long.toString(buildProfile.version()));
        append(canonical, buildProfile.profileHash().toString());
        append(canonical, commandKind.name());
        append(canonical, toolKey);
        append(canonical, Integer.toString(argv.size()));
        argv.forEach(argument -> append(canonical, argument));
        append(canonical, workingDirectory);
        append(canonical, Integer.toString(timeoutSeconds));
        append(canonical, sandboxImage.value());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static List<String> requireArgv(List<String> values) {
        List<String> required = List.copyOf(Objects.requireNonNull(values, "argv"));
        if (required.isEmpty() || required.size() > MAX_ARGUMENTS) {
            throw new DomainValidationException(
                    "commandSpec.argv", "must contain 1 to 128 arguments");
        }
        required.forEach(argument -> {
            if (argument == null
                    || argument.isEmpty()
                    || argument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > MAX_ARGUMENT_BYTES
                    || argument.indexOf('\0') >= 0
                    || argument.indexOf('\r') >= 0
                    || argument.indexOf('\n') >= 0) {
                throw new DomainValidationException(
                        "commandSpec.argv", "arguments must be bounded single-line UTF-8 values");
            }
        });
        return required;
    }

    private static String requireToolKey(String value) {
        if (value == null || !value.matches("[a-z][A-Za-z0-9._-]{0,127}")) {
            throw new DomainValidationException("commandSpec.toolKey", "has an invalid format");
        }
        return value;
    }

    private static String requireWorkingDirectory(String value) {
        CodingTargetAllowedPaths canonical = CodingTargetAllowedPaths.of(value);
        if (canonical.values().size() != 1 || !canonical.values().get(0).equals(value)) {
            throw new DomainValidationException(
                    "commandSpec.workingDirectory", "must be one canonical repository-relative path");
        }
        return value;
    }

    private static boolean startsWith(List<String> values, List<String> prefix) {
        return values.size() >= prefix.size()
                && values.subList(0, prefix.size()).equals(prefix);
    }

    private static void append(StringBuilder target, String value) {
        BuildProfile.append(target, value);
    }

    public WorkspacePolicyReference workspacePolicy() { return workspacePolicy; }

    public BuildProfileReference buildProfile() { return buildProfile; }

    public CommandKind commandKind() { return commandKind; }

    public String toolKey() { return toolKey; }

    public List<String> argv() { return argv; }

    public String workingDirectory() { return workingDirectory; }

    public int timeoutSeconds() { return timeoutSeconds; }

    public SandboxImageReference sandboxImage() { return sandboxImage; }

    public TaskFactHash specHash() { return specHash; }
}
