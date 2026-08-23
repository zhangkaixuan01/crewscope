package io.crewscope.application.github;

import io.crewscope.domain.action.ActionTargetPrecondition;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ProviderAuthorizationReference;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Confirmed action and exact current-authority coordinates for one GitHub Draft PR. */
public record CreateGitHubDraftPullRequestRequest(
        WorkItemScope scope,
        PreflightGitHubRepositoryRequest repositoryPreflight,
        ProviderAuthorizationReference providerAuthorization,
        ActionTargetPrecondition targetPrecondition,
        CreateDraftPullRequestActionParameters action) {

    private static final ProviderCapabilities GITHUB_DRAFT_PR =
            ProviderCapabilities.of("source.pull-request.create");

    public CreateGitHubDraftPullRequestRequest {
        scope = Objects.requireNonNull(scope, "scope");
        repositoryPreflight = Objects.requireNonNull(repositoryPreflight, "repositoryPreflight");
        providerAuthorization = Objects.requireNonNull(
                providerAuthorization, "providerAuthorization");
        targetPrecondition = Objects.requireNonNull(targetPrecondition, "targetPrecondition");
        action = Objects.requireNonNull(action, "action");
        if (!scope.organizationId().equals(repositoryPreflight.access().organizationId())
                || !repositoryPreflight.access().requestedAccess().capabilities()
                        .includes(GITHUB_DRAFT_PR)
                || providerAuthorization.providerType() != ProviderType.SOURCE_CODE
                || !providerAuthorization.connectionId().equals(action.connectionId())
                || !providerAuthorization.connectionId()
                        .equals(repositoryPreflight.access().connectionId())
                || providerAuthorization.connectionVersion()
                        != repositoryPreflight.access().expectedConnectionVersion()
                || !providerAuthorization.grantId()
                        .equals(repositoryPreflight.access().connectionGrantId())
                || providerAuthorization.grantVersion()
                        != repositoryPreflight.access().expectedGrantVersion()) {
            throw new IllegalArgumentException(
                    "GitHub Draft PR Provider authority must match the confirmed action");
        }
        if (!action.repositoryId().value().equals(repositoryPreflight.externalRepositoryId())
                || !action.base().equals(repositoryPreflight.expectedDefaultBranch())
                || !targetPrecondition.defaultBranch().equals(action.base())
                || !targetPrecondition.deliveryCommit().equals(action.headSha())) {
            throw new IllegalArgumentException(
                    "GitHub Draft PR repository and delivery facts must match the confirmed action");
        }
    }
}
