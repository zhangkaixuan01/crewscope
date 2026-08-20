package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.RepositoryCatalogAvailability;
import io.crewscope.application.coding.RepositoryCatalogEntry;
import io.crewscope.application.coding.RepositoryCatalogPort;
import io.crewscope.domain.coding.RepositoryKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Enumerates direct managed-root children and exports only stable repository keys. */
public final class ManagedRepositoryCatalogAdapter implements RepositoryCatalogPort {

    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final Path managedRoot;
    private final ManagedRepositoryResolver resolver;

    public ManagedRepositoryCatalogAdapter(
            Path managedRoot, ManagedRepositoryResolver resolver) {
        this.managedRoot = Objects.requireNonNull(managedRoot, "managedRoot")
                .toAbsolutePath()
                .normalize();
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public List<RepositoryCatalogEntry> list() {
        List<RepositoryCatalogEntry> entries = new ArrayList<>();
        try (var children = Files.list(managedRoot)) {
            children.forEach(candidate -> candidate(candidate).ifPresent(entries::add));
        } catch (IOException | SecurityException failure) {
            // Preserve the infrastructure cause for trusted logs while the application exception
            // keeps host paths and filesystem details out of the HTTP error envelope.
            throw new io.crewscope.application.coding.RepositoryCatalogUnavailableException(failure);
        }
        return List.copyOf(entries);
    }

    private Optional<RepositoryCatalogEntry> candidate(Path candidate) {
        String filename = candidate.getFileName().toString();
        if (!filename.endsWith(".git") || filename.length() <= 4) {
            return Optional.empty();
        }
        String keyValue = filename.substring(0, filename.length() - 4);
        RepositoryKey key;
        try {
            key = RepositoryKey.parse(keyValue);
        } catch (RuntimeException invalidKey) {
            return Optional.empty();
        }
        RepositoryCatalogAvailability availability = RepositoryCatalogAvailability.UNAVAILABLE;
        if (Files.isDirectory(candidate, NO_FOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
            try {
                resolver.resolve(key);
                availability = RepositoryCatalogAvailability.AVAILABLE;
            } catch (RepositoryPreflightException ignored) {
                // Catalog consumers receive a stable classification; resolver details stay inside Worker logs.
            }
        }
        return Optional.of(new RepositoryCatalogEntry(key, availability, Optional.empty()));
    }
}
