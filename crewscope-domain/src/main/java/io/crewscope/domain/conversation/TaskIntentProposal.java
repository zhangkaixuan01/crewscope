package io.crewscope.domain.conversation;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProject;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Validated work target and responsibility proposal carried by a TaskIntent revision. */
public record TaskIntentProposal(
        WorkItemScope targetScope,
        String objective,
        List<String> acceptanceCriteria,
        TaskIntentResponsibility owner,
        Optional<TaskIntentResponsibility> executor,
        Optional<TaskIntentResponsibility> gateReviewer) {

    public static final int MAX_OBJECTIVE_LENGTH = 5_000;
    public static final int MAX_ACCEPTANCE_CRITERIA = 20;
    public static final int MAX_ACCEPTANCE_CRITERION_LENGTH = 1_000;

    public TaskIntentProposal {
        targetScope = Objects.requireNonNull(targetScope, "targetScope");
        objective = requireText(objective, "taskIntent.objective", MAX_OBJECTIVE_LENGTH);
        acceptanceCriteria = requireAcceptanceCriteria(acceptanceCriteria);
        owner = requireRole(owner, ResponsibilityRole.OWNER, "taskIntent.owner");
        executor = requireOptionalRole(executor, ResponsibilityRole.EXECUTOR, "taskIntent.executor");
        gateReviewer = requireOptionalRole(
                gateReviewer, ResponsibilityRole.REVIEWER, "taskIntent.gateReviewer");
        requireDutySeparation(owner, executor, gateReviewer);
    }

    /** Resolves a proposal only from current server-side Project, Principal and membership facts. */
    public static TaskIntentProposal create(
            Conversation conversation,
            WorkProject project,
            String objective,
            List<String> acceptanceCriteria,
            TaskIntentCandidate owner,
            Optional<TaskIntentCandidate> executor,
            Optional<TaskIntentCandidate> gateReviewer) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        WorkProject requiredProject = Objects.requireNonNull(project, "project");
        if (!requiredProject.acceptsWork()
                || !requiredProject
                        .scope()
                        .organizationId()
                        .equals(requiredConversation.scope().organizationId())
                || !requiredProject
                        .scope()
                        .teamId()
                        .equals(requiredConversation.scope().teamId())
                || !requiredProject
                        .scope()
                        .workspaceId()
                        .equals(requiredConversation.scope().workspaceId())) {
            throw new DomainValidationException(
                    "taskIntent.workProjectId",
                    "must reference an active WorkProject in the Conversation scope");
        }
        WorkItemScope targetScope = WorkItemScope.from(requiredProject);
        TaskIntentResponsibility resolvedOwner = TaskIntentResponsibility.resolve(
                ResponsibilityRole.OWNER, owner, targetScope);
        Optional<TaskIntentResponsibility> resolvedExecutor =
                Objects.requireNonNull(executor, "executor")
                        .map(candidate -> TaskIntentResponsibility.resolve(
                                ResponsibilityRole.EXECUTOR, candidate, targetScope));
        Optional<TaskIntentResponsibility> resolvedReviewer =
                Objects.requireNonNull(gateReviewer, "gateReviewer")
                        .map(candidate -> TaskIntentResponsibility.resolve(
                                ResponsibilityRole.REVIEWER, candidate, targetScope));
        return new TaskIntentProposal(
                targetScope,
                objective,
                acceptanceCriteria,
                resolvedOwner,
                resolvedExecutor,
                resolvedReviewer);
    }

    private static TaskIntentResponsibility requireRole(
            TaskIntentResponsibility responsibility,
            ResponsibilityRole expected,
            String field) {
        TaskIntentResponsibility required = Objects.requireNonNull(responsibility, "responsibility");
        if (required.role() != expected) {
            throw new DomainValidationException(field, "must use role " + expected);
        }
        return required;
    }

    private static Optional<TaskIntentResponsibility> requireOptionalRole(
            Optional<TaskIntentResponsibility> responsibility,
            ResponsibilityRole expected,
            String field) {
        Optional<TaskIntentResponsibility> required =
                Objects.requireNonNull(responsibility, "responsibility");
        required.ifPresent(value -> requireRole(value, expected, field));
        return required;
    }

    private static void requireDutySeparation(
            TaskIntentResponsibility owner,
            Optional<TaskIntentResponsibility> executor,
            Optional<TaskIntentResponsibility> reviewer) {
        reviewer.ifPresent(gate -> {
            boolean conflictsWithOwner = sameSubject(gate, owner);
            boolean conflictsWithExecutor =
                    executor.filter(value -> sameSubject(gate, value)).isPresent();
            if (conflictsWithOwner || conflictsWithExecutor) {
                throw new DomainValidationException(
                        "taskIntent.gateReviewer",
                        "must be separated from the proposed Owner and Executor");
            }
        });
    }

    private static boolean sameSubject(
            TaskIntentResponsibility left, TaskIntentResponsibility right) {
        return left.principalId().equals(right.principalId())
                || (left.memberId().isPresent()
                        && left.memberId().equals(right.memberId()));
    }

    private static List<String> requireAcceptanceCriteria(List<String> values) {
        List<String> required = List.copyOf(Objects.requireNonNull(values, "acceptanceCriteria"));
        if (required.isEmpty() || required.size() > MAX_ACCEPTANCE_CRITERIA) {
            throw new DomainValidationException(
                    "taskIntent.acceptanceCriteria",
                    "must contain between 1 and " + MAX_ACCEPTANCE_CRITERIA + " items");
        }
        List<String> normalized = required.stream()
                .map(value -> requireText(
                        value,
                        "taskIntent.acceptanceCriteria",
                        MAX_ACCEPTANCE_CRITERION_LENGTH))
                .toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new DomainValidationException(
                    "taskIntent.acceptanceCriteria", "must not contain duplicate items");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(
                    field, "must contain at most " + maxLength + " characters");
        }
        return normalized;
    }
}
