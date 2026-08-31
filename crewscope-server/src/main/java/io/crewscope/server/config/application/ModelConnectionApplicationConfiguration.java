package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.DefaultPlatformModelCatalogInitializer;
import io.crewscope.application.model.ModelConnectionApplicationService;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.PlatformModelCatalogInitializer;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition root for the M5-A01 model registry and connection management API. */
@Configuration(proxyBeanMethods = false)
public class ModelConnectionApplicationConfiguration {

    @Bean
    PlatformModelCatalogInitializer platformModelCatalogInitializer(
            ModelProviderDefinitionRepository providers,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices) {
        return new DefaultPlatformModelCatalogInitializer(providers, catalogs, prices);
    }

    @Bean
    ModelConnectionApplicationService modelConnectionApplicationService(
            ModelProviderDefinitionRepository providers,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionRepository connections,
            ModelConnectionCredentialService credentials,
            CommandReceiptStore receiptStore,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TimeProvider timeProvider) {
        return new ModelConnectionApplicationService(
                providers,
                catalogs,
                prices,
                connections,
                credentials,
                receiptStore,
                teams,
                memberships,
                roles,
                grants,
                timeProvider);
    }
}
