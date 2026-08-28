package io.crewscope.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.BootstrapOperatorLock;
import io.crewscope.application.identity.BootstrapOperatorPasswordHasher;
import io.crewscope.application.identity.BootstrapOperatorProvisioningService;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that Bootstrap Operator startup is opt-in and validates its deployment coordinates. */
class BootstrapOperatorConfigurationTest {

  private static final String ORGANIZATION_ID = "d3ff4c9c-7a93-4fc7-91ac-4e3f328acdea";

  @Test
  void remainsDisabledByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(BootstrapOperatorConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(BootstrapOperatorProvisioningService.class);
              assertThat(context).doesNotHaveBean(ApplicationRunner.class);
            });
  }

  @Test
  void wiresOneProvisioningServiceAndOneStartupRunnerWhenEnabled() {
    enabledRunner()
        .withPropertyValues(
            "crewscope.security.operator-bootstrap.organization-id=" + ORGANIZATION_ID,
            "crewscope.security.bootstrap.password=M7-I07-bootstrap-secret-47")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(BootstrapOperatorProvisioningService.class);
              assertThat(context).hasSingleBean(ApplicationRunner.class);
            });
  }

  @Test
  void failsClosedWhenEnabledWithoutAnOrganization() {
    enabledRunner()
        .withPropertyValues(
            "crewscope.security.bootstrap.password=M7-I07-bootstrap-secret-47")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsClosedWhenEnabledWithoutAnExternalPassword() {
    enabledRunner()
        .withPropertyValues(
            "crewscope.security.operator-bootstrap.organization-id=" + ORGANIZATION_ID)
        .run(context -> assertThat(context).hasFailed());
  }

  private static ApplicationContextRunner enabledRunner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(BootstrapOperatorConfiguration.class)
        .withPropertyValues("crewscope.security.operator-bootstrap.enabled=true")
        // Startup assembly is tested independently from repository and crypto implementations.
        .withBean(BootstrapOperatorLock.class, () -> mock(BootstrapOperatorLock.class))
        .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
        .withBean(UserAccountRepository.class, () -> mock(UserAccountRepository.class))
        .withBean(LoginIdentityRepository.class, () -> mock(LoginIdentityRepository.class))
        .withBean(LocalCredentialStore.class, () -> mock(LocalCredentialStore.class))
        .withBean(
            AccountOrganizationBindingRepository.class,
            () -> mock(AccountOrganizationBindingRepository.class))
        .withBean(
            BootstrapOperatorPasswordHasher.class,
            () -> mock(BootstrapOperatorPasswordHasher.class))
        .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
        .withBean(TimeProvider.class, () -> mock(TimeProvider.class));
  }
}
