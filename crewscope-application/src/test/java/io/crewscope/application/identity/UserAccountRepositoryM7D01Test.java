package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M7-D01 application port keeps normalized lookup internal and unique conflicts generic. */
class UserAccountRepositoryM7D01Test {

    @Test
    void repositoryUsesTypedNormalizedIdentifiersAndStableAccountIdentity() throws Exception {
        Method byId = UserAccountRepository.class.getMethod("findById", UserAccountId.class);
        Method byUsername = UserAccountRepository.class.getMethod("findByUsername", Username.class);
        Method byEmail = UserAccountRepository.class.getMethod("findByEmail", NormalizedEmail.class);

        assertEquals(Optional.class, byId.getReturnType());
        assertEquals(Optional.class, byUsername.getReturnType());
        assertEquals(Optional.class, byEmail.getReturnType());
        assertEquals(
                UserAccount.class,
                UserAccountRepository.class.getMethod("create", UserAccount.class).getReturnType());
    }

    @Test
    void accountConflictDoesNotCarryTheCollidingIdentifierOrKind() {
        AccountIdentifierConflictException conflict = new AccountIdentifierConflictException();

        assertTrue(conflict.error().details().isEmpty());
        assertFalse(conflict.getMessage().contains("@"));
        assertFalse(conflict.getMessage().toLowerCase().contains("username"));
        assertFalse(conflict.getMessage().toLowerCase().contains("email"));
    }

    @Test
    void repositoryContractDoesNotExposeCredentialOrPrincipalGraphs() {
        assertTrue(Arrays.stream(UserAccountRepository.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(type -> type.getSimpleName().contains("Credential")
                        || type.getSimpleName().contains("Principal")
                        || type.getSimpleName().contains("Session")));
    }
}
