package io.crewscope.domain.team;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Safe invitation collision that does not disclose token digests or target email addresses. */
public final class TeamInvitationConflictException extends DomainException {

    public TeamInvitationConflictException() {
        super(new DomainError(
                DomainErrorCode.TEAM_INVITATION_CONFLICT,
                "Team invitation conflicts with an existing invitation",
                Map.of()));
    }
}
