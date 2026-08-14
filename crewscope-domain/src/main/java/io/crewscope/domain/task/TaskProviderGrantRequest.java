package io.crewscope.domain.task;

import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import java.util.Objects;

/** Trusted issuance input requesting a strict subset of one current ProviderBinding. */
public record TaskProviderGrantRequest(
        ProviderBinding binding, ProviderAccessScope requestedAccess) {

    public TaskProviderGrantRequest {
        binding = Objects.requireNonNull(binding, "binding");
        requestedAccess = Objects.requireNonNull(requestedAccess, "requestedAccess");
    }

    @Override
    public String toString() {
        return "TaskProviderGrantRequest[bindingId=" + binding.id()
                + ", requestedAccess=[REDACTED_SCOPE]]";
    }
}
