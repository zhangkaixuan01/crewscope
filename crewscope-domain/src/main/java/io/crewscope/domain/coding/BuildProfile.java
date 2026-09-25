package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Versioned, immutable and hash-closed build definition resolved before execution. */
public final class BuildProfile {

    private final String key;
    private final long version;
    private final BuildTool buildTool;
    private final int javaRelease;
    private final int schemaVersion;
    private final String nodeVersion;
    private final String packageManager;
    private final String packageManagerVersion;
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
        this(key, version, buildTool, javaRelease, 1, null, null, null, sandboxImage,
                commandCatalog, expectedHash);
    }

    private BuildProfile(
            String key,
            long version,
            BuildTool buildTool,
            int javaRelease,
            int schemaVersion,
            String nodeVersion,
            String packageManager,
            String packageManagerVersion,
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
        if (schemaVersion == 1 && buildTool == BuildTool.NPM) {
            throw new DomainValidationException("buildProfile.buildTool", "Node/npm requires schemaVersion 2");
        }
        if (schemaVersion == 1 && (javaRelease < 17 || javaRelease > 25)) {
            throw new DomainValidationException(
                    "buildProfile.javaRelease", "must be a supported Java release from 17 to 25");
        }
        this.key = key;
        this.version = version;
        this.buildTool = Objects.requireNonNull(buildTool, "buildTool");
        if (schemaVersion != 1 && schemaVersion != 2) {
            throw new DomainValidationException("buildProfile.schemaVersion", "must be 1 or 2");
        }
        if (schemaVersion == 2 && (nodeVersion == null || !nodeVersion.matches("24\\.19\\.0")
                || !"npm".equals(packageManager) || !"11.17.0".equals(packageManagerVersion)
                || javaRelease != 0 || buildTool != BuildTool.NPM)) {
            throw new DomainValidationException("buildProfile.node", "only Node 24.19.0 with npm 11.17.0 is supported");
        }
        this.javaRelease = javaRelease;
        this.schemaVersion = schemaVersion;
        this.nodeVersion = nodeVersion;
        this.packageManager = packageManager;
        this.packageManagerVersion = packageManagerVersion;
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

    /** Defines the first controlled Node/npm profile. Node uses a distinct v2 hash tuple. */
    public static BuildProfile defineNode(
            String key,
            long version,
            SandboxImageReference sandboxImage,
            CommandCatalog commandCatalog) {
        if (!commandCatalog.commands().keySet().containsAll(
                Set.of(CommandKind.PREPARE, CommandKind.COMPILE, CommandKind.TEST))) {
            throw new DomainValidationException(
                    "buildProfile.commandCatalog", "Node profile must declare PREPARE, COMPILE and TEST");
        }
        return new BuildProfile(key, version, BuildTool.NPM, 0, 2,
                "24.19.0", "npm", "11.17.0", sandboxImage, commandCatalog, Optional.empty());
    }

    public BuildProfileReference reference() {
        return new BuildProfileReference(key, version, profileHash);
    }

    private TaskFactHash calculateHash() {
        if (schemaVersion == 2) {
            StringBuilder canonical = new StringBuilder("build-profile-v2\n{\"key\":");
            json(canonical, key); canonical.append(",\"version\":").append(version);
            canonical.append(",\"runtime\":\"NODE\",\"nodeVersion\":"); json(canonical, nodeVersion);
            canonical.append(",\"packageManager\":"); json(canonical, packageManager);
            canonical.append(",\"packageManagerVersion\":"); json(canonical, packageManagerVersion);
            canonical.append(",\"sandboxImage\":"); json(canonical, sandboxImage.value());
            canonical.append(",\"commands\":[");
            boolean first = true;
            for (var entry : commandCatalog.commands().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                if (!first) canonical.append(','); first = false;
                BuildCommand command = entry.getValue();
                canonical.append("{\"kind\":"); json(canonical, entry.getKey().name());
                canonical.append(",\"toolKey\":"); json(canonical, command.toolKey());
                canonical.append(",\"argv\":[");
                for (int i = 0; i < command.argv().size(); i++) { if (i > 0) canonical.append(','); json(canonical, command.argv().get(i)); }
                canonical.append("],\"workingDirectory\":"); json(canonical, command.workingDirectory());
                canonical.append(",\"defaultTimeoutSeconds\":").append(command.defaultTimeoutSeconds());
                canonical.append(",\"maxTimeoutSeconds\":").append(command.maxTimeoutSeconds()).append('}');
            }
            canonical.append("]}");
            return TaskFactHash.sha256(canonical.toString());
        }
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

    private static void json(StringBuilder target, String value) {
        target.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
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

    public int schemaVersion() { return schemaVersion; }

    public Optional<String> nodeVersion() { return Optional.ofNullable(nodeVersion); }

    public Optional<String> packageManager() { return Optional.ofNullable(packageManager); }

    public Optional<String> packageManagerVersion() { return Optional.ofNullable(packageManagerVersion); }

    public SandboxImageReference sandboxImage() { return sandboxImage; }

    public CommandCatalog commandCatalog() { return commandCatalog; }

    public TaskFactHash profileHash() { return profileHash; }
}
