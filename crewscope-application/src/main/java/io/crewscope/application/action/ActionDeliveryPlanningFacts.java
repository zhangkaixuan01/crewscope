package io.crewscope.application.action;

import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.RepositoryBranchReference;
import java.util.Objects;

/** Current server-owned authority graph and managed delivery branch used for Action planning. */
public record ActionDeliveryPlanningFacts(
        ActionAuthorityFacts authority,
        RepositoryBranchReference deliveryBranch,
        String providerResourceKey) {

    public ActionDeliveryPlanningFacts {
        authority = Objects.requireNonNull(authority, "authority");
        deliveryBranch = Objects.requireNonNull(deliveryBranch, "deliveryBranch");
        if (providerResourceKey == null || providerResourceKey.isBlank()) {
            throw new IllegalArgumentException("Provider resource key must not be blank");
        }
        providerResourceKey = providerResourceKey.strip();
    }
}
