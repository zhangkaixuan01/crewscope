package io.crewscope.infrastructure.persistence.github;

import io.crewscope.application.github.GitHubRepositoryImportJob;
import io.crewscope.application.github.GitHubRepositoryImportLease;
import io.crewscope.application.github.GitHubRepositoryImportJobRepository;
import io.crewscope.application.github.GitHubRepositoryImportStatus;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence for import jobs; only stable identifiers and reason codes are stored. */
@Repository
public class JdbcGitHubRepositoryImportJobRepositoryAdapter implements GitHubRepositoryImportJobRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGitHubRepositoryImportJobRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GitHubRepositoryImportJob create(GitHubRepositoryImportJob job) {
        try {
            jdbc.update("""
                    INSERT INTO crewscope.github_repository_import_job
                    (id, organization_id, team_id, project_id, connection_id, connection_version,
                     grant_id, grant_version, external_repository_id, repository_full_name, repository_key,
                     default_branch, status, progress_percent, attempt, failure_code, binding_id,
                     created_by_principal_id, created_by_platform_administrator, created_at, updated_at)
                    VALUES (:id,:organizationId,:teamId,:projectId,:connectionId,:connectionVersion,
                     :grantId,:grantVersion,:externalRepositoryId,:repositoryFullName,:repositoryKey,
                     :defaultBranch,:status,:progressPercent,:attempt,:failureCode,:bindingId,
                     :createdBy,:createdByPlatformAdministrator,:createdAt,:updatedAt)
                    """, params(job));
        } catch (DuplicateKeyException conflict) {
            throw new io.crewscope.application.github.GitHubProviderException(
                    io.crewscope.application.github.GitHubProviderErrorCode.CONFLICT,
                    "Repository Key is already managed; bind the existing repository or choose another key");
        }
        return job;
    }

    @Override
    public GitHubRepositoryImportJob update(GitHubRepositoryImportJob job) {
        jdbc.update("""
                UPDATE crewscope.github_repository_import_job SET
                 connection_id=:connectionId, connection_version=:connectionVersion,
                 grant_id=:grantId, grant_version=:grantVersion,
                 repository_full_name=:repositoryFullName, default_branch=:defaultBranch,
                 status=:status, progress_percent=:progressPercent, attempt=:attempt,
                 failure_code=:failureCode, binding_id=:bindingId,
                 created_by_principal_id=:createdBy,
                 created_by_platform_administrator=:createdByPlatformAdministrator,
                 lease_owner=NULL, lease_expires_at=NULL, updated_at=:updatedAt
                 WHERE organization_id=:organizationId AND id=:id
                """, new MapSqlParameterSource()
                .addValues(params(job).getValues()));
        return job;
    }

    @Override
    public Optional<GitHubRepositoryImportJob> findById(OrganizationId organizationId, TeamId teamId,
            WorkProjectId projectId, UUID jobId) {
        return first("SELECT * FROM crewscope.github_repository_import_job WHERE organization_id=:organizationId AND team_id=:teamId AND project_id=:projectId AND id=:id",
                new MapSqlParameterSource().addValue("organizationId", organizationId.value()).addValue("teamId", teamId.value()).addValue("projectId", projectId.value()).addValue("id", jobId));
    }

    @Override
    public Optional<GitHubRepositoryImportJob> findActiveByTarget(OrganizationId organizationId, TeamId teamId,
            WorkProjectId projectId, String externalRepositoryId, RepositoryKey repositoryKey) {
        return first("SELECT * FROM crewscope.github_repository_import_job WHERE organization_id=:organizationId AND team_id=:teamId AND project_id=:projectId AND external_repository_id=:externalRepositoryId AND repository_key=:repositoryKey ORDER BY updated_at DESC LIMIT 1",
                new MapSqlParameterSource().addValue("organizationId", organizationId.value()).addValue("teamId", teamId.value()).addValue("projectId", projectId.value()).addValue("externalRepositoryId", externalRepositoryId).addValue("repositoryKey", repositoryKey.value()));
    }

    @Override
    public Optional<GitHubRepositoryImportJob> findByRepositoryKey(RepositoryKey repositoryKey) {
        return first("SELECT * FROM crewscope.github_repository_import_job WHERE repository_key=:repositoryKey LIMIT 1",
                new MapSqlParameterSource().addValue("repositoryKey", repositoryKey.value()));
    }

    @Override
    public Optional<GitHubRepositoryImportJob> cancelBeforeImport(
            GitHubRepositoryImportJob job, UtcTimestamp cancelledAt) {
        GitHubRepositoryImportJob required = java.util.Objects.requireNonNull(job, "job");
        UtcTimestamp now = java.util.Objects.requireNonNull(cancelledAt, "cancelledAt");
        return jdbc.query("""
                UPDATE crewscope.github_repository_import_job
                SET status='CANCELLED', failure_code='CANCELLED_BY_USER',
                    lease_owner=NULL, lease_expires_at=NULL, updated_at=:cancelledAt
                WHERE organization_id=:organizationId AND team_id=:teamId
                  AND project_id=:projectId AND id=:id
                  AND status IN ('REQUESTED','PREFLIGHTING')
                RETURNING *
                """, new MapSqlParameterSource()
                        .addValue("organizationId", required.organizationId().value())
                        .addValue("teamId", required.teamId().value())
                        .addValue("projectId", required.projectId().value())
                        .addValue("id", required.id())
                        .addValue("cancelledAt", offset(now)),
                (row, number) -> map(row)).stream().findFirst();
    }

    @Override
    public Optional<GitHubRepositoryImportLease> claimNext(
            String leaseOwner, UtcTimestamp now, Duration leaseDuration) {
        String owner = requireLeaseOwner(leaseOwner);
        OffsetDateTime claimedAt = offset(now);
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                now.value().plus(requireLeaseDuration(leaseDuration)), ZoneOffset.UTC);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM crewscope.github_repository_import_job
                    WHERE status = 'REQUESTED'
                       OR (status IN ('PREFLIGHTING','IMPORTING') AND lease_expires_at <= :claimedAt)
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE crewscope.github_repository_import_job AS target
                SET status='PREFLIGHTING', progress_percent=10, attempt=target.attempt + 1,
                    failure_code=NULL, binding_id=NULL, lease_owner=:leaseOwner,
                    lease_expires_at=:leaseExpiresAt, updated_at=:claimedAt
                FROM candidate
                WHERE target.id=candidate.id
                RETURNING target.*
                """, new MapSqlParameterSource()
                        .addValue("claimedAt", claimedAt)
                        .addValue("leaseOwner", owner)
                        .addValue("leaseExpiresAt", expiresAt),
                (row, number) -> new GitHubRepositoryImportLease(
                        map(row), owner, UtcTimestamp.from(row.getObject(
                                "lease_expires_at", OffsetDateTime.class))))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<GitHubRepositoryImportJob> updateClaimed(
            GitHubRepositoryImportJob job,
            String leaseOwner,
            UtcTimestamp now,
            Duration leaseDuration) {
        GitHubRepositoryImportJob required = java.util.Objects.requireNonNull(job, "job");
        String owner = requireLeaseOwner(leaseOwner);
        boolean terminal = required.status() == GitHubRepositoryImportStatus.READY
                || required.status() == GitHubRepositoryImportStatus.FAILED
                || required.status() == GitHubRepositoryImportStatus.CANCELLED;
        MapSqlParameterSource parameters = params(required)
                .addValue("leaseOwner", owner)
                .addValue("now", offset(now))
                .addValue("nextLeaseOwner", terminal ? null : owner)
                .addValue("nextLeaseExpiresAt", terminal ? null : OffsetDateTime.ofInstant(
                        now.value().plus(requireLeaseDuration(leaseDuration)), ZoneOffset.UTC));
        return jdbc.query("""
                UPDATE crewscope.github_repository_import_job
                SET status=:status, progress_percent=:progressPercent, attempt=:attempt,
                    failure_code=:failureCode, binding_id=:bindingId,
                    lease_owner=:nextLeaseOwner, lease_expires_at=:nextLeaseExpiresAt,
                    updated_at=:updatedAt
                WHERE organization_id=:organizationId AND id=:id
                  AND status IN ('PREFLIGHTING','IMPORTING')
                  AND lease_owner=:leaseOwner AND lease_expires_at > :now
                RETURNING *
                """, parameters, (row, number) -> map(row)).stream().findFirst();
    }

    private Optional<GitHubRepositoryImportJob> first(String sql, MapSqlParameterSource p) {
        return jdbc.query(sql, p, (row, n) -> map(row)).stream().findFirst();
    }

    private static GitHubRepositoryImportJob map(ResultSet row) throws SQLException {
        UtcTimestamp created = UtcTimestamp.from(row.getObject("created_at", OffsetDateTime.class).toInstant());
        UtcTimestamp updated = UtcTimestamp.from(row.getObject("updated_at", OffsetDateTime.class).toInstant());
        PrincipalId creator = new PrincipalId(row.getObject("created_by_principal_id", UUID.class));
        return new GitHubRepositoryImportJob(
                row.getObject("id", UUID.class), new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)), new WorkProjectId(row.getObject("project_id", UUID.class)),
                new ConnectionId(row.getObject("connection_id", UUID.class)), row.getLong("connection_version"),
                new ConnectionGrantId(row.getObject("grant_id", UUID.class)), row.getLong("grant_version"),
                row.getString("external_repository_id"), row.getString("repository_full_name"),
                RepositoryKey.parse(row.getString("repository_key")), new RepositoryBranchName(row.getString("default_branch")),
                GitHubRepositoryImportStatus.valueOf(row.getString("status")), row.getInt("progress_percent"), row.getInt("attempt"),
                Optional.ofNullable(row.getString("failure_code")), Optional.ofNullable(row.getObject("binding_id", UUID.class)).map(RepositoryBindingId::new),
                creator, row.getBoolean("created_by_platform_administrator"), created, updated);
    }

    private static MapSqlParameterSource params(GitHubRepositoryImportJob job) {
        return new MapSqlParameterSource()
                .addValue("id", job.id()).addValue("organizationId", job.organizationId().value()).addValue("teamId", job.teamId().value())
                .addValue("projectId", job.projectId().value()).addValue("connectionId", job.connectionId().value()).addValue("connectionVersion", job.connectionVersion())
                .addValue("grantId", job.grantId().value()).addValue("grantVersion", job.grantVersion()).addValue("externalRepositoryId", job.externalRepositoryId())
                .addValue("repositoryFullName", job.repositoryFullName()).addValue("repositoryKey", job.repositoryKey().value()).addValue("defaultBranch", job.defaultBranch().value())
                .addValue("status", job.status().name()).addValue("progressPercent", job.progressPercent()).addValue("attempt", job.attempt())
                .addValue("failureCode", job.failureCode().orElse(null)).addValue("bindingId", job.bindingId().map(RepositoryBindingId::value).orElse(null))
                .addValue("createdBy", job.createdBy().value())
                .addValue("createdByPlatformAdministrator", job.createdByPlatformAdministrator())
                .addValue("createdAt", OffsetDateTime.ofInstant(job.createdAt().value(), ZoneOffset.UTC))
                .addValue("updatedAt", OffsetDateTime.ofInstant(job.updatedAt().value(), ZoneOffset.UTC));
    }

    private static OffsetDateTime offset(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(
                java.util.Objects.requireNonNull(value, "now").value(), ZoneOffset.UTC);
    }

    private static String requireLeaseOwner(String value) {
        String normalized = java.util.Objects.requireNonNull(value, "leaseOwner").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("leaseOwner must contain 1 to 160 characters");
        }
        return normalized;
    }

    private static Duration requireLeaseDuration(Duration value) {
        Duration required = java.util.Objects.requireNonNull(value, "leaseDuration");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return required;
    }
}
