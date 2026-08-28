package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Safe collision for the single local credential allowed per account. */
public final class LocalCredentialConflictException extends DomainException {

    public LocalCredentialConflictException() {
        super(new DomainError(
                DomainErrorCode.LOCAL_CREDENTIAL_CONFLICT,
                "Local credential conflicts with an existing credential",
                Map.of()));
    }
}
