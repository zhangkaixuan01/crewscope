package io.crewscope.domain.workitem;

/** Supported resource nodes linked into the WorkGraph from a WorkItem. */
public enum WorkItemResourceType {
    TASK,
    CONVERSATION,
    REPOSITORY,
    BRANCH,
    COMMIT,
    PULL_REQUEST,
    ARTIFACT,
    EXTERNAL_URL
}
