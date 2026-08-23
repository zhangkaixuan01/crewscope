package io.crewscope.infrastructure.github;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubDraftPullRequestErrorCode;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.action.ActionTargetPrecondition;
import io.crewscope.domain.action.ProviderAuthorizationReference;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Shared fail-closed Binding and Repository precondition validation for GitHub writes. */
final class GitHubActionAuthorityValidator {

    private final ProviderBindingRepository providerBindings;
    private final RepositoryBindingRepository repositoryBindings;

    GitHubActionAuthorityValidator(
            ProviderBindingRepository providerBindings,
            RepositoryBindingRepository repositoryBindings) {
        this.providerBindings = Objects.requireNonNull(providerBindings, "providerBindings");
        this.repositoryBindings = Objects.requireNonNull(repositoryBindings, "repositoryBindings");
    }

    void validatePush(
            WorkItemScope scope,
            ProviderAuthorizationReference expected,
            ActionTargetPrecondition target) {
        validate(scope, expected, target, ProviderCapabilities.of("source.write"), true);
    }

    void validateDraftPullRequest(
            WorkItemScope scope,
            ProviderAuthorizationReference expected,
            ActionTargetPrecondition target) {
        validate(
                scope,
                expected,
                target,
                ProviderCapabilities.of("source.write", "pull-request.create"),
                false);
    }

    private void validate(
            WorkItemScope scope,
            ProviderAuthorizationReference expected,
            ActionTargetPrecondition target,
            ProviderCapabilities requiredCapabilities,
            boolean push) {
        WorkItemScope requiredScope = Objects.requireNonNull(scope, "scope");
        ProviderAuthorizationReference requiredExpected = Objects.requireNonNull(
                expected, "expected");
        ActionTargetPrecondition requiredTarget = Objects.requireNonNull(target, "target");
        ProviderBinding binding = providerBindings
                .findById(requiredScope.organizationId(), requiredExpected.bindingId())
                .filter(value -> matches(value, requiredExpected))
                .orElseThrow(() -> stale(push, "GitHub Provider Binding is stale"));
        if (!binding.effectiveAccess().capabilities().includes(requiredCapabilities)) {
            throw stale(push, "GitHub Provider Binding no longer permits the action");
        }
        repositoryBindings
                .findById(
                        requiredScope.organizationId(),
                        requiredScope.teamId(),
                        requiredScope.projectId(),
                        requiredTarget.repositoryBindingId())
                .filter(value -> value.version() == requiredTarget.repositoryBindingVersion())
                .filter(RepositoryBinding::acceptsNewTargets)
                .filter(value -> value.repositoryKey().equals(requiredTarget.repositoryKey()))
                .filter(value -> value.defaultBranch().equals(requiredTarget.defaultBranch()))
                .orElseThrow(() -> stale(push, "GitHub Repository Binding is stale"));
    }

    private static boolean matches(
            ProviderBinding actual, ProviderAuthorizationReference expected) {
        return actual.status() == ProviderRegistrationStatus.ACTIVE
                && actual.version() == expected.bindingVersion()
                && actual.definitionId().equals(expected.definitionId())
                && actual.definitionVersion() == expected.definitionVersion()
                && actual.implementationId().equals(expected.implementationId())
                && actual.implementationVersion() == expected.implementationVersion()
                && actual.providerType() == expected.providerType()
                && actual.executionIdentity().filter(expected.executionIdentity()::equals).isPresent()
                && actual.connectionId().filter(expected.connectionId()::equals).isPresent()
                && actual.connectionVersion()
                        .filter(value -> value == expected.connectionVersion()).isPresent()
                && actual.connectionGrantId().filter(expected.grantId()::equals).isPresent()
                && actual.connectionGrantVersion()
                        .filter(value -> value == expected.grantVersion()).isPresent()
                && expected.matchesEffectiveAccess(actual.effectiveAccess());
    }

    private static RuntimeException stale(boolean push, String summary) {
        return push
                ? new GitHubPushException(GitHubPushErrorCode.AUTHORITY_STALE, summary)
                : new GitHubDraftPullRequestException(
                        GitHubDraftPullRequestErrorCode.AUTHORITY_STALE, summary);
    }
}
