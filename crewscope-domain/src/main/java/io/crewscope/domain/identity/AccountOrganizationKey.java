package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Unique coordinate allowing one Account binding per Organization. */
public record AccountOrganizationKey(UserAccountId accountId, OrganizationId organizationId) {

    public AccountOrganizationKey {
        accountId = Objects.requireNonNull(accountId, "accountId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
    }
}
