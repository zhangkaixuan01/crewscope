package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.organization.governance.OrganizationAccountDeletionJobRepository;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.organization.governance.AccountDeletionItem;
import io.crewscope.domain.organization.governance.AccountDeletionItemId;
import io.crewscope.domain.organization.governance.AccountDeletionItemState;
import io.crewscope.domain.organization.governance.AccountDeletionJob;
import io.crewscope.domain.organization.governance.AccountDeletionJobId;
import io.crewscope.domain.organization.governance.AccountDeletionJobStatus;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for the V69 account-deletion-job tables. The job row lock serializes
 * confirm/process/cancel; each step item commits in its own short transaction under the previous
 * version as the optimistic lock predicate, so a re-run after a crash or a restored backup
 * resumes at the first PENDING step and never replays a settled one.
 */
@Repository
public class JdbcOrganizationAccountDeletionJobRepositoryAdapter
        implements OrganizationAccountDeletionJobRepository {

    private static final String JOB_COLUMNS =
            "id, organization_id, account_id, target_principal_id, reason,"
                    + " confirmation_token_hash, status, command_id, reservation_check,"
                    + " backup_retention_statement, version, created_at, created_by_principal_id,"
                    + " updated_at, updated_by_principal_id";

    private static final String ITEM_COLUMNS =
            "id, organization_id, job_id, step_key, target_ref, expected_count, state,"
                    + " result_count, error_code, processed_at, version, created_at,"
                    + " created_by_principal_id, updated_at, updated_by_principal_id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationAccountDeletionJobRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public AccountDeletionJob createJob(AccountDeletionJob job, List<AccountDeletionItem> items) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(items, "items");
        jdbc.update(
                "INSERT INTO crewscope.organization_account_deletion_job ("
                        + "id, organization_id, account_id, target_principal_id, reason,"
                        + " confirmation_token_hash, status, command_id, reservation_check,"
                        + " backup_retention_statement, version, created_at,"
                        + " created_by_principal_id, updated_at, updated_by_principal_id)"
                        + " VALUES (:id, :organizationId, :accountId, :targetPrincipalId, :reason,"
                        + " :confirmationTokenHash, :status, :commandId,"
                        + " CAST(:reservationCheck AS JSONB), :backupRetentionStatement, :version,"
                        + " :createdAt, :createdBy, :updatedAt, :updatedBy)",
                jobParameters(job));
        for (AccountDeletionItem item : items) {
            jdbc.update(
                    "INSERT INTO crewscope.organization_account_deletion_item ("
                            + "id, organization_id, job_id, step_key, target_ref, expected_count,"
                            + " state, result_count, error_code, processed_at, version, created_at,"
                            + " created_by_principal_id, updated_at, updated_by_principal_id)"
                            + " VALUES (:id, :organizationId, :jobId, :stepKey, :targetRef,"
                            + " :expectedCount, :state, :resultCount, :errorCode, :processedAt,"
                            + " :version, :createdAt, :createdBy, :updatedAt, :updatedBy)",
                    itemParameters(item));
        }
        return job;
    }

    @Override
    @Transactional
    public Optional<AccountDeletionJob> lockJobById(
            OrganizationId organizationId, AccountDeletionJobId jobId) {
        List<AccountDeletionJob> matches = jdbc.query(
                "SELECT " + JOB_COLUMNS + " FROM crewscope.organization_account_deletion_job"
                        + " WHERE organization_id = :organizationId AND id = :jobId FOR UPDATE",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("jobId", jobId.value()),
                JdbcOrganizationAccountDeletionJobRepositoryAdapter::mapJob);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountDeletionJob> findJobById(
            OrganizationId organizationId, AccountDeletionJobId jobId) {
        List<AccountDeletionJob> matches = jdbc.query(
                "SELECT " + JOB_COLUMNS + " FROM crewscope.organization_account_deletion_job"
                        + " WHERE organization_id = :organizationId AND id = :jobId",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("jobId", jobId.value()),
                JdbcOrganizationAccountDeletionJobRepositoryAdapter::mapJob);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountDeletionJob> findJobByCommandId(
            OrganizationId organizationId, String commandId) {
        List<AccountDeletionJob> matches = jdbc.query(
                "SELECT " + JOB_COLUMNS + " FROM crewscope.organization_account_deletion_job"
                        + " WHERE organization_id = :organizationId AND command_id = :commandId",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("commandId", commandId),
                JdbcOrganizationAccountDeletionJobRepositoryAdapter::mapJob);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    @Transactional
    public AccountDeletionJob updateJob(AccountDeletionJob job) {
        Objects.requireNonNull(job, "job");
        int updated = jdbc.update(
                "UPDATE crewscope.organization_account_deletion_job SET"
                        + " status = :status, version = :version, updated_at = :updatedAt,"
                        + " updated_by_principal_id = :updatedBy"
                        + " WHERE id = :id AND organization_id = :organizationId"
                        + " AND version = :version - 1",
                jobParameters(job));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_ACCOUNT_DELETION_JOB",
                    job.id().value().toString(),
                    job.version(),
                    job.version() - 1);
        }
        return job;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDeletionItem> findItems(
            OrganizationId organizationId, AccountDeletionJobId jobId) {
        return jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM crewscope.organization_account_deletion_item"
                        + " WHERE organization_id = :organizationId AND job_id = :jobId"
                        + " ORDER BY step_key, target_ref",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("jobId", jobId.value()),
                JdbcOrganizationAccountDeletionJobRepositoryAdapter::mapItem);
    }

    @Override
    @Transactional
    public Optional<AccountDeletionItem> lockItemById(
            OrganizationId organizationId, AccountDeletionItemId itemId) {
        List<AccountDeletionItem> matches = jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM crewscope.organization_account_deletion_item"
                        + " WHERE organization_id = :organizationId AND id = :itemId FOR UPDATE",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("itemId", itemId.value()),
                JdbcOrganizationAccountDeletionJobRepositoryAdapter::mapItem);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    @Transactional
    public AccountDeletionItem updateItem(AccountDeletionItem item) {
        Objects.requireNonNull(item, "item");
        int updated = jdbc.update(
                "UPDATE crewscope.organization_account_deletion_item SET"
                        + " state = :state, result_count = :resultCount, error_code = :errorCode,"
                        + " processed_at = :processedAt, version = :version,"
                        + " updated_at = :updatedAt, updated_by_principal_id = :updatedBy"
                        + " WHERE id = :id AND organization_id = :organizationId"
                        + " AND version = :version - 1",
                itemParameters(item));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_ACCOUNT_DELETION_ITEM",
                    item.id().value().toString(),
                    item.version(),
                    item.version() - 1);
        }
        return item;
    }

    private static MapSqlParameterSource jobParameters(AccountDeletionJob job) {
        return new MapSqlParameterSource()
                .addValue("id", job.id().value())
                .addValue("organizationId", job.organizationId().value())
                .addValue("accountId", job.accountId().value())
                .addValue("targetPrincipalId", job.targetPrincipalId().value())
                .addValue("reason", job.reason())
                .addValue("confirmationTokenHash", job.confirmationTokenHash())
                .addValue("status", job.status().name())
                .addValue("commandId", job.commandId())
                .addValue("reservationCheck", job.reservationCheck())
                .addValue("backupRetentionStatement", job.backupRetentionStatement())
                .addValue("version", job.version())
                .addValue("createdAt", job.audit().createdAt().toOffsetDateTime())
                .addValue("createdBy", job.audit().createdBy().map(PrincipalId::value).orElse(null))
                .addValue("updatedAt", job.audit().updatedAt().toOffsetDateTime())
                .addValue("updatedBy", job.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    private static MapSqlParameterSource itemParameters(AccountDeletionItem item) {
        return new MapSqlParameterSource()
                .addValue("id", item.id().value())
                .addValue("organizationId", item.organizationId().value())
                .addValue("jobId", item.jobId().value())
                .addValue("stepKey", item.stepKey())
                .addValue("targetRef", item.targetRef())
                .addValue("expectedCount", item.expectedCount())
                .addValue("state", item.state().name())
                .addValue("resultCount", item.resultCount().orElse(null))
                .addValue("errorCode", item.errorCode().orElse(null))
                .addValue("processedAt", item.processedAt().map(UtcTimestamp::toOffsetDateTime).orElse(null))
                .addValue("version", item.version())
                .addValue("createdAt", item.audit().createdAt().toOffsetDateTime())
                .addValue("createdBy", item.audit().createdBy().map(PrincipalId::value).orElse(null))
                .addValue("updatedAt", item.audit().updatedAt().toOffsetDateTime())
                .addValue("updatedBy", item.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    private static AccountDeletionJob mapJob(ResultSet rs, int rowNum) throws SQLException {
        return AccountDeletionJob.reconstitute(
                new AccountDeletionJobId(rs.getObject("id", UUID.class)),
                new OrganizationId(rs.getObject("organization_id", UUID.class)),
                new UserAccountId(rs.getObject("account_id", UUID.class)),
                new PrincipalId(rs.getObject("target_principal_id", UUID.class)),
                rs.getString("reason"),
                rs.getString("confirmation_token_hash"),
                AccountDeletionJobStatus.valueOf(rs.getString("status")),
                rs.getString("command_id"),
                rs.getString("reservation_check"),
                rs.getString("backup_retention_statement"),
                rs.getLong("version"),
                new AuditMetadata(
                        Optional.of(new PrincipalId(
                                rs.getObject("created_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                        Optional.of(new PrincipalId(
                                rs.getObject("updated_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
    }

    private static AccountDeletionItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return AccountDeletionItem.reconstitute(
                new AccountDeletionItemId(rs.getObject("id", UUID.class)),
                new OrganizationId(rs.getObject("organization_id", UUID.class)),
                new AccountDeletionJobId(rs.getObject("job_id", UUID.class)),
                rs.getString("step_key"),
                rs.getString("target_ref"),
                rs.getInt("expected_count"),
                AccountDeletionItemState.valueOf(rs.getString("state")),
                Optional.ofNullable(rs.getObject("result_count", Integer.class)),
                Optional.ofNullable(rs.getString("error_code")),
                Optional.ofNullable(
                        rs.getObject("processed_at", java.time.OffsetDateTime.class))
                        .map(UtcTimestamp::from),
                rs.getLong("version"),
                new AuditMetadata(
                        Optional.of(new PrincipalId(
                                rs.getObject("created_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                        Optional.of(new PrincipalId(
                                rs.getObject("updated_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
    }
}
