package io.crewscope.domain.task;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Pinned, explicit Provider capability and resource subset carried by a Task Token. */
public record TaskProviderAuthorization(
        ProviderBindingId bindingId,
        long bindingVersion,
        Optional<ConnectionGrantId> connectionGrantId,
        Optional<Long> connectionGrantVersion,
        ProviderCapabilities capabilities,
        ProviderResourceScope resources) {

    public TaskProviderAuthorization {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        if (bindingVersion < 0) {
            throw new DomainValidationException(
                    "taskProviderAuthorization.bindingVersion", "must not be negative");
        }
        connectionGrantId = Objects.requireNonNull(connectionGrantId, "connectionGrantId");
        connectionGrantVersion = Objects.requireNonNull(
                connectionGrantVersion, "connectionGrantVersion");
        if (connectionGrantId.isPresent() != connectionGrantVersion.isPresent()
                || connectionGrantVersion.filter(value -> value < 0).isPresent()) {
            throw new DomainValidationException(
                    "taskProviderAuthorization.connectionGrantVersion",
                    "must be non-negative and present exactly with the Grant ID");
        }
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        resources = Objects.requireNonNull(resources, "resources");
        if (resources.unrestricted()) {
            throw new DomainValidationException(
                    "taskProviderAuthorization.resources",
                    "must contain an explicit minimum resource set");
        }
    }

    static TaskProviderAuthorization issue(
            WorkItemScope taskScope,
            PolicySnapshot policy,
            TaskProviderGrantRequest request) {
        TaskProviderGrantRequest requiredRequest = Objects.requireNonNull(request, "request");
        ProviderBinding binding = requiredRequest.binding();
        ProviderAccessScope requested = requiredRequest.requestedAccess();
        if (binding.status() != ProviderRegistrationStatus.ACTIVE
                || !policy.providerBindingIds().contains(binding.id())
                || !matchesTaskScope(taskScope, binding.target())) {
            throw new DomainValidationException(
                    "taskProviderAuthorization.bindingId",
                    "must reference an active Policy-allowed Binding in the Task scope");
        }
        if (requested.resources().unrestricted()
                || binding.effectiveAccess().intersection(requested)
                        .filter(requested::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "taskProviderAuthorization.access",
                    "must be an explicit subset of the Binding effective access");
        }
        return new TaskProviderAuthorization(
                binding.id(), binding.version(), binding.connectionGrantId(),
                binding.connectionGrantVersion(), requested.capabilities(), requested.resources());
    }

    public boolean allows(ProviderCapability capability, String resource) {
        ProviderCapability requiredCapability = Objects.requireNonNull(capability, "capability");
        String requiredResource = requireResource(resource);
        return capabilities.values().contains(requiredCapability)
                && resources.resources().contains(requiredResource);
    }

    private static boolean matchesTaskScope(
            WorkItemScope taskScope, ProviderBindingTarget target) {
        WorkItemScope scope = Objects.requireNonNull(taskScope, "taskScope");
        ProviderBindingTarget required = Objects.requireNonNull(target, "target");
        return scope.organizationId().equals(required.organizationId())
                && scope.teamId().equals(required.teamId())
                && scope.workspaceId().equals(required.workspaceId())
                && (required.type() == ProviderBindingTargetType.WORKSPACE
                        || required.workProjectId().filter(scope.projectId()::equals).isPresent());
    }

    private static String requireResource(String resource) {
        if (resource == null
                || resource.isBlank()
                || resource.strip().length() > ProviderResourceScope.MAX_RESOURCE_LENGTH) {
            throw new DomainValidationException(
                    "taskTokenAccess.resource", "must be a valid explicit resource key");
        }
        return resource.strip();
    }

    @Override
    public String toString() {
        return "TaskProviderAuthorization[bindingId=" + bindingId
                + ", bindingVersion=" + bindingVersion
                + ", capabilities=" + capabilities.values().size()
                + ", resources=" + resources.resources().size() + "]";
    }
}
