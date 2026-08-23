package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubAuthenticationType;
import io.crewscope.application.github.GitHubConnectionProfile;
import io.crewscope.application.github.GitHubConnectionProfileStatus;
import io.crewscope.application.github.GitHubPermission;
import io.crewscope.application.github.GitHubProviderRepository;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL adapter for safe GitHub identity, catalog and rate-limit facts. */
@Repository
public class JdbcGitHubProviderRepositoryAdapter implements GitHubProviderRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGitHubProviderRepositoryAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<GitHubConnectionProfile> findProfile(
            OrganizationId organizationId, ConnectionId connectionId, long connectionVersion) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.github_connection_profile
                WHERE organization_id = ? AND connection_id = ? AND connection_version = ?
                """,
                this::profile,
                organizationId.value(), connectionId.value(), connectionVersion));
    }

    @Override
    @Transactional
    public GitHubConnectionProfile insertProfile(GitHubConnectionProfile profile) {
        GitHubConnectionProfile value = Objects.requireNonNull(profile, "profile");
        jdbc.update(
                """
                INSERT INTO crewscope.github_connection_profile (
                    id, organization_id, connection_id, connection_version,
                    connection_owner_type, connection_owner_id, external_identity,
                    authentication_type, external_account_id, external_account_login,
                    granted_permissions, repository_allowlist_hash, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, connection_id, connection_version) DO NOTHING
                """,
                value.id(), value.organizationId().value(), value.connectionId().value(),
                value.connectionVersion(), value.connectionOwner().type().name(),
                value.connectionOwner().ownerId(), value.externalIdentity().name(),
                value.authenticationType().name(), value.externalAccountId(),
                value.externalAccountLogin(), permissionsJson(value.grantedPermissions()),
                value.repositoryAllowlistHash(), value.status().name(), value.version(),
                time(value.audit().createdAt()), creator(value.audit()),
                time(value.audit().updatedAt()), updater(value.audit()));
        return findProfile(value.organizationId(), value.connectionId(), value.connectionVersion())
                .orElseThrow();
    }

    @Override
    @Transactional
    public void synchronizeCatalog(
            GitHubConnectionProfile profile,
            List<GitHubRepositoryCatalogEntry> entries,
            GitHubRateLimitSnapshot rateLimit) {
        GitHubConnectionProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        List<GitHubRepositoryCatalogEntry> requiredEntries = List.copyOf(
                Objects.requireNonNull(entries, "entries"));
        GitHubRateLimitSnapshot requiredRate = Objects.requireNonNull(rateLimit, "rateLimit");
        requireExactProfile(requiredProfile);
        UtcTimestamp updatedAt = requiredRate.observedAt();
        PrincipalId actor = requiredRate.createdBy();
        for (GitHubRepositoryCatalogEntry entry : requiredEntries) {
            requireEntryScope(requiredProfile, entry);
            upsert(entry, actor, updatedAt);
        }
        markMissingRepositoriesStale(requiredProfile, requiredEntries, actor, updatedAt);
        requireRateScope(requiredProfile, requiredRate);
        jdbc.update(
                """
                INSERT INTO crewscope.github_rate_limit_snapshot (
                    id, organization_id, connection_id, connection_version, resource,
                    rate_limit, remaining, used, resets_at, observed_at,
                    created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connection_id, resource, observed_at) DO NOTHING
                """,
                requiredRate.id(), requiredRate.organizationId().value(),
                requiredRate.connectionId().value(), requiredRate.connectionVersion(),
                requiredRate.resource(), requiredRate.limit(), requiredRate.remaining(),
                requiredRate.used(), time(requiredRate.resetsAt()), time(requiredRate.observedAt()),
                time(requiredRate.observedAt()), requiredRate.createdBy().value());
    }

    @Override
    @Transactional
    public void recordPreflight(
            GitHubConnectionProfile profile,
            GitHubRepositoryCatalogEntry entry,
            GitHubRateLimitSnapshot rateLimit) {
        GitHubConnectionProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        GitHubRepositoryCatalogEntry requiredEntry = Objects.requireNonNull(entry, "entry");
        GitHubRateLimitSnapshot requiredRate = Objects.requireNonNull(rateLimit, "rateLimit");
        requireExactProfile(requiredProfile);
        requireEntryScope(requiredProfile, requiredEntry);
        requireRateScope(requiredProfile, requiredRate);
        upsert(requiredEntry, requiredRate.createdBy(), requiredRate.observedAt());
        jdbc.update(
                """
                INSERT INTO crewscope.github_rate_limit_snapshot (
                    id, organization_id, connection_id, connection_version, resource,
                    rate_limit, remaining, used, resets_at, observed_at,
                    created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connection_id, resource, observed_at) DO NOTHING
                """,
                requiredRate.id(), requiredRate.organizationId().value(),
                requiredRate.connectionId().value(), requiredRate.connectionVersion(),
                requiredRate.resource(), requiredRate.limit(), requiredRate.remaining(),
                requiredRate.used(), time(requiredRate.resetsAt()), time(requiredRate.observedAt()),
                time(requiredRate.observedAt()), requiredRate.createdBy().value());
    }

    @Override
    public Optional<GitHubRepositoryCatalogEntry> findRepository(
            OrganizationId organizationId,
            ConnectionId connectionId,
            String externalRepositoryId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.github_repository_catalog_entry
                WHERE organization_id = ? AND connection_id = ? AND external_repository_id = ?
                """,
                this::repository,
                organizationId.value(), connectionId.value(), externalRepositoryId));
    }

    @Override
    public List<GitHubRepositoryCatalogEntry> findDeliverableRepositories(
            OrganizationId organizationId, ConnectionId connectionId) {
        return jdbc.query(
                """
                SELECT * FROM crewscope.github_repository_catalog_entry
                WHERE organization_id = ? AND connection_id = ? AND status = 'DELIVERABLE'
                ORDER BY full_name, id
                """,
                this::repository,
                organizationId.value(), connectionId.value());
    }

    @Override
    public Optional<GitHubRateLimitSnapshot> findCurrentRateLimit(
            OrganizationId organizationId, ConnectionId connectionId, String resource) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.github_rate_limit_snapshot
                WHERE organization_id = ? AND connection_id = ? AND resource = ?
                ORDER BY observed_at DESC, id DESC LIMIT 1
                """,
                this::rateLimit,
                organizationId.value(), connectionId.value(), resource));
    }

    private void upsert(
            GitHubRepositoryCatalogEntry value, PrincipalId actor, UtcTimestamp updatedAt) {
        jdbc.update(
                """
                INSERT INTO crewscope.github_repository_catalog_entry (
                    id, organization_id, connection_id, connection_version, external_identity,
                    external_repository_id, owner_login, repository_name, full_name,
                    default_branch, visibility, archived, fork, can_pull, can_push,
                    can_create_pull_request, permissions_hash, etag_hash,
                    discovered_at, cache_expires_at, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connection_id, external_repository_id) DO UPDATE SET
                    connection_version = EXCLUDED.connection_version,
                    external_identity = EXCLUDED.external_identity,
                    owner_login = EXCLUDED.owner_login,
                    repository_name = EXCLUDED.repository_name,
                    full_name = EXCLUDED.full_name,
                    default_branch = EXCLUDED.default_branch,
                    visibility = EXCLUDED.visibility,
                    archived = EXCLUDED.archived,
                    fork = EXCLUDED.fork,
                    can_pull = EXCLUDED.can_pull,
                    can_push = EXCLUDED.can_push,
                    can_create_pull_request = EXCLUDED.can_create_pull_request,
                    permissions_hash = EXCLUDED.permissions_hash,
                    etag_hash = EXCLUDED.etag_hash,
                    discovered_at = EXCLUDED.discovered_at,
                    cache_expires_at = EXCLUDED.cache_expires_at,
                    status = EXCLUDED.status,
                    version = crewscope.github_repository_catalog_entry.version + 1,
                    updated_at = EXCLUDED.updated_at,
                    updated_by_principal_id = EXCLUDED.updated_by_principal_id
                """,
                value.id(), value.organizationId().value(), value.connectionId().value(),
                value.connectionVersion(), value.externalIdentity().name(),
                value.externalRepositoryId(), value.ownerLogin(), value.repositoryName(),
                value.fullName(), value.defaultBranch().value(), value.visibility().name(),
                value.archived(), value.fork(), value.canPull(), value.canPush(),
                value.canCreatePullRequest(), value.permissionsHash(),
                value.etagHash().orElse(null), time(value.discoveredAt()),
                time(value.cacheExpiresAt()), value.status().name(), value.version(),
                time(value.audit().createdAt()), creator(value.audit()),
                time(updatedAt), actor.value());
    }

    private void markMissingRepositoriesStale(
            GitHubConnectionProfile profile,
            List<GitHubRepositoryCatalogEntry> currentEntries,
            PrincipalId actor,
            UtcTimestamp updatedAt) {
        String exclusion = currentEntries.isEmpty()
                ? ""
                : " AND external_repository_id NOT IN ("
                        + String.join(",", java.util.Collections.nCopies(
                                currentEntries.size(), "?"))
                        + ")";
        List<Object> parameters = new ArrayList<>();
        parameters.add(time(updatedAt));
        parameters.add(actor.value());
        parameters.add(profile.organizationId().value());
        parameters.add(profile.connectionId().value());
        currentEntries.stream()
                .map(GitHubRepositoryCatalogEntry::externalRepositoryId)
                .forEach(parameters::add);
        jdbc.update(
                """
                UPDATE crewscope.github_repository_catalog_entry
                SET status = 'STALE', version = version + 1,
                    updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND connection_id = ? AND status <> 'STALE'
                """ + exclusion,
                parameters.toArray());
    }

    private GitHubConnectionProfile profile(ResultSet row, int ignored) throws SQLException {
        OrganizationId organizationId = new OrganizationId(row.getObject("organization_id", UUID.class));
        ProviderOwner owner = owner(
                organizationId,
                ProviderOwnerType.valueOf(row.getString("connection_owner_type")),
                row.getObject("connection_owner_id", UUID.class));
        return new GitHubConnectionProfile(
                row.getObject("id", UUID.class), organizationId,
                new ConnectionId(row.getObject("connection_id", UUID.class)),
                row.getLong("connection_version"), owner,
                ProviderExecutionIdentity.valueOf(row.getString("external_identity")),
                GitHubAuthenticationType.valueOf(row.getString("authentication_type")),
                row.getString("external_account_id"), row.getString("external_account_login"),
                permissions(row.getString("granted_permissions")),
                row.getString("repository_allowlist_hash"),
                GitHubConnectionProfileStatus.valueOf(row.getString("status")),
                row.getLong("version"), audit(row));
    }

    private GitHubRepositoryCatalogEntry repository(ResultSet row, int ignored) throws SQLException {
        return new GitHubRepositoryCatalogEntry(
                row.getObject("id", UUID.class),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new ConnectionId(row.getObject("connection_id", UUID.class)),
                row.getLong("connection_version"),
                ProviderExecutionIdentity.valueOf(row.getString("external_identity")),
                row.getString("external_repository_id"), row.getString("owner_login"),
                row.getString("repository_name"), row.getString("full_name"),
                new RepositoryBranchName(row.getString("default_branch")),
                GitHubRepositoryVisibility.valueOf(row.getString("visibility")),
                row.getBoolean("archived"), row.getBoolean("fork"),
                row.getBoolean("can_pull"), row.getBoolean("can_push"),
                row.getBoolean("can_create_pull_request"), row.getString("permissions_hash"),
                Optional.ofNullable(row.getString("etag_hash")),
                timestamp(row, "discovered_at"), timestamp(row, "cache_expires_at"),
                GitHubRepositoryStatus.valueOf(row.getString("status")),
                row.getLong("version"), audit(row));
    }

    private GitHubRateLimitSnapshot rateLimit(ResultSet row, int ignored) throws SQLException {
        return new GitHubRateLimitSnapshot(
                row.getObject("id", UUID.class),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new ConnectionId(row.getObject("connection_id", UUID.class)),
                row.getLong("connection_version"), row.getString("resource"),
                row.getLong("rate_limit"), row.getLong("remaining"), row.getLong("used"),
                timestamp(row, "resets_at"), timestamp(row, "observed_at"),
                new PrincipalId(row.getObject("created_by_principal_id", UUID.class)));
    }

    private void requireExactProfile(GitHubConnectionProfile profile) {
        GitHubConnectionProfile committed = one(jdbc.query(
                """
                SELECT * FROM crewscope.github_connection_profile
                WHERE organization_id = ? AND connection_id = ? AND connection_version = ?
                FOR UPDATE
                """,
                this::profile,
                profile.organizationId().value(), profile.connectionId().value(),
                profile.connectionVersion()))
                .orElseThrow(() -> new IllegalStateException(
                        "GitHub Connection Profile is not committed"));
        if (!committed.equals(profile)) {
            throw new IllegalStateException("GitHub Connection Profile authority drifted");
        }
    }

    private static void requireEntryScope(
            GitHubConnectionProfile profile, GitHubRepositoryCatalogEntry entry) {
        if (!profile.organizationId().equals(entry.organizationId())
                || !profile.connectionId().equals(entry.connectionId())
                || profile.connectionVersion() != entry.connectionVersion()
                || profile.externalIdentity() != entry.externalIdentity()) {
            throw new IllegalArgumentException("GitHub repository does not match the Profile scope");
        }
    }

    private static void requireRateScope(
            GitHubConnectionProfile profile, GitHubRateLimitSnapshot rate) {
        if (!profile.organizationId().equals(rate.organizationId())
                || !profile.connectionId().equals(rate.connectionId())
                || profile.connectionVersion() != rate.connectionVersion()) {
            throw new IllegalArgumentException("GitHub rate limit does not match the Profile scope");
        }
    }

    private String permissionsJson(java.util.Set<GitHubPermission> permissions) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        permissions.stream().sorted().forEach(value -> values.put(value.name(), true));
        return objectMapper.writeValueAsString(values);
    }

    private java.util.Set<GitHubPermission> permissions(String json) {
        JsonNode root = objectMapper.readTree(json);
        EnumSet<GitHubPermission> result = EnumSet.noneOf(GitHubPermission.class);
        root.properties().forEach(entry -> {
            if (entry.getValue().isBoolean() && entry.getValue().booleanValue()) {
                result.add(GitHubPermission.valueOf(entry.getKey()));
            }
        });
        return java.util.Set.copyOf(result);
    }

    private static ProviderOwner owner(
            OrganizationId organizationId, ProviderOwnerType type, UUID ownerId) {
        return new ProviderOwner(
                organizationId,
                type,
                ownerId,
                type == ProviderOwnerType.TEAM
                        ? Optional.of(new TeamId(ownerId)) : Optional.empty(),
                type == ProviderOwnerType.USER
                        ? Optional.of(new PrincipalId(ownerId)) : Optional.empty());
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(row.getObject("created_by_principal_id", UUID.class))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(row.getObject("updated_by_principal_id", UUID.class))),
                timestamp(row, "updated_at"));
    }

    private static UUID creator(AuditMetadata audit) {
        return audit.createdBy().orElseThrow().value();
    }

    private static UUID updater(AuditMetadata audit) {
        return audit.updatedBy().orElseThrow().value();
    }

    private static OffsetDateTime time(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class).toInstant());
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Expected at most one GitHub persistence row");
        }
        return values.stream().findFirst();
    }
}
