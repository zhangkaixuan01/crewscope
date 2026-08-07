package io.crewscope.domain.workitem;

/** System that owns the authoritative WorkItem fact. */
public enum WorkItemSource {
    CREWSCOPE,
    JIRA,
    ZENTAO,
    TAPD;

    public boolean isNative() {
        return this == CREWSCOPE;
    }
}
