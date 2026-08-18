package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Map;
import java.util.Objects;

/** Reports a Repository Key already bound inside the same WorkProject scope. */
public final class RepositoryBindingKeyConflictException extends DomainException {

    public RepositoryBindingKeyConflictException(
            WorkProjectId workProjectId, RepositoryKey repositoryKey) {
        super(new DomainError(
                DomainErrorCode.REPOSITORY_BINDING_KEY_CONFLICT,
                "Repository key is already bound in this WorkProject",
                Map.of(
                        "workProjectId",
                        Objects.requireNonNull(workProjectId, "workProjectId").toString(),
                        "repositoryKey",
                        Objects.requireNonNull(repositoryKey, "repositoryKey").value())));
    }
}
