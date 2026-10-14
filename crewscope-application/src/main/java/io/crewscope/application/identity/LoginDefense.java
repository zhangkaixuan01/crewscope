package io.crewscope.application.identity;

import io.crewscope.domain.identity.UserAccountId;
import java.util.concurrent.CompletionStage;

/** Redis-backed authentication defense port consumed before and after password verification. */
public interface LoginDefense {

    /** Atomically consumes identifier and controlled-network resources before Account lookup. */
    CompletionStage<LoginResourceAdmission> admit(LoginDefenseRequest request);

    /**
     * Consumes only the controlled-network window for flows that carry no user-submitted
     * identifier (M12-I01: the OIDC authorization start has no identifier dimension).
     */
    CompletionStage<LoginResourceAdmission> admitNetwork(
            AuthenticationFlow flow, ControlledNetworkResource controlledNetwork);

    CompletionStage<AccountLoginDefenseState> observeAccount(UserAccountId accountId);

    CompletionStage<AccountLoginDefenseState> recordFailure(UserAccountId accountId);

    /** Clears only the known-Account failure state; resource windows remain consumed. */
    CompletionStage<AccountLoginDefenseState> recordSuccess(UserAccountId accountId);
}
