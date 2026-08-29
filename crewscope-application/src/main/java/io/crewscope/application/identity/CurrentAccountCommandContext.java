package io.crewscope.application.identity;

import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted current-account coordinates derived from the authenticated browser Session. */
public record CurrentAccountCommandContext(
        UserAccountId accountId,
        OrganizationId organizationId,
        PrincipalId actorPrincipalId,
        UUID correlationId,
        Optional<UUID> causationId) {

    public CurrentAccountCommandContext {
        accountId = Objects.requireNonNull(accountId, "accountId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        actorPrincipalId = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId");
    }
}
