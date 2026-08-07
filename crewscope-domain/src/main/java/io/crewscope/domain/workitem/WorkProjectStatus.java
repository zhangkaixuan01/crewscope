package io.crewscope.domain.workitem;

/** WorkProject lifecycle; archived projects no longer accept new work. */
public enum WorkProjectStatus {
    ACTIVE,
    ARCHIVED
}
