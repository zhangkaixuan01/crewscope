package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
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

/** PostgreSQL account adapter; canonical identifiers are always derived by domain value objects. */
@Repository
public class JdbcUserAccountRepositoryAdapter implements UserAccountRepository {

    private static final String SELECT = "SELECT * FROM crewscope.user_account";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityPersistenceMapper mapper;

    public JdbcUserAccountRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, IdentityPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UserAccountId accountId) {
        return first(
                SELECT + " WHERE id = :id",
                new MapSqlParameterSource("id", required(accountId).value()));
    }

    @Override
    @Transactional
    public Optional<UserAccount> findByIdForUpdate(UserAccountId accountId) {
        return first(
                SELECT + " WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", required(accountId).value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(Username username) {
        Username required = Objects.requireNonNull(username, "username");
        return first(
                SELECT + " WHERE username_normalized = :normalized",
                new MapSqlParameterSource("normalized", required.normalizedValue()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByEmail(NormalizedEmail email) {
        return first(
                SELECT + " WHERE email_normalized = :normalized",
                new MapSqlParameterSource(
                        "normalized", Objects.requireNonNull(email, "email").value()));
    }

    @Override
    @Transactional
    public UserAccount create(UserAccount account) {
        UserAccount required = Objects.requireNonNull(account, "account");
        IdentityPersistenceSupport.requireNewVersion(required.version(), "userAccount.version");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.user_account (
                        id, username, username_normalized, email, email_normalized, display_name,
                        status, platform_role, security_version, version, created_at, updated_at
                    ) VALUES (
                        :id, :username, :usernameNormalized, :email, :emailNormalized, :displayName,
                        :status, :platformRole, :securityVersion, :version, :createdAt, :updatedAt
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw IdentityPersistenceSupport.accountConflict(failure);
        }
        return findById(required.id())
                .orElseThrow(() -> new AggregateNotFoundException("UserAccount", required.id()));
    }

    @Override
    @Transactional
    public UserAccount update(UserAccount account, long expectedVersion) {
        UserAccount required = Objects.requireNonNull(account, "account");
        IdentityPersistenceSupport.requireNextVersion(
                required.version(), expectedVersion, "userAccount.version");
        int affected;
        try {
            affected = jdbc.update(
                    """
                    UPDATE crewscope.user_account
                       SET username = :username,
                           username_normalized = :usernameNormalized,
                           email = :email,
                           email_normalized = :emailNormalized,
                           display_name = :displayName,
                           status = :status,
                           security_version = :securityVersion,
                           version = :version,
                           updated_at = :updatedAt
                     WHERE id = :id AND version = :expectedVersion
                    """,
                    parameters(required).addValue("expectedVersion", expectedVersion));
        } catch (DataIntegrityViolationException failure) {
            throw IdentityPersistenceSupport.accountConflict(failure);
        }
        if (affected == 0) {
            throwVersionConflict(required.id(), expectedVersion);
        }
        return findById(required.id())
                .orElseThrow(() -> new AggregateNotFoundException("UserAccount", required.id()));
    }

    private Optional<UserAccount> first(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, ignored) -> mapper.userAccount(row))
                .stream()
                .findFirst();
    }

    private void throwVersionConflict(UserAccountId accountId, long expectedVersion) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM crewscope.user_account WHERE id = :id",
                new MapSqlParameterSource("id", accountId.value()),
                (row, ignored) -> row.getLong("version"));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("UserAccount", accountId);
        }
        throw new OptimisticLockConflictException(
                "UserAccount", accountId, expectedVersion, versions.get(0));
    }

    private static MapSqlParameterSource parameters(UserAccount account) {
        return new MapSqlParameterSource()
                .addValue("id", account.id().value())
                .addValue("username", account.username().displayValue())
                .addValue("usernameNormalized", account.username().normalizedValue())
                .addValue("email", account.email())
                .addValue("emailNormalized", account.normalizedEmail().value())
                .addValue("displayName", account.displayName())
                .addValue("status", account.status().name())
                .addValue("platformRole", account.platformRole().name())
                .addValue("securityVersion", account.securityVersion().value())
                .addValue("version", account.version())
                .addValue(
                        "createdAt",
                        IdentityPersistenceSupport.timestamp(account.lifecycle().createdAt()))
                .addValue(
                        "updatedAt",
                        IdentityPersistenceSupport.timestamp(account.lifecycle().updatedAt()));
    }

    private static UserAccountId required(UserAccountId value) {
        return Objects.requireNonNull(value, "accountId");
    }
}
