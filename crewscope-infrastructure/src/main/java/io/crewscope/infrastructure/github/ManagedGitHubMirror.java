package io.crewscope.infrastructure.github;

import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.nio.file.Path;
import java.util.Objects;

/** Validated platform-owned bare Mirror; its host path remains infrastructure-only. */
record ManagedGitHubMirror(
        OrganizationId organizationId, ExternalRepositoryId repositoryId, Path path) {

    ManagedGitHubMirror {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
