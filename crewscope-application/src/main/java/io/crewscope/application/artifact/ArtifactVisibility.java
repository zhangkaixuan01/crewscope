package io.crewscope.application.artifact;

/** Scope used by the storage adapter when authorizing metadata and content reads. */
public enum ArtifactVisibility {
    PRIVATE,
    WORKSPACE,
    TEAM,
    ORGANIZATION
}
