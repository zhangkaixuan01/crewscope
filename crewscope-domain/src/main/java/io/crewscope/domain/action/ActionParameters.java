package io.crewscope.domain.action;

/** Closed, typed parameter contract for every externally observable action kind. */
public sealed interface ActionParameters
        permits PushBranchActionParameters,
                CreateDraftPullRequestActionParameters,
                NotifyCollaborationActionParameters {

    ActionKind kind();

    void appendCanonical(ActionCanonicalEncoder encoder);
}
