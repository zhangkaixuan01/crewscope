package io.crewscope.server.config.application;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.collaboration.DefaultLarkConnectionAuthorizationResolver;
import io.crewscope.application.collaboration.DefaultLarkMappingAdministration;
import io.crewscope.application.collaboration.LarkCollaborationApplicationService;
import io.crewscope.application.collaboration.LarkAdministrationCommandService;
import io.crewscope.application.collaboration.LarkConnectionApplicationService;
import io.crewscope.application.collaboration.LarkConnectionAuthorizationResolver;
import io.crewscope.application.collaboration.LarkExternalTenantRepository;
import io.crewscope.application.collaboration.LarkMappingAdministration;
import io.crewscope.application.collaboration.LarkMemberMappingApplicationService;
import io.crewscope.application.collaboration.LarkMemberMappingRepository;
import io.crewscope.application.collaboration.LarkMemberVerificationProofRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.notification.NotificationAdministrationRepository;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.NotificationAuthorizationFactsResolver;
import io.crewscope.application.notification.NotificationPlanRepository;
import io.crewscope.application.notification.NotificationPlanningApplicationService;
import io.crewscope.application.notification.NotificationRecipientAuthorization;
import io.crewscope.application.notification.FixedNotificationTemplateRenderer;
import io.crewscope.application.notification.NotificationCredentialIssuer;
import io.crewscope.application.notification.NotificationProviderPort;
import io.crewscope.application.notification.NotificationTemplateCatalog;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.server.api.LarkMappingCursorCodec;
import io.crewscope.server.api.NotificationDeliveryCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.integration.provider.collaboration.LarkCollaborationProvider;
import io.crewscope.integration.provider.collaboration.LarkCredentialAccessManager;
import io.crewscope.integration.provider.collaboration.LarkOpenApiClient;
import io.crewscope.integration.provider.collaboration.LarkNotificationCredentialIssuer;
import io.crewscope.integration.provider.collaboration.LarkNotificationProviderAdapter;
import io.crewscope.integration.provider.collaboration.LarkTenantTokenCache;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Constructor-based Spring composition for the fixed-operation Lark Connector. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LarkConnectorProperties.class)
public class LarkConnectorApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean(LarkCollaborationProvider.class)
    LarkCollaborationProvider larkCollaborationProvider(
            ObjectProvider<LarkOpenApiClient> clients,
            ObjectProvider<TimeProvider> timeProviders) {
        LarkOpenApiClient client = clients.getIfAvailable();
        TimeProvider timeProvider = timeProviders.getIfAvailable();
        return client == null || timeProvider == null
                ? new LarkCollaborationProvider()
                : new LarkCollaborationProvider(client, timeProvider);
    }

    @Bean
    @ConditionalOnBean({
        ConnectionRepository.class,
        ConnectionGrantRepository.class,
        CredentialStore.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(LarkCredentialAccessManager.class)
    LarkCredentialAccessManager larkCredentialAccessManager(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            TimeProvider timeProvider,
            LarkConnectorProperties properties) {
        return new LarkCredentialAccessManager(
                connections, grants, credentials, timeProvider,
                properties.validatedCredentialHandleTtl());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LarkTenantTokenCache.class)
    LarkTenantTokenCache larkTenantTokenCache(LarkConnectorProperties properties) {
        return new LarkTenantTokenCache(
                properties.validatedMaximumCachedTokens(),
                properties.validatedTokenExpirySafetyMargin());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean({
        LarkCredentialAccessManager.class,
        LarkTenantTokenCache.class,
        ObjectMapper.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(LarkOpenApiClient.class)
    LarkOpenApiClient larkOpenApiClient(
            ObjectMapper objectMapper,
            TimeProvider timeProvider,
            LarkCredentialAccessManager accessManager,
            LarkTenantTokenCache tokenCache,
            ObjectProvider<OperationalTelemetry> telemetry,
            LarkConnectorProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.validatedConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new LarkOpenApiClient(
                client, objectMapper, properties.validatedBaseUri(),
                properties.isAllowLoopbackHttp(), properties.validatedRequestTimeout(),
                properties.validatedMaximumResponseBytes(), timeProvider, accessManager,
                tokenCache, telemetry.getIfAvailable(OperationalTelemetry::noop));
    }

    @Bean
    @ConditionalOnBean(ProviderBindingResolver.class)
    @ConditionalOnMissingBean(LarkConnectionAuthorizationResolver.class)
    LarkConnectionAuthorizationResolver larkConnectionAuthorizationResolver(
            ProviderBindingResolver bindings) {
        return new DefaultLarkConnectionAuthorizationResolver(bindings);
    }

    @Bean
    @ConditionalOnBean({
        TeamMemberRepository.class,
        TeamRoleRepository.class,
        MemberRoleRepository.class
    })
    @ConditionalOnMissingBean(LarkMappingAdministration.class)
    LarkMappingAdministration larkMappingAdministration(
            TeamMemberRepository members,
            TeamRoleRepository roles,
            MemberRoleRepository grants) {
        return new DefaultLarkMappingAdministration(members, roles, grants);
    }

    @Bean
    @ConditionalOnBean({
        LarkConnectionAuthorizationResolver.class,
        LarkMappingAdministration.class,
        LarkOpenApiClient.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(LarkCollaborationApplicationService.class)
    LarkCollaborationApplicationService larkCollaborationApplicationService(
            LarkConnectionAuthorizationResolver authorizations,
            LarkMappingAdministration administration,
            LarkCollaborationProvider provider,
            TimeProvider timeProvider) {
        return new LarkCollaborationApplicationService(
                authorizations, administration, provider, timeProvider);
    }

    @Bean
    @ConditionalOnBean({
        LarkConnectionAuthorizationResolver.class,
        LarkMappingAdministration.class,
        LarkOpenApiClient.class,
        LarkExternalTenantRepository.class,
        LarkMemberVerificationProofRepository.class,
        LarkMemberMappingRepository.class,
        TeamMemberRepository.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(LarkMemberMappingApplicationService.class)
    LarkMemberMappingApplicationService larkMemberMappingApplicationService(
            LarkConnectionAuthorizationResolver authorizations,
            LarkMappingAdministration administration,
            LarkCollaborationProvider provider,
            LarkExternalTenantRepository tenants,
            LarkMemberVerificationProofRepository proofs,
            LarkMemberMappingRepository mappings,
            TeamMemberRepository members,
            TimeProvider timeProvider,
            LarkConnectorProperties properties) {
        return new LarkMemberMappingApplicationService(
                authorizations,
                administration,
                provider,
                tenants,
                proofs,
                mappings,
                members,
                timeProvider,
                properties.validatedMemberConfirmationWindow());
    }

    @Bean
    @ConditionalOnBean(NotificationTemplateCatalog.class)
    @ConditionalOnMissingBean(FixedNotificationTemplateRenderer.class)
    FixedNotificationTemplateRenderer fixedNotificationTemplateRenderer(
            NotificationTemplateCatalog templates) {
        return new FixedNotificationTemplateRenderer(templates);
    }

    @Bean
    @ConditionalOnBean({
        LarkConnectionAuthorizationResolver.class,
        LarkCredentialAccessManager.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(NotificationCredentialIssuer.class)
    NotificationCredentialIssuer larkNotificationCredentialIssuer(
            LarkConnectionAuthorizationResolver authorizations,
            LarkCredentialAccessManager accessManager,
            TimeProvider timeProvider) {
        return new LarkNotificationCredentialIssuer(
                authorizations, accessManager, timeProvider);
    }

    @Bean
    @ConditionalOnBean({
        LarkOpenApiClient.class,
        FixedNotificationTemplateRenderer.class,
        LarkMemberMappingRepository.class,
        LarkExternalTenantRepository.class,
        TeamMemberRepository.class
    })
    @ConditionalOnMissingBean(NotificationProviderPort.class)
    NotificationProviderPort larkNotificationProviderPort(
            LarkOpenApiClient client,
            FixedNotificationTemplateRenderer renderer,
            LarkMemberMappingRepository mappings,
            LarkExternalTenantRepository tenants,
            TeamMemberRepository members) {
        return new LarkNotificationProviderAdapter(
                client, renderer, mappings, tenants, members);
    }

    @Bean
    @ConditionalOnBean({
        NotificationTemplateCatalog.class,
        NotificationAuthorizationFactsResolver.class,
        NotificationRecipientAuthorization.class,
        NotificationPlanRepository.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(NotificationPlanningApplicationService.class)
    NotificationPlanningApplicationService notificationPlanningApplicationService(
            NotificationTemplateCatalog templates,
            NotificationAuthorizationFactsResolver facts,
            NotificationRecipientAuthorization recipients,
            NotificationPlanRepository plans,
            TimeProvider timeProvider) {
        return new NotificationPlanningApplicationService(
                templates, facts, recipients, plans, timeProvider, Duration.ofHours(1));
    }

    @Bean
    @ConditionalOnBean({
        LarkMappingAdministration.class,
        NotificationAdministrationRepository.class,
        NotificationPlanningApplicationService.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(NotificationAdministrationService.class)
    NotificationAdministrationService notificationAdministrationService(
            LarkMappingAdministration administration,
            NotificationAdministrationRepository repository,
            NotificationPlanningApplicationService planning,
            TimeProvider timeProvider) {
        return new NotificationAdministrationService(
                administration, repository, planning, timeProvider);
    }

    @Bean
    @ConditionalOnBean({
        LarkMappingAdministration.class,
        ConnectionRepository.class,
        ConnectionGrantRepository.class,
        CredentialStore.class,
        TeamRepository.class,
        WorkspaceRepository.class,
        ProviderDefinitionRepository.class,
        ProviderImplementationRepository.class,
        ProviderBindingRepository.class,
        ProviderBootstrapLock.class,
        CommandReceiptStore.class,
        TransactionExecutor.class,
        TimeProvider.class,
        ObjectMapper.class
    })
    @ConditionalOnMissingBean(LarkConnectionApplicationService.class)
    LarkConnectionApplicationService larkConnectionApplicationService(
            LarkMappingAdministration administration,
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            TeamRepository teams,
            WorkspaceRepository workspaces,
            ProviderDefinitionRepository definitions,
            ProviderImplementationRepository implementations,
            ProviderBindingRepository bindings,
            ProviderBootstrapLock bootstrapLock,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            ObjectMapper objectMapper,
            LarkCollaborationProvider provider) {
        var descriptor = provider.descriptor();
        BuiltInProviderRegistration registration = new BuiltInProviderRegistration(
                LarkCollaborationCapabilities.CONNECTOR_KEY,
                descriptor.type(), descriptor.interfaceVersion(), descriptor.displayName(),
                LarkCollaborationCapabilities.CONNECTOR_KEY, "1.0.0",
                LarkCollaborationCapabilities.COMPLETE);
        return new LarkConnectionApplicationService(
                administration, connections, grants, credentials, teams, workspaces,
                definitions, implementations, bindings, bootstrapLock, registration,
                receipts, transactions, timeProvider, objectMapper);
    }

    @Bean
    @ConditionalOnBean({
        LarkMappingAdministration.class,
        LarkMemberMappingApplicationService.class,
        NotificationAdministrationService.class,
        CommandReceiptStore.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(LarkAdministrationCommandService.class)
    LarkAdministrationCommandService larkAdministrationCommandService(
            LarkMappingAdministration administration,
            LarkMemberMappingApplicationService mappings,
            NotificationAdministrationService notifications,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new LarkAdministrationCommandService(
                administration, mappings, notifications, receipts, transactions, timeProvider);
    }

    @Bean
    @ConditionalOnBean(TeamActivityCursorKeyRing.class)
    @ConditionalOnMissingBean(LarkMappingCursorCodec.class)
    LarkMappingCursorCodec larkMappingCursorCodec(TeamActivityCursorKeyRing keyRing) {
        return new LarkMappingCursorCodec(keyRing);
    }

    @Bean
    @ConditionalOnBean(TeamActivityCursorKeyRing.class)
    @ConditionalOnMissingBean(NotificationDeliveryCursorCodec.class)
    NotificationDeliveryCursorCodec notificationDeliveryCursorCodec(
            TeamActivityCursorKeyRing keyRing) {
        return new NotificationDeliveryCursorCodec(keyRing);
    }
}
