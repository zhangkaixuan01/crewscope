package io.crewscope.server.deployment;

import io.crewscope.application.model.PlatformModelCatalogInitializer;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** API-owned clean-host bootstrap that runs after all migrations and before Worker-facing beans. */
@Configuration(proxyBeanMethods = false)
@Profile("team-beta")
@ConditionalOnProperty(
        prefix = "crewscope.deployment.bootstrap",
        name = "enabled",
        havingValue = "true")
public class TeamBetaBootstrapConfiguration {

    @Bean
    FlywayMigrationStrategy teamBetaFlywayMigrationStrategy(
            @Value("${crewscope.deployment.bootstrap.organization-id}") String organizationId,
            @Value("${crewscope.deployment.bootstrap.organization-name}") String organizationName,
            @Value("${crewscope.deployment.bootstrap.runtime-principal-id}")
                    String runtimePrincipalId) {
        TeamBetaBootstrapSeeder seeder =
                new TeamBetaBootstrapSeeder(organizationId, organizationName, runtimePrincipalId);
        return flyway -> {
            flyway.migrate();
            seeder.seed(flyway.getConfiguration().getDataSource());
        };
    }

    /** Restores the non-secret model directory after the Runtime Principal has been seeded. */
    @Bean
    @ConditionalOnProperty(
            prefix = "crewscope.runtime",
            name = "execution-profile",
            havingValue = "server")
    ApplicationRunner teamBetaPlatformModelCatalogRunner(
            PlatformModelCatalogInitializer modelCatalog,
            TimeProvider timeProvider,
            @Value("${crewscope.deployment.bootstrap.runtime-principal-id}")
                    String runtimePrincipalId) {
        PrincipalId actor = PrincipalId.from(runtimePrincipalId);
        return arguments -> modelCatalog.initialize(actor, timeProvider.now());
    }
}
