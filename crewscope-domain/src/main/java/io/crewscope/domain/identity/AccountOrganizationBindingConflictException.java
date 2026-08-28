package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Safe collision for either binding coordinate without disclosing Account or Principal IDs. */
public final class AccountOrganizationBindingConflictException extends DomainException {

    public AccountOrganizationBindingConflictException() {
        super(new DomainError(
                DomainErrorCode.ACCOUNT_ORGANIZATION_BINDING_CONFLICT,
                "Account organization binding conflicts with an existing binding",
                Map.of()));
    }
}
