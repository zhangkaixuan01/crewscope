package io.crewscope.application.retrieval;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
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
 * The dynamic Team Skill references (A03b) arrive already resolved as INJECTED
 * SKILL_INSTRUCTION triples — the resolver proved them loadable, so the manifest only
 * seals them as load evidence next to the built-in skill instruction.
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
        KnowledgeRetrievalQuery.RepositoryTarget repositoryTarget,
        List<ManifestSourceRef> dynamicSkillInstructions) {

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
        dynamicSkillInstructions = dynamicSkillInstructions == null
                ? List.of()
                : List.copyOf(dynamicSkillInstructions);
        // The same guard the built-in reference passes at service construction: every
        // dynamic skill enters the manifest as proven, INJECTED instruction evidence.
        for (ManifestSourceRef reference : dynamicSkillInstructions) {
            if (reference.type() != ManifestSourceType.SKILL_INSTRUCTION
                    || reference.stage() != ManifestSourceStage.INJECTED) {
                throw new DomainValidationException(
                        "promptInjectionRequest.dynamicSkillInstructions",
                        "must be INJECTED SKILL_INSTRUCTION references");
            }
        }
    }

    /** Pre-A03b shape without dynamic skills; identical to the previous constructor. */
    public PromptInjectionRequest(
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
        this(organizationId, teamId, workspaceId, projectId, executionId, attempt,
                agentProfileId, injectionActor, objective, acceptanceCriteria,
                repositoryTarget, List.of());
    }
}
