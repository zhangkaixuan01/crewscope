package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.AccountOrganizationBindingConflictException;
import io.crewscope.domain.identity.LoginIdentityConflictException;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/** Shared fail-closed guards and safe conflict translation for M7 identity persistence. */
final class IdentityPersistenceSupport {

    private IdentityPersistenceSupport() {}

    static void requireNewVersion(long version, String field) {
        if (version != 0) {
            throw new DomainValidationException(field, "must be zero when created");
        }
    }

    static void requireNextVersion(long actual, long expected, String field) {
        if (expected < 0 || expected == Long.MAX_VALUE || actual != expected + 1) {
            throw new DomainValidationException(
                    field, "must be exactly one greater than expectedVersion");
        }
    }

    static OffsetDateTime timestamp(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }

    static RuntimeException accountConflict(
            DataIntegrityViolationException failure) {
        if (hasConstraint(failure, "uk_user_account_username_normalized")
                || hasConstraint(failure, "uk_user_account_email_normalized")) {
            return redacted(new AccountIdentifierConflictException());
        }
        return failure;
    }

    static RuntimeException identityConflict(
            DataIntegrityViolationException failure) {
        if (hasConstraint(failure, "uk_login_identity_provider_subject")
                || hasConstraint(failure, "uk_login_identity_account_provider")) {
            return redacted(new LoginIdentityConflictException());
        }
        return failure;
    }

    static RuntimeException bindingConflict(
            DataIntegrityViolationException failure) {
        if (hasConstraint(failure, "uk_account_organization_binding_account")
                || hasConstraint(failure, "uk_account_organization_binding_principal")
                || hasConstraint(failure, "fk_account_organization_binding_account")
                || hasConstraint(failure, "fk_account_organization_binding_organization")
                || hasConstraint(failure, "fk_account_organization_binding_principal")
                || hasMessage(
                        failure,
                        "accountorganizationbinding requires an organization user principal")) {
            return redacted(new AccountOrganizationBindingConflictException());
        }
        return failure;
    }

    /** Raw PostgreSQL unique-key messages may contain identity values and are never attached. */
    private static <T extends DomainException> T redacted(T conflict) {
        return conflict;
    }

    /** Spring preserves PostgreSQL SQLState and constraint name in the translated cause chain. */
    private static boolean hasConstraint(Throwable failure, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("23503".equals(sql.getSQLState()) || "23505".equals(sql.getSQLState()))
                    && contains(current.getMessage(), expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMessage(Throwable failure, String expectedText) {
        String expected = expectedText.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && "23514".equals(sql.getSQLState())
                    && contains(current.getMessage(), expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String expected) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(expected);
    }
}
