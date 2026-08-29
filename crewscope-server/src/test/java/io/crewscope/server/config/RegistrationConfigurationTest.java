package io.crewscope.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.domain.identity.RegistrationMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies the closed registration policy and its safe local default. */
class RegistrationConfigurationTest {

  @Test
  void bindsEverySupportedMode() {
    for (RegistrationMode mode : RegistrationMode.values()) {
      runner(mode.name())
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(RegistrationProperties.class).getMode()).isEqualTo(mode);
              });
    }
    assertThat(List.of(RegistrationMode.values()))
        .containsExactly(
            RegistrationMode.OPEN, RegistrationMode.INVITE_ONLY, RegistrationMode.DISABLED);
  }

  @Test
  void defaultsLocalDevelopmentToOpen() {
    new ApplicationContextRunner()
        .withUserConfiguration(RegistrationConfiguration.class)
        .run(context -> assertThat(context.getBean(RegistrationProperties.class).getMode())
            .isEqualTo(RegistrationMode.OPEN));
  }

  @Test
  void rejectsAnUnknownModeDuringBinding() {
    runner("PUBLIC").run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsANonUuidRegistrationOrganizationAtStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(RegistrationConfiguration.class)
        .withPropertyValues("crewscope.registration.organization-id=not-an-organization")
        .run(context -> assertThat(context).hasFailed());
  }

  private static ApplicationContextRunner runner(String mode) {
    return new ApplicationContextRunner()
        .withUserConfiguration(RegistrationConfiguration.class)
        .withPropertyValues("crewscope.registration.mode=" + mode);
  }
}
