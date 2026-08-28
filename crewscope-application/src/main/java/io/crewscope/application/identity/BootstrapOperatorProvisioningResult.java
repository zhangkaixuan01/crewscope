package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Stable, secret-free outcome of one idempotent deployment Operator provisioning pass. */
public record BootstrapOperatorProvisioningResult(
        UserAccountId accountId,
        LoginIdentityId loginIdentityId,
        AccountOrganizationBindingId bindingId,
        PrincipalId principalId,
        CredentialAction credentialAction) {

    public BootstrapOperatorProvisioningResult {
        accountId = Objects.requireNonNull(accountId, "accountId");
        loginIdentityId = Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        credentialAction = Objects.requireNonNull(credentialAction, "credentialAction");
    }

    public enum CredentialAction {
        CREATED,
        UNCHANGED,
        REHASHED,
        ROTATED
    }
}
