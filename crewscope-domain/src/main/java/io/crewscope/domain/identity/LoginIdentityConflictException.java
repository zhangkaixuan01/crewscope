package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Safe collision for either identity unique coordinate without disclosing provider subjects. */
public final class LoginIdentityConflictException extends DomainException {

    public LoginIdentityConflictException() {
        super(new DomainError(
                DomainErrorCode.LOGIN_IDENTITY_CONFLICT,
                "Login identity conflicts with an existing binding",
                Map.of()));
    }
}
