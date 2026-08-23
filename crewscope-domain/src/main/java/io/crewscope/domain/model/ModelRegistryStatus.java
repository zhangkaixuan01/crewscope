package io.crewscope.domain.model;

/** Provider and catalog lifecycle independent from immutable revision content. */
public enum ModelRegistryStatus {
    ACTIVE,
    DISABLED,
    ARCHIVED
}
