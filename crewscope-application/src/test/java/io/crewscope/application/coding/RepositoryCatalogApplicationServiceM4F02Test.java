package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryCatalogApplicationServiceM4F02Test {

    private static final OrganizationId ORGANIZATION =
            OrganizationId.from("00000000-0000-0000-0000-000000000001");
    private static final TeamId TEAM =
            TeamId.from("00000000-0000-0000-0000-000000000201");
    private static final WorkProjectId PROJECT =
            WorkProjectId.from("00000000-0000-0000-0000-000000000401");

    @Test
    void authorizesAsAdministratorAndReturnsSortedPathFreeEntries() {
        RepositoryBindingAccessPolicy accessPolicy = mock(RepositoryBindingAccessPolicy.class);
        RepositoryCatalogPort port = () -> List.of(
                new RepositoryCatalogEntry(
                        RepositoryKey.parse("zeta-service"),
                        RepositoryCatalogAvailability.UNAVAILABLE,
                        Optional.empty()),
                new RepositoryCatalogEntry(
                        RepositoryKey.parse("crewscope-java"),
                        RepositoryCatalogAvailability.AVAILABLE,
                        Optional.of("main")));
        TimeProvider timeProvider = mock(TimeProvider.class);
        UtcTimestamp now = UtcTimestamp.from(Instant.parse("2026-08-20T01:00:00Z"));
        when(timeProvider.now()).thenReturn(now);
        TeamAccessContext context = mock(TeamAccessContext.class);
        RepositoryCatalogApplicationService service =
                new RepositoryCatalogApplicationService(accessPolicy, port, timeProvider);

        List<RepositoryCatalogEntry> entries = service.list(context, ORGANIZATION, TEAM, PROJECT);

        assertEquals(
                List.of("crewscope-java", "zeta-service"),
                entries.stream().map(entry -> entry.repositoryKey().value()).toList());
        verify(accessPolicy).requireAdministrator(context, ORGANIZATION, TEAM, PROJECT, now);
    }

    @Test
    void preservesTheStableUnavailableBoundaryFromServerOnlyProfiles() {
        RepositoryBindingAccessPolicy accessPolicy = mock(RepositoryBindingAccessPolicy.class);
        TimeProvider timeProvider = mock(TimeProvider.class);
        when(timeProvider.now()).thenReturn(
                UtcTimestamp.from(Instant.parse("2026-08-20T01:00:00Z")));
        RepositoryCatalogApplicationService service = new RepositoryCatalogApplicationService(
                accessPolicy,
                () -> {
                    throw new RepositoryCatalogUnavailableException();
                },
                timeProvider);

        RepositoryCatalogUnavailableException failure = assertThrows(
                RepositoryCatalogUnavailableException.class,
                () -> service.list(
                        mock(TeamAccessContext.class), ORGANIZATION, TEAM, PROJECT));
        assertEquals("Repository Catalog is unavailable on this server", failure.getMessage());
    }
}
