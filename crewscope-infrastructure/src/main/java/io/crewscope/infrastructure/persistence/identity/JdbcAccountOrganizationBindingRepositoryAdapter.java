package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationKey;
import io.crewscope.domain.identity.OrganizationPrincipalKey;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authorization-bridge adapter scoped by both Account/Organization unique keys. */
@Repository
public class JdbcAccountOrganizationBindingRepositoryAdapter
        implements AccountOrganizationBindingRepository {

    private static final String SELECT = "SELECT * FROM crewscope.account_organization_binding";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityPersistenceMapper mapper;

    public JdbcAccountOrganizationBindingRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, IdentityPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountOrganizationBinding> findById(
            AccountOrganizationBindingId bindingId) {
        return first(
                SELECT + " WHERE id = :id",
                new MapSqlParameterSource(
                        "id", Objects.requireNonNull(bindingId, "bindingId").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountOrganizationBinding> findByAccountOrganizationKey(
            AccountOrganizationKey key) {
        return findByAccountOrganizationKey(key, false);
    }

    @Override
    @Transactional
    public Optional<AccountOrganizationBinding> findByAccountOrganizationKeyForUpdate(
            AccountOrganizationKey key) {
        return findByAccountOrganizationKey(key, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountOrganizationBinding> findByOrganizationPrincipalKey(
            OrganizationPrincipalKey key) {
        OrganizationPrincipalKey required = Objects.requireNonNull(key, "key");
        return first(
                SELECT
                        + " WHERE organization_id = :organizationId"
                        + " AND principal_id = :principalId",
                new MapSqlParameterSource()
                        .addValue("organizationId", required.organizationId().value())
                        .addValue("principalId", required.principalId().value()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountOrganizationBinding> findByAccountId(UserAccountId accountId) {
        return jdbc.query(
                SELECT
                        + " WHERE account_id = :accountId"
                        + " ORDER BY organization_id, created_at, id",
                new MapSqlParameterSource(
                        "accountId", Objects.requireNonNull(accountId, "accountId").value()),
                (row, ignored) -> mapper.organizationBinding(row));
    }

    @Override
    @Transactional
    public AccountOrganizationBinding create(AccountOrganizationBinding binding) {
        AccountOrganizationBinding required = Objects.requireNonNull(binding, "binding");
        IdentityPersistenceSupport.requireNewVersion(
                required.version(), "accountOrganizationBinding.version");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.account_organization_binding (
                        id, account_id, organization_id, principal_id, status, version,
                        created_at, updated_at
                    ) VALUES (
                        :id, :accountId, :organizationId, :principalId, :status, :version,
                        :createdAt, :updatedAt
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw IdentityPersistenceSupport.bindingConflict(failure);
        }
        return findById(required.id()).orElseThrow(
                () -> new AggregateNotFoundException("AccountOrganizationBinding", required.id()));
    }

    @Override
    @Transactional
    public AccountOrganizationBinding update(
            AccountOrganizationBinding binding, long expectedVersion) {
        AccountOrganizationBinding required = Objects.requireNonNull(binding, "binding");
        IdentityPersistenceSupport.requireNextVersion(
                required.version(), expectedVersion, "accountOrganizationBinding.version");
        int affected;
        try {
            affected = jdbc.update(
                    """
                    UPDATE crewscope.account_organization_binding
                       SET status = :status,
                           version = :version,
                           updated_at = :updatedAt
                     WHERE id = :id AND version = :expectedVersion
                    """,
                    parameters(required).addValue("expectedVersion", expectedVersion));
        } catch (DataIntegrityViolationException failure) {
            throw IdentityPersistenceSupport.bindingConflict(failure);
        }
        if (affected == 0) {
            throwVersionConflict(required.id(), expectedVersion);
        }
        return findById(required.id()).orElseThrow(
                () -> new AggregateNotFoundException("AccountOrganizationBinding", required.id()));
    }

    private Optional<AccountOrganizationBinding> findByAccountOrganizationKey(
            AccountOrganizationKey key, boolean lock) {
        AccountOrganizationKey required = Objects.requireNonNull(key, "key");
        return first(
                SELECT
                        + " WHERE account_id = :accountId AND organization_id = :organizationId"
                        + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource()
                        .addValue("accountId", required.accountId().value())
                        .addValue("organizationId", required.organizationId().value()));
    }

    private Optional<AccountOrganizationBinding> first(
            String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, ignored) -> mapper.organizationBinding(row))
                .stream()
                .findFirst();
    }

    private void throwVersionConflict(
            AccountOrganizationBindingId bindingId, long expectedVersion) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM crewscope.account_organization_binding WHERE id = :id",
                new MapSqlParameterSource("id", bindingId.value()),
                (row, ignored) -> row.getLong("version"));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("AccountOrganizationBinding", bindingId);
        }
        throw new OptimisticLockConflictException(
                "AccountOrganizationBinding", bindingId, expectedVersion, versions.get(0));
    }

    private static MapSqlParameterSource parameters(AccountOrganizationBinding binding) {
        return new MapSqlParameterSource()
                .addValue("id", binding.id().value())
                .addValue("accountId", binding.accountId().value())
                .addValue("organizationId", binding.organizationId().value())
                .addValue("principalId", binding.principalId().value())
                .addValue("status", binding.status().name())
                .addValue("version", binding.version())
                .addValue(
                        "createdAt",
                        IdentityPersistenceSupport.timestamp(binding.lifecycle().createdAt()))
                .addValue(
                        "updatedAt",
                        IdentityPersistenceSupport.timestamp(binding.lifecycle().updatedAt()));
    }
}
