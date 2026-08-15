package io.crewscope.server.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenAuthenticator;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.server.security.TaskTokenWebFilter;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Profile-neutral verification, Worker authority and key failure-closure proof. */
class TaskTokenSecurityConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TaskTokenSecurityConfiguration.class)
            .withBean(TaskCredentialGrantRepository.class,
                    () -> mock(TaskCredentialGrantRepository.class))
            .withBean(ExecutionLeaseRepository.class,
                    () -> mock(ExecutionLeaseRepository.class))
            .withBean(TaskExecutionRepository.class,
                    () -> mock(TaskExecutionRepository.class))
            .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
            .withBean(TransactionExecutor.class, DirectTransactions::new)
            .withBean(AuthoritativeTimeProvider.class,
                    () -> () -> UtcTimestamp.parse("2026-08-15T03:00:00Z"));

    @Test
    void disabledConfigurationCreatesNoTaskTokenBoundary() {
        runner.withPropertyValues("crewscope.security.task-token.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TaskTokenCodec.class);
                    assertThat(context).doesNotHaveBean(TaskTokenWebFilter.class);
                });
    }

    @Test
    void serverProfileCreatesVerifierWithoutWorkerIssuanceAuthority() {
        runner.withPropertyValues(validProperties("server"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TaskTokenCodec.class);
                    assertThat(context).hasSingleBean(TaskTokenAuthenticator.class);
                    assertThat(context).hasSingleBean(TaskTokenWebFilter.class);
                    assertThat(context).doesNotHaveBean(TaskTokenService.class);
                });
    }

    @Test
    void allProfileCreatesIssuanceAuthority() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(), PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE, Optional.empty(), "Runtime", Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                UtcTimestamp.parse("2026-08-15T03:00:00Z"));
        RuntimeWorkerRegistrationSpec registration = mock(RuntimeWorkerRegistrationSpec.class);
        when(registration.organizationId()).thenReturn(organizationId);
        when(registration.environment()).thenReturn(new RuntimeEnvironment("test"));
        when(registration.actor()).thenReturn(actor);

        runner.withBean(RuntimeWorkerRegistrationSpec.class, () -> registration)
                .withBean(PolicySnapshotRepository.class,
                        () -> mock(PolicySnapshotRepository.class))
                .withBean(SafetyEnforcementOverlayRepository.class,
                        () -> mock(SafetyEnforcementOverlayRepository.class))
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(ConnectionGrantRepository.class,
                        () -> mock(ConnectionGrantRepository.class))
                .withPropertyValues(validProperties("all"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TaskTokenService.class);
                });
    }

    @Test
    void enabledConfigurationRejectsWeakKeysAtStartup() {
        runner.withPropertyValues(
                        "crewscope.runtime.execution-profile=server",
                        "crewscope.security.task-token.enabled=true",
                        "crewscope.security.task-token.current-key-id=v1",
                        "crewscope.security.task-token.keys.v1="
                                + Base64.getEncoder().encodeToString(new byte[16]))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] validProperties(String profile) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 3);
        return new String[] {
            "crewscope.runtime.execution-profile=" + profile,
            "crewscope.security.task-token.enabled=true",
            "crewscope.security.task-token.issuer=crewscope",
            "crewscope.security.task-token.current-key-id=v1",
            "crewscope.security.task-token.keys.v1="
                    + Base64.getEncoder().encodeToString(key)
        };
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
