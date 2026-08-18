package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Versioned, immutable and hash-closed build definition resolved before execution. */
public final class BuildProfile {

    private final String key;
    private final long version;
    private final BuildTool buildTool;
    private final int javaRelease;
    private final SandboxImageReference sandboxImage;
    private final CommandCatalog commandCatalog;
    private final TaskFactHash profileHash;

    private BuildProfile(
            String key,
            long version,
            BuildTool buildTool,
            int javaRelease,
            SandboxImageReference sandboxImage,
            CommandCatalog commandCatalog,
            Optional<TaskFactHash> expectedHash) {
        if (key == null || !key.matches(BuildProfileReference.KEY_REGEX)) {
            throw new DomainValidationException(
                    "buildProfile.key", "must match " + BuildProfileReference.KEY_REGEX);
        }
        if (version < 1) {
            throw new DomainValidationException("buildProfile.version", "must be positive");
        }
        if (javaRelease < 17 || javaRelease > 25) {
            throw new DomainValidationException(
                    "buildProfile.javaRelease", "must be a supported Java release from 17 to 25");
        }
        this.key = key;
        this.version = version;
        this.buildTool = Objects.requireNonNull(buildTool, "buildTool");
        this.javaRelease = javaRelease;
        this.sandboxImage = Objects.requireNonNull(sandboxImage, "sandboxImage");
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog");
        if (this.commandCatalog.commands().isEmpty()) {
            throw new DomainValidationException(
                    "buildProfile.commandCatalog", "must contain at least one command");
        }
        this.commandCatalog.validateFor(this.buildTool);
        this.profileHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.profileHash)) {
                throw new DomainValidationException(
                        "buildProfile.profileHash", "must match the canonical BuildProfile facts");
            }
        });
    }

    public static BuildProfile define(
            String key,
            long version,
            BuildTool buildTool,
            int javaRelease,
            SandboxImageReference sandboxImage,
            CommandCatalog commandCatalog) {
        return new BuildProfile(
                key,
                version,
                buildTool,
                javaRelease,
                sandboxImage,
                commandCatalog,
                Optional.empty());
    }

    public static BuildProfile reconstitute(
            String key,
            long version,
            BuildTool buildTool,
            int javaRelease,
            SandboxImageReference sandboxImage,
            CommandCatalog commandCatalog,
            TaskFactHash profileHash) {
        return new BuildProfile(
                key,
                version,
                buildTool,
                javaRelease,
                sandboxImage,
                commandCatalog,
                Optional.of(Objects.requireNonNull(profileHash, "profileHash")));
    }

    public BuildProfileReference reference() {
        return new BuildProfileReference(key, version, profileHash);
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("build-profile-v1");
        append(canonical, key);
        append(canonical, Long.toString(version));
        append(canonical, buildTool.name());
        append(canonical, Integer.toString(javaRelease));
        append(canonical, sandboxImage.value());
        commandCatalog.commands().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(canonical, entry.getKey().name());
                    BuildCommand command = entry.getValue();
                    append(canonical, command.toolKey());
                    append(canonical, command.workingDirectory());
                    append(canonical, Integer.toString(command.defaultTimeoutSeconds()));
                    append(canonical, Integer.toString(command.maxTimeoutSeconds()));
                    append(canonical, Integer.toString(command.argv().size()));
                    command.argv().forEach(argument -> append(canonical, argument));
                    appendSelectorPolicy(canonical, command.selectorPolicy());
                });
        return TaskFactHash.sha256(canonical.toString());
    }

    static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    static void appendSelectorPolicy(StringBuilder target, CommandSelectorPolicy policy) {
        append(target, Integer.toString(policy.maxModuleSelectors()));
        append(target, Integer.toString(policy.maxTestSelectors()));
        append(target, Integer.toString(policy.maxSelectorLength()));
        policy.allowedModules().forEach(module -> append(target, module));
    }

    public String key() { return key; }

    public long version() { return version; }

    public BuildTool buildTool() { return buildTool; }

    public int javaRelease() { return javaRelease; }

    public SandboxImageReference sandboxImage() { return sandboxImage; }

    public CommandCatalog commandCatalog() { return commandCatalog; }

    public TaskFactHash profileHash() { return profileHash; }
}
