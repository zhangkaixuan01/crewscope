package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.domain.identity.AccountIdentityProviderKey;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityKey;
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

/** PostgreSQL adapter preserving provider Subject bytes and both immutable identity coordinates. */
@Repository
public class JdbcLoginIdentityRepositoryAdapter implements LoginIdentityRepository {

    private static final String SELECT = "SELECT * FROM crewscope.login_identity";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityPersistenceMapper mapper;

    public JdbcLoginIdentityRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, IdentityPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginIdentity> findById(LoginIdentityId identityId) {
        return first(
                SELECT + " WHERE id = :id",
                new MapSqlParameterSource(
                        "id", Objects.requireNonNull(identityId, "identityId").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginIdentity> findByIdentityKey(LoginIdentityKey identityKey) {
        return findByIdentityKey(identityKey, false);
    }

    @Override
    @Transactional
    public Optional<LoginIdentity> findByIdentityKeyForUpdate(LoginIdentityKey identityKey) {
        return findByIdentityKey(identityKey, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginIdentity> findByAccountProviderKey(
            AccountIdentityProviderKey accountProviderKey) {
        AccountIdentityProviderKey required =
                Objects.requireNonNull(accountProviderKey, "accountProviderKey");
        return first(
                SELECT + " WHERE account_id = :accountId AND provider = :provider",
                new MapSqlParameterSource()
                        .addValue("accountId", required.accountId().value())
                        .addValue("provider", required.provider().value()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoginIdentity> findByAccountId(UserAccountId accountId) {
        return jdbc.query(
                SELECT
                        + " WHERE account_id = :accountId"
                        + " ORDER BY provider, created_at, id",
                new MapSqlParameterSource(
                        "accountId", Objects.requireNonNull(accountId, "accountId").value()),
                (row, ignored) -> mapper.loginIdentity(row));
    }

    @Override
    @Transactional
    public LoginIdentity create(LoginIdentity identity) {
        LoginIdentity required = Objects.requireNonNull(identity, "identity");
        IdentityPersistenceSupport.requireNewVersion(required.version(), "loginIdentity.version");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.login_identity (
                        id, account_id, provider, subject, status, last_authenticated_at,
                        version, created_at, updated_at
                    ) VALUES (
                        :id, :accountId, :provider, :subject, :status, :lastAuthenticatedAt,
                        :version, :createdAt, :updatedAt
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw IdentityPersistenceSupport.identityConflict(failure);
        }
        return findById(required.id())
                .orElseThrow(() -> new AggregateNotFoundException("LoginIdentity", required.id()));
    }

    @Override
    @Transactional
    public LoginIdentity update(LoginIdentity identity, long expectedVersion) {
        LoginIdentity required = Objects.requireNonNull(identity, "identity");
        IdentityPersistenceSupport.requireNextVersion(
                required.version(), expectedVersion, "loginIdentity.version");
        int affected;
        try {
            affected = jdbc.update(
                    """
                    UPDATE crewscope.login_identity
                       SET status = :status,
                           last_authenticated_at = :lastAuthenticatedAt,
                           version = :version,
                           updated_at = :updatedAt
                     WHERE id = :id AND version = :expectedVersion
                    """,
                    parameters(required).addValue("expectedVersion", expectedVersion));
        } catch (DataIntegrityViolationException failure) {
            throw IdentityPersistenceSupport.identityConflict(failure);
        }
        if (affected == 0) {
            throwVersionConflict(required.id(), expectedVersion);
        }
        return findById(required.id())
                .orElseThrow(() -> new AggregateNotFoundException("LoginIdentity", required.id()));
    }

    private Optional<LoginIdentity> findByIdentityKey(LoginIdentityKey key, boolean lock) {
        LoginIdentityKey required = Objects.requireNonNull(key, "identityKey");
        return first(
                SELECT
                        + " WHERE provider = :provider AND subject = :subject"
                        + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource()
                        .addValue("provider", required.provider().value())
                        .addValue("subject", required.subject().value()));
    }

    private Optional<LoginIdentity> first(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, ignored) -> mapper.loginIdentity(row))
                .stream()
                .findFirst();
    }

    private void throwVersionConflict(LoginIdentityId identityId, long expectedVersion) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM crewscope.login_identity WHERE id = :id",
                new MapSqlParameterSource("id", identityId.value()),
                (row, ignored) -> row.getLong("version"));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("LoginIdentity", identityId);
        }
        throw new OptimisticLockConflictException(
                "LoginIdentity", identityId, expectedVersion, versions.get(0));
    }

    private static MapSqlParameterSource parameters(LoginIdentity identity) {
        return new MapSqlParameterSource()
                .addValue("id", identity.id().value())
                .addValue("accountId", identity.accountId().value())
                .addValue("provider", identity.provider().value())
                .addValue("subject", identity.subject().value())
                .addValue("status", identity.status().name())
                .addValue(
                        "lastAuthenticatedAt",
                        identity.lastAuthenticatedAt()
                                .map(IdentityPersistenceSupport::timestamp)
                                .orElse(null))
                .addValue("version", identity.version())
                .addValue(
                        "createdAt",
                        IdentityPersistenceSupport.timestamp(identity.lifecycle().createdAt()))
                .addValue(
                        "updatedAt",
                        IdentityPersistenceSupport.timestamp(identity.lifecycle().updatedAt()));
    }
}
