package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.CurrentAccountApplicationService;
import io.crewscope.application.identity.LocalAccountLoginService;
import io.crewscope.application.identity.LocalAccountRegistrationService;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.OnboardingApplicationService;
import io.crewscope.application.team.TeamInvitationApplicationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.api.AuthenticationController;
import io.crewscope.server.api.CurrentAccountController;
import io.crewscope.server.api.OnboardingController;
import io.crewscope.server.api.RegistrationController;
import io.crewscope.server.api.TeamInvitationController;
import io.crewscope.server.api.TeamRequestIdentityResolver;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spring Context cardinality and Jackson-major-version isolation contract for M7-A07. */
class M7ApiCompositionM7A07Test {

    private final ApplicationContextRunner controllers = new ApplicationContextRunner()
            .withUserConfiguration(
                    AuthenticationController.class,
                    RegistrationController.class,
                    CurrentAccountController.class,
                    OnboardingController.class,
                    TeamInvitationController.class)
            .withBean(
                    LocalAccountRegistrationService.class,
                    () -> mock(LocalAccountRegistrationService.class))
            .withBean(LocalAccountLoginService.class, () -> mock(LocalAccountLoginService.class))
            .withBean(
                    CurrentAccountApplicationService.class,
                    () -> mock(CurrentAccountApplicationService.class))
            .withBean(
                    OnboardingApplicationService.class,
                    () -> mock(OnboardingApplicationService.class))
            .withBean(LoginDefense.class, () -> mock(LoginDefense.class))
            .withBean(
                    ControlledNetworkSourceResolver.class,
                    () -> mock(ControlledNetworkSourceResolver.class))
            .withBean(BrowserSessionLifecycle.class, () -> mock(BrowserSessionLifecycle.class))
            .withBean(
                    AuthenticatedAccountOrganizationResolver.class,
                    () -> mock(AuthenticatedAccountOrganizationResolver.class))
            .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
            .withBean(TeamMemberRepository.class, () -> mock(TeamMemberRepository.class))
            .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
            .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
            .withBean(
                    TeamRequestIdentityResolver.class,
                    () -> mock(TeamRequestIdentityResolver.class))
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
            .withBean(RegistrationProperties.class, M7ApiCompositionM7A07Test::registration);

    @Test
    void createsEachM7ControllerAndApplicationServiceExactlyOnce() {
        controllers
                .withPropertyValues("crewscope.invitation.token.enabled=true")
                .withBean(
                        TeamInvitationApplicationService.class,
                        () -> mock(TeamInvitationApplicationService.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(AuthenticationController.class)
                        .hasSingleBean(RegistrationController.class)
                        .hasSingleBean(CurrentAccountController.class)
                        .hasSingleBean(OnboardingController.class)
                        .hasSingleBean(TeamInvitationController.class)
                        .hasSingleBean(LocalAccountRegistrationService.class)
                        .hasSingleBean(LocalAccountLoginService.class)
                        .hasSingleBean(CurrentAccountApplicationService.class)
                        .hasSingleBean(OnboardingApplicationService.class)
                        .hasSingleBean(TeamInvitationApplicationService.class));
    }

    @Test
    void omitsInvitationHttpSurfaceWhenItsApplicationServiceIsUnavailable() {
        controllers
            .withPropertyValues("crewscope.invitation.token.enabled=false")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(TeamInvitationController.class));
    }

    @Test
    void publishesOneJackson3WebMapperAndOneJackson2AgentScopeMapper() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(LegacyJacksonConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(tools.jackson.databind.ObjectMapper.class);
                    assertThat(context)
                            .hasSingleBean(com.fasterxml.jackson.databind.ObjectMapper.class);
                    assertThat(context.getBean(tools.jackson.databind.ObjectMapper.class))
                            .isNotInstanceOf(com.fasterxml.jackson.databind.ObjectMapper.class);
                });
    }

    private static RegistrationProperties registration() {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setMode(RegistrationMode.OPEN);
        properties.setOrganizationId(OrganizationId.generate().toString());
        return properties;
    }
}
