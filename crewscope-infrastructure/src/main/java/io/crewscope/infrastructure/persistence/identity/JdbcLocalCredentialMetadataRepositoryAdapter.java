package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.LocalCredentialMetadataRepository;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.UserAccountId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Non-secret JDBC reader restricted to the V31 credential metadata projection. */
@Repository
public class JdbcLocalCredentialMetadataRepositoryAdapter
        implements LocalCredentialMetadataRepository {

    private static final String SELECT = "SELECT * FROM crewscope.local_credential_metadata";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityPersistenceMapper mapper;

    public JdbcLocalCredentialMetadataRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, IdentityPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalCredentialMetadata> findById(LocalCredentialId credentialId) {
        return first(
                SELECT + " WHERE id = :id",
                new MapSqlParameterSource(
                        "id", Objects.requireNonNull(credentialId, "credentialId").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalCredentialMetadata> findByAccountId(UserAccountId accountId) {
        return findByAccountId(accountId, false);
    }

    @Override
    @Transactional
    public Optional<LocalCredentialMetadata> findByAccountIdForUpdate(UserAccountId accountId) {
        return findByAccountId(accountId, true);
    }

    private Optional<LocalCredentialMetadata> findByAccountId(
            UserAccountId accountId, boolean lock) {
        return first(
                SELECT
                        + " WHERE account_id = :accountId"
                        + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource(
                        "accountId", Objects.requireNonNull(accountId, "accountId").value()));
    }

    private Optional<LocalCredentialMetadata> first(
            String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, ignored) -> mapper.credentialMetadata(row))
                .stream()
                .findFirst();
    }
}
