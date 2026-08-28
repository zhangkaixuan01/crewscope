package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Safe conflict shared by username and email unique-key failures without identifying the key. */
public final class AccountIdentifierConflictException extends DomainException {

    public AccountIdentifierConflictException() {
        super(new DomainError(
                DomainErrorCode.ACCOUNT_IDENTIFIER_CONFLICT,
                "Account identifier conflicts with an existing account",
                Map.of()));
    }
}
