package io.crewscope.domain.task;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** One exact Provider capability and resource requested at a Task Token use boundary. */
public record TaskProviderAccessRequest(
        ProviderBindingId bindingId, ProviderCapability capability, String resource) {

    public TaskProviderAccessRequest {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        capability = Objects.requireNonNull(capability, "capability");
        if (resource == null
                || resource.isBlank()
                || resource.strip().length() > ProviderResourceScope.MAX_RESOURCE_LENGTH) {
            throw new DomainValidationException(
                    "taskTokenAccess.resource", "must be a valid explicit resource key");
        }
        resource = resource.strip();
    }

    @Override
    public String toString() {
        return "TaskProviderAccessRequest[bindingId=" + bindingId
                + ", scope=[REDACTED_SCOPE]]";
    }
}
