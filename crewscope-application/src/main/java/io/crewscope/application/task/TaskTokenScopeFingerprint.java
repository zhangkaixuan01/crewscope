package io.crewscope.application.task;

import io.crewscope.domain.task.TaskProviderAuthorization;
import io.crewscope.domain.task.TaskTokenGrantScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical SHA-256 commitment binding every Tool, Provider and ownership scope coordinate. */
public final class TaskTokenScopeFingerprint {

    private TaskTokenScopeFingerprint() {}

    public static String compute(TaskTokenGrantScope scope) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, scope.workItemScope().organizationId());
        append(canonical, scope.workItemScope().teamId());
        append(canonical, scope.workItemScope().workspaceId());
        append(canonical, scope.workItemScope().projectId());
        append(canonical, scope.taskId());
        append(canonical, scope.taskExecutionId());
        append(canonical, scope.attempt());
        append(canonical, scope.executionLeaseId());
        append(canonical, scope.environment());
        append(canonical, scope.runtimeId());
        append(canonical, scope.workerId());
        append(canonical, scope.claimTokenHash().value());
        append(canonical, scope.fencingToken().value());
        append(canonical, scope.executionPrincipal().principalId());
        append(canonical, scope.executionPrincipal().assignmentId());
        append(canonical, scope.executionPrincipal().assignmentVersion());
        append(canonical, scope.executionPrincipal().responsibilitySnapshotHash().value());
        append(canonical, scope.policySnapshotId());
        append(canonical, scope.policySnapshotHash().value());
        append(canonical, scope.safetyOverlay().id());
        append(canonical, scope.safetyOverlay().version());
        append(canonical, scope.safetyOverlay().overlayHash().value());
        append(canonical, "tools");
        append(canonical, scope.allowedTools().size());
        scope.allowedTools().stream().sorted().forEach(tool -> append(canonical, tool));
        append(canonical, "providers");
        append(canonical, scope.providerAuthorizations().size());
        scope.providerAuthorizations().stream()
                .sorted(java.util.Comparator.comparing(value -> value.bindingId().toString()))
                .forEach(value -> appendProvider(canonical, value));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    private static void appendProvider(StringBuilder value, TaskProviderAuthorization provider) {
        append(value, "provider");
        append(value, provider.bindingId());
        append(value, provider.bindingVersion());
        append(value, provider.connectionGrantId().map(Object::toString).orElse("-"));
        append(value, provider.connectionGrantVersion().map(Object::toString).orElse("-"));
        append(value, "capabilities");
        append(value, provider.capabilities().values().size());
        provider.capabilities().values().stream().map(Object::toString).sorted()
                .forEach(capability -> append(value, capability));
        append(value, "resources");
        append(value, provider.resources().unrestricted());
        append(value, provider.resources().resources().size());
        provider.resources().resources().stream().sorted()
                .forEach(resource -> append(value, resource));
    }

    /** Length-prefixing prevents delimiter characters in Provider resource keys from colliding. */
    private static void append(StringBuilder target, Object value) {
        String text = String.valueOf(value);
        target.append(text.length()).append(':').append(text).append(';');
    }
}
