package io.crewscope.application.retrieval;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;

/**
 * Everything one prompt assembly attempt needs (M10-I02b): the execution coordinates
 * the manifest is keyed by, the injection actor — the task creator, under whose one
 * identity both retrieval authorization and memory ownership run — the task text that
 * seeds the retrieval query, and the repository target the coding workspace pinned.
 */
public record PromptInjectionRequest(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        WorkProjectId projectId,
        TaskExecutionId executionId,
        int attempt,
        AgentProfileId agentProfileId,
        Principal injectionActor,
        String objective,
        List<String> acceptanceCriteria,
        KnowledgeRetrievalQuery.RepositoryTarget repositoryTarget) {

    public PromptInjectionRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(executionId, "executionId");
        if (attempt < 1) {
            throw new DomainValidationException(
                    "promptInjectionRequest.attempt", "must be positive");
        }
        Objects.requireNonNull(agentProfileId, "agentProfileId");
        Objects.requireNonNull(injectionActor, "injectionActor");
        objective = Objects.requireNonNull(objective, "objective").strip();
        if (objective.isEmpty()) {
            throw new DomainValidationException(
                    "promptInjectionRequest.objective", "must not be blank");
        }
        Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria");
        for (String criterion : acceptanceCriteria) {
            if (criterion == null || criterion.isBlank()) {
                throw new DomainValidationException(
                        "promptInjectionRequest.acceptanceCriteria",
                        "must not contain blank criteria");
            }
        }
        acceptanceCriteria = acceptanceCriteria.stream().map(String::strip).toList();
        Objects.requireNonNull(repositoryTarget, "repositoryTarget");
    }
}
