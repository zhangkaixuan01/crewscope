package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationBindingStatus;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalCredentialVersion;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityStatus;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.PasswordHashAlgorithm;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reconstitutes M7 account facts while independently verifying every derived canonical key. */
@Component
public final class IdentityPersistenceMapper {

    public UserAccount userAccount(ResultSet row) throws SQLException {
        Username username = new Username(row.getString("username"));
        requireDerivedKey(
                "userAccount.usernameNormalized",
                username.normalizedValue(),
                row.getString("username_normalized"));
        NormalizedEmail email = NormalizedEmail.fromDisplayValue(row.getString("email"));
        requireDerivedKey(
                "userAccount.emailNormalized", email.value(), row.getString("email_normalized"));
        return UserAccount.reconstitute(
                new UserAccountId(uuid(row, "id")),
                username,
                row.getString("email"),
                email,
                row.getString("display_name"),
                AccountStatus.valueOf(row.getString("status")),
                PlatformRole.valueOf(row.getString("platform_role")),
                new SecurityVersion(row.getLong("security_version")),
                row.getLong("version"),
                lifecycle(row, ""));
    }

    public LoginIdentity loginIdentity(ResultSet row) throws SQLException {
        return loginIdentity(row, "");
    }

    public LoginIdentity loginIdentity(ResultSet row, String prefix) throws SQLException {
        return LoginIdentity.reconstitute(
                new LoginIdentityId(uuid(row, prefix + "id")),
                new UserAccountId(uuid(row, prefix + "account_id")),
                new IdentityProviderKey(row.getString(prefix + "provider")),
                new LoginIdentitySubject(row.getString(prefix + "subject")),
                LoginIdentityStatus.valueOf(row.getString(prefix + "status")),
                optionalTime(row, prefix + "last_authenticated_at"),
                row.getLong(prefix + "version"),
                lifecycle(row, prefix));
    }

    public LocalCredentialMetadata credentialMetadata(ResultSet row) throws SQLException {
        return credentialMetadata(row, "");
    }

    public LocalCredentialMetadata credentialMetadata(ResultSet row, String prefix)
            throws SQLException {
        return LocalCredentialMetadata.reconstitute(
                new LocalCredentialId(uuid(row, prefix + "id")),
                new UserAccountId(uuid(row, prefix + "account_id")),
                passwordAlgorithm(row.getString(prefix + "algorithm")),
                new LocalCredentialVersion(row.getLong(prefix + "credential_version")),
                time(row, prefix + "password_changed_at"),
                row.getLong(prefix + "version"),
                lifecycle(row, prefix));
    }

    public AccountOrganizationBinding organizationBinding(ResultSet row) throws SQLException {
        return organizationBinding(row, "");
    }

    public AccountOrganizationBinding organizationBinding(ResultSet row, String prefix)
            throws SQLException {
        return AccountOrganizationBinding.reconstitute(
                new AccountOrganizationBindingId(uuid(row, prefix + "id")),
                new UserAccountId(uuid(row, prefix + "account_id")),
                new OrganizationId(uuid(row, prefix + "organization_id")),
                new PrincipalId(uuid(row, prefix + "principal_id")),
                AccountOrganizationBindingStatus.valueOf(row.getString(prefix + "status")),
                row.getLong(prefix + "version"),
                lifecycle(row, prefix));
    }

    public boolean hasValue(ResultSet row, String column) throws SQLException {
        return row.getObject(column) != null;
    }

    private static LifecycleMetadata lifecycle(ResultSet row, String prefix) throws SQLException {
        return new LifecycleMetadata(
                time(row, prefix + "created_at"), time(row, prefix + "updated_at"));
    }

    private static UtcTimestamp time(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static Optional<UtcTimestamp> optionalTime(ResultSet row, String column)
            throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class))
                .map(UtcTimestamp::from);
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static PasswordHashAlgorithm passwordAlgorithm(String value) {
        return switch (value) {
            case "argon2id" -> PasswordHashAlgorithm.ARGON2ID;
            case "bcrypt" -> PasswordHashAlgorithm.BCRYPT;
            default -> throw new DomainValidationException(
                    "localCredential.algorithm", "contains an unsupported persisted value");
        };
    }

    private static void requireDerivedKey(String field, String derived, String persisted) {
        if (!derived.equals(persisted)) {
            throw new DomainValidationException(field, "does not match the domain-derived key");
        }
    }
}
