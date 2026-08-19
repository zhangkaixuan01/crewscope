package io.crewscope.infrastructure.workspace.repository;

/** Safe lifecycle state; Tombstone reason and storage details stay internal. */
public enum CodingArtifactAvailability {
    ACTIVE,
    EXPIRED,
    TOMBSTONED
}
