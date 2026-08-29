package io.crewscope.application.team;

import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import java.util.Objects;

/** Trusted Account Session and Organization Principal coordinates for onboarding. */
public record OnboardingAccountContext(
        UserAccountId accountId,
        SecurityVersion sessionSecurityVersion,
        TeamAccessContext teamAccess) {

    public OnboardingAccountContext {
        accountId = Objects.requireNonNull(accountId, "accountId");
        sessionSecurityVersion =
                Objects.requireNonNull(sessionSecurityVersion, "sessionSecurityVersion");
        teamAccess = Objects.requireNonNull(teamAccess, "teamAccess");
    }
}
