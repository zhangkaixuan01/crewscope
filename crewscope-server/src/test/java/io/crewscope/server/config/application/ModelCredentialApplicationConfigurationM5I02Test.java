package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.ModelProviderHealthProbe;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.model.OpenAiCompatibleModelProviderHealthProbe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Explicit Spring assembly contract for the M5-I02 credential lifecycle boundary. */
class ModelCredentialApplicationConfigurationM5I02Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelCredentialApplicationConfiguration.class)
            .withBean(ModelConnectionRepository.class, () -> mock(ModelConnectionRepository.class))
            .withBean(
                    ModelProviderDefinitionRepository.class,
                    () -> mock(ModelProviderDefinitionRepository.class))
            .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
            .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class));

    @Test
    void createsTheCredentialServiceAndDefaultOpenAiCompatibleProbe() {
        runner.withPropertyValues(
                        "crewscope.model.credential.handle-ttl=20s",
                        "crewscope.model.credential.health-connect-timeout=2s",
                        "crewscope.model.credential.health-request-timeout=5s")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(ModelConnectionCredentialService.class)
                        .hasSingleBean(ModelProviderHealthProbe.class)
                        .hasSingleBean(OpenAiCompatibleModelProviderHealthProbe.class));
    }

    @Test
    void honorsAProviderSpecificHealthProbeOverride() {
        runner.withBean(
                        ModelProviderHealthProbe.class,
                        () -> mock(ModelProviderHealthProbe.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(ModelConnectionCredentialService.class)
                        .hasSingleBean(ModelProviderHealthProbe.class)
                        .doesNotHaveBean(OpenAiCompatibleModelProviderHealthProbe.class));
    }

    @Test
    void rejectsAHandleLifetimeAboveTheSecurityLimit() {
        runner.withPropertyValues("crewscope.model.credential.handle-ttl=11m")
                .run(context -> context.assertThat().hasFailed());
    }
}
