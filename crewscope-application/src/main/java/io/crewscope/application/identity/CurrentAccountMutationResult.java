package io.crewscope.application.identity;

import io.crewscope.domain.identity.UserAccount;
import java.util.Objects;
import java.util.UUID;

/** Secret-free committed account mutation proof. */
public record CurrentAccountMutationResult(UserAccount account, UUID domainEventId) {

    public CurrentAccountMutationResult {
        account = Objects.requireNonNull(account, "account");
        domainEventId = Objects.requireNonNull(domainEventId, "domainEventId");
    }
}
