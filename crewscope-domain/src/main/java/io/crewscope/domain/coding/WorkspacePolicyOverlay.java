package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Runtime Coding restrictions that can only remove paths, commands or budget. */
public final class WorkspacePolicyOverlay {

    private final WorkspacePolicyOverlayId id;
    private final WorkspacePolicyReference workspacePolicy;
    private final long version;
    private final Optional<TaskFactHash> parentOverlayHash;
    private final AllowedPathSet allowedPaths;
    private final CommandCatalog commandCatalog;
    private final SandboxResourceBudget sandboxBudget;
    private final WorkspaceOperationBudget operationBudget;
    private final TaskFactHash overlayHash;
    private final AuditMetadata audit;

    private WorkspacePolicyOverlay(
            WorkspacePolicyOverlayId id,
            WorkspacePolicy policy,
            long version,
            Optional<TaskFactHash> parentOverlayHash,
            AllowedPathSet allowedPaths,
            CommandCatalog commandCatalog,
            SandboxResourceBudget sandboxBudget,
            WorkspaceOperationBudget operationBudget,
            Optional<TaskFactHash> expectedHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        WorkspacePolicy base = Objects.requireNonNull(policy, "policy");
        this.workspacePolicy = base.reference();
        if (version < 1) {
            throw new DomainValidationException("workspacePolicyOverlay.version", "must be positive");
        }
        this.version = version;
        this.parentOverlayHash = requireParent(version, parentOverlayHash);
        this.allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog");
        this.sandboxBudget = Objects.requireNonNull(sandboxBudget, "sandboxBudget");
        this.operationBudget = Objects.requireNonNull(operationBudget, "operationBudget");
        requireNoBroaderThanBase(base);
        if (version == 1
                && (!this.allowedPaths.equals(base.allowedPaths())
                        || !this.commandCatalog.equals(base.commandCatalog())
                        || !this.sandboxBudget.equals(base.sandboxBudget())
                        || !this.operationBudget.equals(base.operationBudget()))) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay", "version one must inherit the complete base policy");
        }
        this.audit = Objects.requireNonNull(audit, "audit");
        this.overlayHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.overlayHash)) {
                throw new DomainValidationException(
                        "workspacePolicyOverlay.overlayHash",
                        "must match the canonical overlay facts");
            }
        });
    }

    public static WorkspacePolicyOverlay unrestricted(
            WorkspacePolicyOverlayId id,
            WorkspacePolicy policy,
            Principal actor,
            UtcTimestamp createdAt) {
        WorkspacePolicy base = Objects.requireNonNull(policy, "policy");
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, base.scope(), "workspacePolicyOverlay.createdByPrincipalId");
        return new WorkspacePolicyOverlay(
                id,
                base,
                1,
                Optional.empty(),
                base.allowedPaths(),
                base.commandCatalog(),
                base.sandboxBudget(),
                base.operationBudget(),
                Optional.empty(),
                AuditMetadata.createdBy(actorId, createdAt));
    }

    /** Creates the direct successor after proving every effective dimension is non-expanding. */
    public WorkspacePolicyOverlay tighten(
            WorkspacePolicy policy,
            AllowedPathSet nextAllowedPaths,
            CommandCatalog nextCommandCatalog,
            SandboxResourceBudget nextSandboxBudget,
            WorkspaceOperationBudget nextOperationBudget,
            Principal actor,
            UtcTimestamp occurredAt) {
        WorkspacePolicy base = requireBasePolicy(policy);
        AllowedPathSet paths = Objects.requireNonNull(nextAllowedPaths, "nextAllowedPaths");
        CommandCatalog commands = Objects.requireNonNull(nextCommandCatalog, "nextCommandCatalog");
        SandboxResourceBudget sandbox = Objects.requireNonNull(
                nextSandboxBudget, "nextSandboxBudget");
        WorkspaceOperationBudget operations = Objects.requireNonNull(
                nextOperationBudget, "nextOperationBudget");
        if (!allowedPaths.containsAll(paths)
                || !commandCatalog.containsUnchanged(commands)
                || !sandbox.isNoBroaderThan(sandboxBudget)
                || !operations.isNoBroaderThan(operationBudget)) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay", "runtime overlay can only reduce existing authority");
        }
        if (paths.equals(allowedPaths)
                && commands.equals(commandCatalog)
                && sandbox.equals(sandboxBudget)
                && operations.equals(operationBudget)) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay", "successor must tighten at least one dimension");
        }
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, base.scope(), "workspacePolicyOverlay.updatedByPrincipalId");
        return new WorkspacePolicyOverlay(
                id,
                base,
                version + 1,
                Optional.of(overlayHash),
                paths,
                commands,
                sandbox,
                operations,
                Optional.empty(),
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Reconstitutes and verifies both the base-policy reference and canonical overlay hash. */
    public static WorkspacePolicyOverlay reconstitute(
            WorkspacePolicyOverlayId id,
            WorkspacePolicy policy,
            long version,
            Optional<TaskFactHash> parentOverlayHash,
            AllowedPathSet allowedPaths,
            CommandCatalog commandCatalog,
            SandboxResourceBudget sandboxBudget,
            WorkspaceOperationBudget operationBudget,
            TaskFactHash overlayHash,
            AuditMetadata audit) {
        return new WorkspacePolicyOverlay(
                id,
                policy,
                version,
                parentOverlayHash,
                allowedPaths,
                commandCatalog,
                sandboxBudget,
                operationBudget,
                Optional.of(Objects.requireNonNull(overlayHash, "overlayHash")),
                audit);
    }

    private WorkspacePolicy requireBasePolicy(WorkspacePolicy policy) {
        WorkspacePolicy required = Objects.requireNonNull(policy, "policy");
        if (!workspacePolicy.equals(required.reference())) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay.workspacePolicyId",
                    "must identify the exact immutable base policy");
        }
        return required;
    }

    private void requireNoBroaderThanBase(WorkspacePolicy policy) {
        if (!policy.allowedPaths().containsAll(allowedPaths)
                || !policy.commandCatalog().containsUnchanged(commandCatalog)
                || !sandboxBudget.isNoBroaderThan(policy.sandboxBudget())
                || !operationBudget.isNoBroaderThan(policy.operationBudget())) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay", "must not exceed the immutable WorkspacePolicy");
        }
    }

    private static Optional<TaskFactHash> requireParent(
            long version, Optional<TaskFactHash> parentHash) {
        Optional<TaskFactHash> required = Objects.requireNonNull(
                parentHash, "parentOverlayHash");
        if ((version == 1) == required.isPresent()) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay.parentOverlayHash",
                    version == 1 ? "must be empty for version one" : "is required after version one");
        }
        return required;
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("workspace-policy-overlay-v1");
        BuildProfile.append(canonical, id.toString());
        BuildProfile.append(canonical, workspacePolicy.id().toString());
        BuildProfile.append(canonical, workspacePolicy.policyHash().toString());
        BuildProfile.append(canonical, Long.toString(version));
        BuildProfile.append(canonical, parentOverlayHash.map(Object::toString).orElse("-"));
        allowedPaths.values().forEach(value -> BuildProfile.append(canonical, value));
        commandCatalog.commands().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    BuildProfile.append(canonical, entry.getKey().name());
                    BuildCommand command = entry.getValue();
                    BuildProfile.append(canonical, command.toolKey());
                    command.argv().forEach(value -> BuildProfile.append(canonical, value));
                    BuildProfile.append(canonical, command.workingDirectory());
                    BuildProfile.append(
                            canonical, Integer.toString(command.defaultTimeoutSeconds()));
                    BuildProfile.append(
                            canonical, Integer.toString(command.maxTimeoutSeconds()));
                    BuildProfile.appendSelectorPolicy(canonical, command.selectorPolicy());
                });
        BuildProfile.append(canonical, sandboxBudget.networkMode().name());
        BuildProfile.append(canonical, Integer.toString(sandboxBudget.cpuCount()));
        BuildProfile.append(canonical, Integer.toString(sandboxBudget.memoryMiB()));
        BuildProfile.append(canonical, Integer.toString(sandboxBudget.pids()));
        BuildProfile.append(
                canonical, Integer.toString(sandboxBudget.maxCommandDurationSeconds()));
        BuildProfile.append(canonical, Long.toString(sandboxBudget.maxCommandOutputBytes()));
        BuildProfile.append(canonical, Boolean.toString(sandboxBudget.readOnlyRootFilesystem()));
        BuildProfile.append(canonical, Integer.toString(operationBudget.maxCommandCalls()));
        BuildProfile.append(canonical, Integer.toString(operationBudget.maxChangedFiles()));
        BuildProfile.append(canonical, Long.toString(operationBudget.maxSingleFileBytes()));
        BuildProfile.append(canonical, Integer.toString(operationBudget.maxWriteOperations()));
        BuildProfile.append(canonical, Long.toString(operationBudget.maxWrittenBytes()));
        BuildProfile.append(canonical, Long.toString(operationBudget.maxDiffBytes()));
        BuildProfile.append(canonical, Integer.toString(operationBudget.maxTestRepairRounds()));
        BuildProfile.append(canonical, audit.createdBy().map(Object::toString).orElse("-"));
        BuildProfile.append(canonical, audit.createdAt().toString());
        BuildProfile.append(canonical, audit.updatedBy().map(Object::toString).orElse("-"));
        BuildProfile.append(canonical, audit.updatedAt().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    public WorkspacePolicyOverlayReference reference() {
        return new WorkspacePolicyOverlayReference(id, version, overlayHash);
    }

    public WorkspacePolicyOverlayId id() { return id; }

    public WorkspacePolicyReference workspacePolicy() { return workspacePolicy; }

    public long version() { return version; }

    public Optional<TaskFactHash> parentOverlayHash() { return parentOverlayHash; }

    public AllowedPathSet allowedPaths() { return allowedPaths; }

    public CommandCatalog commandCatalog() { return commandCatalog; }

    public SandboxResourceBudget sandboxBudget() { return sandboxBudget; }

    public WorkspaceOperationBudget operationBudget() { return operationBudget; }

    public TaskFactHash overlayHash() { return overlayHash; }

    public AuditMetadata audit() { return audit; }
}
