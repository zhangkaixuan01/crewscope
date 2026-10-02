package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Optional;

/**
 * Persistence port for sealed injection manifests (I02b's delivery). (executionId,
 * attempt) is the idempotency key: appending a second manifest for an already-stored
 * pair is a conflict, never a duplicate row.
 */
public interface InjectionManifestRepository {

    InjectionManifest append(InjectionManifest manifest);

    Optional<InjectionManifest> findByAttempt(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId executionId,
            int attempt);
}
