package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.collaboration.LarkAdministrationCommandService;
import io.crewscope.application.collaboration.LarkCollaborationApplicationService;
import io.crewscope.application.collaboration.LarkConnectionApplicationService;
import io.crewscope.application.collaboration.LarkConnectionAuthorizationResolver;
import io.crewscope.application.collaboration.LarkExternalTenantRepository;
import io.crewscope.application.collaboration.LarkIdentityVerificationPort;
import io.crewscope.application.collaboration.LarkMappingAdministration;
import io.crewscope.application.collaboration.LarkMemberMappingApplicationService;
import io.crewscope.application.collaboration.LarkMemberMappingRepository;
import io.crewscope.application.collaboration.LarkMemberVerificationProofRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.notification.NotificationAdministrationRepository;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.NotificationAuthorizationFactsResolver;
import io.crewscope.application.notification.NotificationCredentialIssuer;
import io.crewscope.application.notification.NotificationPlanRepository;
import io.crewscope.application.notification.NotificationProviderPort;
import io.crewscope.application.notification.NotificationRecipientAuthorization;
import io.crewscope.application.notification.NotificationTemplateCatalog;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.integration.provider.collaboration.LarkCollaborationProvider;
import io.crewscope.integration.provider.collaboration.LarkCredentialAccessManager;
import io.crewscope.integration.provider.collaboration.LarkOpenApiClient;
import io.crewscope.integration.provider.collaboration.LarkTenantTokenCache;
import io.crewscope.server.api.LarkMappingCursorCodec;
import io.crewscope.server.api.NotificationDeliveryCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Spring production composition and fail-closed configuration tests for the Lark connector. */
class LarkConnectorApplicationConfigurationM6I04Test {

    @Test
    void wiresTheCompleteProductionCompositionWithoutBeanRegistrationOrderDependencies() {
        fullRunner().run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(LarkCollaborationProvider.class)
                    .hasSingleBean(LarkCredentialAccessManager.class)
                    .hasSingleBean(LarkTenantTokenCache.class)
                    .hasSingleBean(LarkOpenApiClient.class)
                    .hasSingleBean(LarkConnectionAuthorizationResolver.class)
                    .hasSingleBean(LarkMappingAdministration.class)
                    .hasSingleBean(LarkIdentityVerificationPort.class)
                    .hasSingleBean(LarkCollaborationApplicationService.class)
                    .hasSingleBean(LarkMemberMappingApplicationService.class)
                    .hasSingleBean(NotificationCredentialIssuer.class)
                    .hasSingleBean(NotificationProviderPort.class)
                    .hasSingleBean(NotificationRecipientAuthorization.class)
                    .hasSingleBean(NotificationAdministrationService.class)
                    .hasSingleBean(LarkConnectionApplicationService.class)
                    .hasSingleBean(LarkAdministrationCommandService.class)
                    .hasSingleBean(LarkMappingCursorCodec.class)
                    .hasSingleBean(NotificationDeliveryCursorCodec.class);
            assertThat(context.getBean(LarkOpenApiClient.class).safeSummary())
                    .doesNotContain("open.feishu.cn", "Bearer", "credential");
        });
    }

    @Test
    void failsClosedWhenMandatoryProductionDependenciesAreUnavailable() {
        new ApplicationContextRunner()
                .withUserConfiguration(LarkConnectorApplicationConfiguration.class)
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void rejectsAnUnboundedMemberConfirmationWindow() {
        fullRunner()
                .withPropertyValues("crewscope.provider.lark.member-confirmation-window=16m")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void allowsHttpOnlyForAnExplicitLiteralLoopbackTestOrigin() {
        fullRunner().withPropertyValues(
                        "crewscope.provider.lark.base-uri=http://127.0.0.1:18080",
                        "crewscope.provider.lark.allow-loopback-http=true")
                .run(context -> context.assertThat().hasNotFailed());
        fullRunner().withPropertyValues(
                        "crewscope.provider.lark.base-uri=http://127.0.0.1:18080",
                        "crewscope.provider.lark.allow-loopback-http=false")
                .run(context -> context.assertThat().hasFailed());
        fullRunner().withPropertyValues(
                        "crewscope.provider.lark.base-uri=http://localhost:18080",
                        "crewscope.provider.lark.allow-loopback-http=true")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void rejectsUnsafeTimeoutCacheAndResponseBounds() {
        fullRunner().withPropertyValues("crewscope.provider.lark.request-timeout=3m")
                .run(context -> context.assertThat().hasFailed());
        fullRunner().withPropertyValues("crewscope.provider.lark.token-expiry-safety-margin=30s")
                .run(context -> context.assertThat().hasFailed());
        fullRunner().withPropertyValues("crewscope.provider.lark.maximum-cached-tokens=10001")
                .run(context -> context.assertThat().hasFailed());
        fullRunner().withPropertyValues("crewscope.provider.lark.maximum-response-bytes=1023")
                .run(context -> context.assertThat().hasFailed());
    }

    private static ApplicationContextRunner fullRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(LarkConnectorApplicationConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
                .withBean(ConnectionGrantRepository.class,
                        () -> mock(ConnectionGrantRepository.class))
                .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
                .withBean(ProviderBindingResolver.class,
                        () -> mock(ProviderBindingResolver.class))
                .withBean(TeamMemberRepository.class,
                        () -> mock(TeamMemberRepository.class))
                .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
                .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
                .withBean(LarkExternalTenantRepository.class,
                        () -> mock(LarkExternalTenantRepository.class))
                .withBean(LarkMemberVerificationProofRepository.class,
                        () -> mock(LarkMemberVerificationProofRepository.class))
                .withBean(LarkMemberMappingRepository.class,
                        () -> mock(LarkMemberMappingRepository.class))
                .withBean(NotificationTemplateCatalog.class,
                        () -> mock(NotificationTemplateCatalog.class))
                .withBean(NotificationAuthorizationFactsResolver.class,
                        () -> mock(NotificationAuthorizationFactsResolver.class))
                .withBean(NotificationPlanRepository.class,
                        () -> mock(NotificationPlanRepository.class))
                .withBean(NotificationAdministrationRepository.class,
                        () -> mock(NotificationAdministrationRepository.class))
                .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
                .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
                .withBean(ProviderDefinitionRepository.class,
                        () -> mock(ProviderDefinitionRepository.class))
                .withBean(ProviderImplementationRepository.class,
                        () -> mock(ProviderImplementationRepository.class))
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(ProviderBootstrapLock.class,
                        () -> mock(ProviderBootstrapLock.class))
                .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TeamActivityCursorKeyRing.class,
                        () -> mock(TeamActivityCursorKeyRing.class));
    }
}
