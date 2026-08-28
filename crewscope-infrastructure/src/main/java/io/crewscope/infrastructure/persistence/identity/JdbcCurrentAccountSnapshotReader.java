package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.CurrentAccountSnapshot;
import io.crewscope.application.identity.CurrentAccountSnapshotReader;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Loads the complete non-secret current-account graph with one fixed SQL query. */
@Repository
public class JdbcCurrentAccountSnapshotReader implements CurrentAccountSnapshotReader {

    private static final String SELECT = """
            SELECT account.*,
                   identity.id AS identity_id,
                   identity.account_id AS identity_account_id,
                   identity.provider AS identity_provider,
                   identity.subject AS identity_subject,
                   identity.status AS identity_status,
                   identity.last_authenticated_at AS identity_last_authenticated_at,
                   identity.version AS identity_version,
                   identity.created_at AS identity_created_at,
                   identity.updated_at AS identity_updated_at,
                   credential.id AS credential_id,
                   credential.account_id AS credential_account_id,
                   credential.algorithm AS credential_algorithm,
                   credential.credential_version AS credential_credential_version,
                   credential.password_changed_at AS credential_password_changed_at,
                   credential.version AS credential_version,
                   credential.created_at AS credential_created_at,
                   credential.updated_at AS credential_updated_at,
                   binding.id AS binding_id,
                   binding.account_id AS binding_account_id,
                   binding.organization_id AS binding_organization_id,
                   binding.principal_id AS binding_principal_id,
                   binding.status AS binding_status,
                   binding.version AS binding_version,
                   binding.created_at AS binding_created_at,
                   binding.updated_at AS binding_updated_at
              FROM crewscope.user_account account
              LEFT JOIN crewscope.login_identity identity
                ON identity.account_id = account.id
              LEFT JOIN crewscope.local_credential_metadata credential
                ON credential.account_id = account.id
              LEFT JOIN crewscope.account_organization_binding binding
                ON binding.account_id = account.id
             WHERE account.id = :accountId
             ORDER BY identity.provider NULLS LAST, identity.id NULLS LAST,
                      binding.organization_id NULLS LAST, binding.id NULLS LAST
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityPersistenceMapper mapper;

    public JdbcCurrentAccountSnapshotReader(
            NamedParameterJdbcTemplate jdbc, IdentityPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CurrentAccountSnapshot> findByAccountId(UserAccountId accountId) {
        SnapshotAccumulator accumulator = jdbc.query(
                SELECT,
                new MapSqlParameterSource(
                        "accountId", Objects.requireNonNull(accountId, "accountId").value()),
                resultSet -> {
                    SnapshotAccumulator result = null;
                    while (resultSet.next()) {
                        if (result == null) {
                            result = new SnapshotAccumulator(mapper.userAccount(resultSet));
                        }
                        if (mapper.hasValue(resultSet, "identity_id")) {
                            LoginIdentity identity =
                                    mapper.loginIdentity(resultSet, "identity_");
                            result.identities.putIfAbsent(identity.id(), identity);
                        }
                        if (mapper.hasValue(resultSet, "credential_id")) {
                            LocalCredentialMetadata credential =
                                    mapper.credentialMetadata(resultSet, "credential_");
                            result.credential = Optional.of(credential);
                        }
                        if (mapper.hasValue(resultSet, "binding_id")) {
                            AccountOrganizationBinding binding =
                                    mapper.organizationBinding(resultSet, "binding_");
                            result.bindings.putIfAbsent(binding.id(), binding);
                        }
                    }
                    return result;
                });
        return Optional.ofNullable(accumulator).map(SnapshotAccumulator::snapshot);
    }

    private static final class SnapshotAccumulator {

        private final UserAccount account;
        private final Map<LoginIdentityId, LoginIdentity> identities = new LinkedHashMap<>();
        private final Map<AccountOrganizationBindingId, AccountOrganizationBinding> bindings =
                new LinkedHashMap<>();
        private Optional<LocalCredentialMetadata> credential = Optional.empty();

        private SnapshotAccumulator(UserAccount account) {
            this.account = account;
        }

        private CurrentAccountSnapshot snapshot() {
            return new CurrentAccountSnapshot(
                    account,
                    new ArrayList<>(identities.values()),
                    credential,
                    new ArrayList<>(bindings.values()));
        }
    }
}
