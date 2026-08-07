package io.crewscope.application.team;

import io.crewscope.domain.workspace.Workspace;

/** Persistence Port for product Workspace aggregates. */
public interface WorkspaceRepository {

    Workspace create(Workspace workspace);
}
