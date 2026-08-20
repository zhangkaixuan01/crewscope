package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryCatalogAvailability;
import io.crewscope.application.coding.RepositoryCatalogEntry;
import io.crewscope.domain.coding.RepositoryKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies path-free Catalog classification at the managed filesystem boundary. */
class ManagedRepositoryCatalogAdapterM4F02Test {

    @TempDir Path temporaryDirectory;

    @Test
    void exportsOnlyValidDirectRepositoryKeysAndKeepsResolverFailuresPathFree()
            throws Exception {
        Path managedRoot = Files.createDirectory(temporaryDirectory.resolve("managed"));
        Path availablePath = Files.createDirectory(managedRoot.resolve("crewscope-java.git"));
        Files.createDirectory(managedRoot.resolve("broken-service.git"));
        Files.createDirectory(managedRoot.resolve("Invalid Key.git"));
        Files.createFile(managedRoot.resolve("notes.txt"));
        Path symbolicLink = managedRoot.resolve("linked-service.git");
        Files.createSymbolicLink(symbolicLink, availablePath);

        ManagedRepositoryResolver resolver = mock(ManagedRepositoryResolver.class);
        RepositoryKey availableKey = RepositoryKey.parse("crewscope-java");
        RepositoryKey brokenKey = RepositoryKey.parse("broken-service");
        when(resolver.resolve(availableKey))
                .thenReturn(new ManagedRepository(availableKey, availablePath));
        when(resolver.resolve(brokenKey))
                .thenThrow(new RepositoryPreflightException(
                        RepositoryPreflightError.OWNER_MISMATCH,
                        "Owner mismatch at " + temporaryDirectory));

        List<RepositoryCatalogEntry> entries =
                new ManagedRepositoryCatalogAdapter(managedRoot, resolver).list();

        assertThat(entries)
                .extracting(entry -> entry.repositoryKey().value())
                .containsExactlyInAnyOrder("crewscope-java", "broken-service", "linked-service");
        assertThat(entries)
                .filteredOn(entry -> entry.repositoryKey().equals(availableKey))
                .extracting(RepositoryCatalogEntry::availability)
                .containsExactly(RepositoryCatalogAvailability.AVAILABLE);
        assertThat(entries)
                .filteredOn(entry -> !entry.repositoryKey().equals(availableKey))
                .extracting(RepositoryCatalogEntry::availability)
                .containsOnly(RepositoryCatalogAvailability.UNAVAILABLE);
        assertThat(entries.toString()).doesNotContain(temporaryDirectory.toString());
        verify(resolver).resolve(availableKey);
        verify(resolver).resolve(brokenKey);
        verify(resolver, never()).resolve(RepositoryKey.parse("linked-service"));
    }
}
