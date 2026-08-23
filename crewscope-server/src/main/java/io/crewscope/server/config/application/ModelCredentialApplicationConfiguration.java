package io.crewscope.server.config.application;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.ModelProviderHealthProbe;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.model.OpenAiCompatibleModelProviderHealthProbe;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/** Explicit constructor wiring for the M5 model credential lifecycle boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelCredentialProperties.class)
public class ModelCredentialApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelProviderHealthProbe.class)
    ModelProviderHealthProbe modelProviderHealthProbe(ModelCredentialProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.validatedHealthConnectTimeout())
                .build();
        return new OpenAiCompatibleModelProviderHealthProbe(
                client, properties.validatedHealthRequestTimeout());
    }

    @Bean
    ModelConnectionCredentialService modelConnectionCredentialService(
            ModelConnectionRepository connectionRepository,
            ModelProviderDefinitionRepository providerRepository,
            CredentialStore credentialStore,
            ModelProviderHealthProbe healthProbe,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            ModelCredentialProperties properties,
            ObjectProvider<ModelConnectionAvailabilityVerifier> availabilityVerifiers) {
        ModelConnectionAvailabilityVerifier availabilityVerifier = availabilityVerifiers
                .getIfAvailable(ModelConnectionAvailabilityVerifier::persistedStateOnly);
        return new ModelConnectionCredentialService(
                connectionRepository,
                providerRepository,
                credentialStore,
                healthProbe,
                eventStore,
                outboxRepository,
                transactionExecutor,
                timeProvider,
                properties.validatedHandleTtl(),
                availabilityVerifier);
    }
}
