package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.github.GitHubAuthenticationType;
import io.crewscope.application.github.GitHubConnectionProfile;
import io.crewscope.application.github.GitHubConnectionProfileStatus;
import io.crewscope.application.github.GitHubHash;
import io.crewscope.application.github.GitHubPermission;
import io.crewscope.application.github.GitHubRateLimitSnapshot;
import io.crewscope.application.github.GitHubRepositoryCatalogEntry;
import io.crewscope.application.github.GitHubRepositoryStatus;
import io.crewscope.application.github.GitHubRepositoryVisibility;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL proof for exact-version GitHub profiles, catalogs and rate-limit facts. */
class JdbcGitHubProviderRepositoryAdapterM5I08IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private JdbcGitHubProviderRepositoryAdapter repository;
    private OrganizationId organizationId;
    private PrincipalId actor;
    private ProviderOwner owner;
    private ConnectionId connectionId;
    private UtcTimestamp now;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        repository = new JdbcGitHubProviderRepositoryAdapter(
                new JdbcTemplate(dataSource), new ObjectMapper());
        organizationId = new OrganizationId(UUID.randomUUID());
        actor = new PrincipalId(UUID.randomUUID());
        TeamId teamId = new TeamId(UUID.randomUUID());
        owner = new ProviderOwner(
                organizationId, ProviderOwnerType.TEAM, teamId.value(),
                Optional.of(teamId), Optional.empty());
        connectionId = new ConnectionId(UUID.randomUUID());
        now = UtcTimestamp.from(Instant.parse("2026-08-23T12:00:00Z"));
        seedAuthority();
    }

    @Test
    void persistsCatalogAtomicallyMarksOnlyMissingFactsStaleAndIsolatesOrganizations() {
        GitHubConnectionProfile versionZero = repository.insertProfile(profile(0));
        GitHubRepositoryCatalogEntry repositoryA = entry("101", "repository-a", true, 0);
        GitHubRepositoryCatalogEntry repositoryB = entry("102", "repository-b", false, 0);
        repository.synchronizeCatalog(
                versionZero, List.of(repositoryA, repositoryB), rate(0, now));

        UtcTimestamp refreshed = UtcTimestamp.from(now.value().plusSeconds(30));
        repository.synchronizeCatalog(
                versionZero,
                List.of(entry("101", "repository-a", true, 0, refreshed)),
                rate(0, refreshed));

        GitHubRepositoryCatalogEntry current = repository.findRepository(
                organizationId, connectionId, "101").orElseThrow();
        GitHubRepositoryCatalogEntry stale = repository.findRepository(
                organizationId, connectionId, "102").orElseThrow();
        assertEquals(1, current.version());
        assertEquals(GitHubRepositoryStatus.DELIVERABLE, current.status());
        assertEquals(1, stale.version());
        assertEquals(GitHubRepositoryStatus.STALE, stale.status());
        assertEquals(List.of("crewscope/repository-a"),
                repository.findDeliverableRepositories(organizationId, connectionId)
                        .stream().map(GitHubRepositoryCatalogEntry::fullName).toList());
        assertEquals(4_993, repository.findCurrentRateLimit(
                organizationId, connectionId, "core").orElseThrow().remaining());

        assertTrue(repository.findProfile(
                new OrganizationId(UUID.randomUUID()), connectionId, 0).isEmpty());
        assertTrue(repository.findRepository(
                new OrganizationId(UUID.randomUUID()), connectionId, "101").isEmpty());
    }

    @Test
    void storesIndependentProfilesAndRateFactsForSuccessiveConnectionVersions() {
        repository.insertProfile(profile(0));
        GitHubConnectionProfile versionOne = repository.insertProfile(profile(1));
        repository.synchronizeCatalog(
                versionOne, List.of(entry("101", "repository-a", true, 1)), rate(1, now));

        assertTrue(repository.findProfile(organizationId, connectionId, 0).isPresent());
        assertTrue(repository.findProfile(organizationId, connectionId, 1).isPresent());
        assertEquals(1, repository.findRepository(
                organizationId, connectionId, "101").orElseThrow().connectionVersion());
        assertEquals(1, repository.findCurrentRateLimit(
                organizationId, connectionId, "core").orElseThrow().connectionVersion());
    }

    private GitHubConnectionProfile profile(long connectionVersion) {
        return new GitHubConnectionProfile(
                UUID.nameUUIDFromBytes(("profile-" + connectionVersion).getBytes()),
                organizationId, connectionId, connectionVersion, owner,
                ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT,
                GitHubAuthenticationType.APP_INSTALLATION, "4815", "crewscope",
                GitHubPermission.minimumDraftDelivery(), GitHubHash.sha256("crewscope/repository-a"),
                GitHubConnectionProfileStatus.ACTIVE, 0, AuditMetadata.createdBy(actor, now));
    }

    private GitHubRepositoryCatalogEntry entry(
            String id, String name, boolean deliverable, long connectionVersion) {
        return entry(id, name, deliverable, connectionVersion, now);
    }

    private GitHubRepositoryCatalogEntry entry(
            String id,
            String name,
            boolean deliverable,
            long connectionVersion,
            UtcTimestamp observedAt) {
        return new GitHubRepositoryCatalogEntry(
                UUID.nameUUIDFromBytes(("repository-" + id).getBytes()), organizationId,
                connectionId, connectionVersion,
                ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT, id, "crewscope", name,
                "crewscope/" + name, new RepositoryBranchName("main"),
                GitHubRepositoryVisibility.PRIVATE, false, false, true, deliverable,
                deliverable, GitHubHash.sha256("permissions-" + deliverable), Optional.empty(),
                observedAt, UtcTimestamp.from(observedAt.value().plusSeconds(300)),
                deliverable ? GitHubRepositoryStatus.DELIVERABLE : GitHubRepositoryStatus.BLOCKED,
                0, AuditMetadata.createdBy(actor, observedAt));
    }

    private GitHubRateLimitSnapshot rate(long connectionVersion, UtcTimestamp observedAt) {
        return new GitHubRateLimitSnapshot(
                UUID.randomUUID(), organizationId, connectionId, connectionVersion,
                "core", 5_000, 4_993, 7,
                UtcTimestamp.from(observedAt.value().plusSeconds(3_600)), observedAt, actor);
    }

    private void seedAuthority() throws SQLException {
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'M5 I08', 'ACTIVE')",
                organizationId.value());
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'M5 I08 Actor', 'ORGANIZATION', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
