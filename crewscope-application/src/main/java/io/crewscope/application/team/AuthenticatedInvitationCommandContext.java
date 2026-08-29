package io.crewscope.application.team;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.UserAccount;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Current Account chain plus retry and tracing coordinates for accepting an invitation. */
public record AuthenticatedInvitationCommandContext(
        UserAccount account,
        AccountOrganizationBinding binding,
        TeamAccessContext access,
        IdempotencyKey idempotencyKey,
        UUID correlationId,
        Optional<UUID> causationId) {

    public AuthenticatedInvitationCommandContext {
        account = Objects.requireNonNull(account, "account");
        binding = Objects.requireNonNull(binding, "binding");
        access = Objects.requireNonNull(access, "access");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId");
        if (!account.canAuthenticate()
                || !binding.isUsable()
                || !binding.accountId().equals(account.id())
                || !binding.isCompatibleWith(access.actor())) {
            throw new IllegalArgumentException(
                    "invitation command context must contain one active Account identity chain");
        }
    }
}
