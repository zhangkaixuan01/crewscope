package io.crewscope.application.coding;

import io.crewscope.domain.coding.RepositoryKey;
import java.util.Objects;
import java.util.Optional;

/** Path-free description of one repository visible in the Worker-managed catalog. */
public record RepositoryCatalogEntry(
        RepositoryKey repositoryKey,
        RepositoryCatalogAvailability availability,
        Optional<String> suggestedDefaultBranch) {

    public RepositoryCatalogEntry {
        Objects.requireNonNull(repositoryKey, "repositoryKey");
        Objects.requireNonNull(availability, "availability");
        suggestedDefaultBranch = Objects.requireNonNull(
                suggestedDefaultBranch, "suggestedDefaultBranch");
    }
}
