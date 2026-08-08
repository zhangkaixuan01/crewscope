package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Map;
import java.util.Objects;

/** Reports a WorkProject key that is already reserved inside the same Team. */
public final class WorkProjectKeyConflictException extends DomainException {

  public WorkProjectKeyConflictException(TeamId teamId, WorkProjectKey key) {
    super(
        new DomainError(
            DomainErrorCode.WORK_PROJECT_KEY_CONFLICT,
            "WorkProject key is already used in this Team",
            Map.of(
                "teamId", Objects.requireNonNull(teamId, "teamId").toString(),
                "projectKey", Objects.requireNonNull(key, "key").value())));
  }
}
