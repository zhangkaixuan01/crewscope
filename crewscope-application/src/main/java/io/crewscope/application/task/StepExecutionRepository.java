package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for serial durable Steps; no Step Lease Port exists in the MVP. */
public interface StepExecutionRepository {
    StepExecution create(StepExecution step);

    StepExecution update(StepExecution step);

    Optional<StepExecution> findById(
            OrganizationId organizationId, StepExecutionId stepExecutionId);

    /** Returns Steps ordered by Plan sequence. */
    List<StepExecution> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId);
}
