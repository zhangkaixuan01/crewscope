package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.collaboration.LarkCollaborationApplicationService;
import io.crewscope.application.collaboration.LarkConnectionAuthorizationResolver;
import io.crewscope.application.collaboration.LarkExternalTenantRepository;
import io.crewscope.application.collaboration.LarkIdentityVerificationPort;
import io.crewscope.application.collaboration.LarkMappingAdministration;
import io.crewscope.application.collaboration.LarkMemberMappingApplicationService;
import io.crewscope.application.collaboration.LarkMemberMappingRepository;
import io.crewscope.application.collaboration.LarkMemberVerificationProofRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.notification.FixedNotificationTemplateRenderer;
import io.crewscope.application.notification.NotificationCredentialIssuer;
import io.crewscope.application.notification.NotificationProviderPort;
import io.crewscope.application.notification.NotificationTemplateCatalog;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.integration.provider.collaboration.LarkCollaborationProvider;
import io.crewscope.integration.provider.collaboration.LarkCredentialAccessManager;
import io.crewscope.integration.provider.collaboration.LarkOpenApiClient;
import io.crewscope.integration.provider.collaboration.LarkTenantTokenCache;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Spring composition and fail-closed configuration tests for M6-I04 and M6-I05. */
class LarkConnectorApplicationConfigurationM6I04Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LarkConnectorApplicationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
            .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
            .withBean(ConnectionGrantRepository.class,
                    () -> mock(ConnectionGrantRepository.class))
            .withBean(CredentialStore.class, () -> mock(CredentialStore.class));

    @Test
    void wiresTheFixedProductionConnectorWithConstructorInjectedDependencies() {
        runner.run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(LarkCollaborationProvider.class)
                    .hasSingleBean(LarkCredentialAccessManager.class)
                    .hasSingleBean(LarkTenantTokenCache.class)
                    .hasSingleBean(LarkOpenApiClient.class);
            assertThat(context.getBean(LarkOpenApiClient.class).safeSummary())
                    .doesNotContain("open.feishu.cn", "Bearer", "credential");
        });
    }

    @Test
    void leavesTheNetworkClientAbsentWhenAuthorizationStoresAreUnavailable() {
        new ApplicationContextRunner()
                .withUserConfiguration(LarkConnectorApplicationConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(LarkCollaborationProvider.class)
                        .hasSingleBean(LarkTenantTokenCache.class)
                        .doesNotHaveBean(LarkCredentialAccessManager.class)
                        .doesNotHaveBean(LarkOpenApiClient.class));
    }

    @Test
    void wiresI05AuthorizationAdministrationProviderAndMappingServicesConditionally() {
        runner.withBean(ProviderBindingResolver.class, () -> mock(ProviderBindingResolver.class))
                .withBean(TeamMemberRepository.class, () -> mock(TeamMemberRepository.class))
                .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
                .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
                .withBean(LarkExternalTenantRepository.class,
                        () -> mock(LarkExternalTenantRepository.class))
                .withBean(LarkMemberVerificationProofRepository.class,
                        () -> mock(LarkMemberVerificationProofRepository.class))
                .withBean(LarkMemberMappingRepository.class,
                        () -> mock(LarkMemberMappingRepository.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(LarkConnectionAuthorizationResolver.class)
                        .hasSingleBean(LarkMappingAdministration.class)
                        .hasSingleBean(LarkIdentityVerificationPort.class)
                        .hasSingleBean(LarkCollaborationApplicationService.class)
                        .hasSingleBean(LarkMemberMappingApplicationService.class));
    }

    @Test
    void wiresI06FixedTemplateCredentialAndNotificationProviderConditionally() {
        runner.withBean(ProviderBindingResolver.class, () -> mock(ProviderBindingResolver.class))
                .withBean(NotificationTemplateCatalog.class,
                        () -> mock(NotificationTemplateCatalog.class))
                .withBean(TeamMemberRepository.class, () -> mock(TeamMemberRepository.class))
                .withBean(LarkExternalTenantRepository.class,
                        () -> mock(LarkExternalTenantRepository.class))
                .withBean(LarkMemberMappingRepository.class,
                        () -> mock(LarkMemberMappingRepository.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(FixedNotificationTemplateRenderer.class)
                        .hasSingleBean(NotificationCredentialIssuer.class)
                        .hasSingleBean(NotificationProviderPort.class));
    }

    @Test
    void rejectsAnUnboundedMemberConfirmationWindowWhenMappingRuntimeIsEnabled() {
        runner.withBean(ProviderBindingResolver.class, () -> mock(ProviderBindingResolver.class))
                .withBean(TeamMemberRepository.class, () -> mock(TeamMemberRepository.class))
                .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
                .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
                .withBean(LarkExternalTenantRepository.class,
                        () -> mock(LarkExternalTenantRepository.class))
                .withBean(LarkMemberVerificationProofRepository.class,
                        () -> mock(LarkMemberVerificationProofRepository.class))
                .withBean(LarkMemberMappingRepository.class,
                        () -> mock(LarkMemberMappingRepository.class))
                .withPropertyValues("crewscope.provider.lark.member-confirmation-window=16m")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void allowsHttpOnlyForAnExplicitLiteralLoopbackTestOrigin() {
        runner.withPropertyValues(
                        "crewscope.provider.lark.base-uri=http://127.0.0.1:18080",
                        "crewscope.provider.lark.allow-loopback-http=true")
                .run(context -> context.assertThat().hasNotFailed());
        runner.withPropertyValues(
                        "crewscope.provider.lark.base-uri=http://127.0.0.1:18080",
                        "crewscope.provider.lark.allow-loopback-http=false")
                .run(context -> context.assertThat().hasFailed());
        runner.withPropertyValues(
                        "crewscope.provider.lark.base-uri=http://localhost:18080",
                        "crewscope.provider.lark.allow-loopback-http=true")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void rejectsUnsafeTimeoutCacheAndResponseBounds() {
        runner.withPropertyValues("crewscope.provider.lark.request-timeout=3m")
                .run(context -> context.assertThat().hasFailed());
        runner.withPropertyValues("crewscope.provider.lark.token-expiry-safety-margin=30s")
                .run(context -> context.assertThat().hasFailed());
        runner.withPropertyValues("crewscope.provider.lark.maximum-cached-tokens=10001")
                .run(context -> context.assertThat().hasFailed());
        runner.withPropertyValues("crewscope.provider.lark.maximum-response-bytes=1023")
                .run(context -> context.assertThat().hasFailed());
    }
}
