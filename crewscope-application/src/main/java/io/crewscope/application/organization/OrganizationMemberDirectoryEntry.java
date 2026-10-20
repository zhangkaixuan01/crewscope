package io.crewscope.application.organization;

import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.List;
import java.util.Objects;

/**
 * Member-safe account projection for organization governance (M13b-A01); the account's email and
 * credential coordinates are intentionally omitted.
 */
public record OrganizationMemberDirectoryEntry(
        UserAccountId accountId,
        PrincipalId principalId,
        String displayName,
        String username,
        AccountStatus status,
        List<String> orgRoles) {

    public OrganizationMemberDirectoryEntry {
        accountId = Objects.requireNonNull(accountId, "accountId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.strip();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        username = username.strip();
        status = Objects.requireNonNull(status, "status");
        orgRoles = List.copyOf(Objects.requireNonNull(orgRoles, "orgRoles"));
    }
}
