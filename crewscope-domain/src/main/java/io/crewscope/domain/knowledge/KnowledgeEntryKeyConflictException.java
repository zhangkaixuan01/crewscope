package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.team.TeamScope;
import java.util.Map;
import java.util.Objects;

/** Reports a Knowledge entry key already present inside the same Team scope. */
public final class KnowledgeEntryKeyConflictException extends DomainException {

    public KnowledgeEntryKeyConflictException(TeamScope scope, KnowledgeEntryKey entryKey) {
        super(new DomainError(
                DomainErrorCode.KNOWLEDGE_ENTRY_KEY_CONFLICT,
                "Knowledge entry key is already present in this Team",
                Map.of(
                        "organizationId",
                        Objects.requireNonNull(scope, "scope").organizationId().toString(),
                        "teamId",
                        scope.teamId().toString(),
                        "entryKey",
                        Objects.requireNonNull(entryKey, "entryKey").value())));
    }
}
