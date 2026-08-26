package io.crewscope.server.config.application;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.collaboration.DefaultLarkConnectionAuthorizationResolver;
import io.crewscope.application.collaboration.DefaultLarkMappingAdministration;
import io.crewscope.application.collaboration.LarkCollaborationApplicationService;
import io.crewscope.application.collaboration.LarkConnectionAuthorizationResolver;
import io.crewscope.application.collaboration.LarkExternalTenantRepository;
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
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.integration.provider.collaboration.LarkCollaborationProvider;
import io.crewscope.integration.provider.collaboration.LarkCredentialAccessManager;
import io.crewscope.integration.provider.collaboration.LarkOpenApiClient;
import io.crewscope.integration.provider.collaboration.LarkNotificationCredentialIssuer;
import io.crewscope.integration.provider.collaboration.LarkNotificationProviderAdapter;
import io.crewscope.integration.provider.collaboration.LarkTenantTokenCache;
import java.net.http.HttpClient;
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
}
