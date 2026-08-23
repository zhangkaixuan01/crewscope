package io.crewscope.domain.action;

/** Closed, typed parameter contract for every M5 action kind. */
public sealed interface ActionParameters
        permits PushBranchActionParameters, CreateDraftPullRequestActionParameters {

    ActionKind kind();

    void appendCanonical(ActionCanonicalEncoder encoder);
}
