package io.crewscope.server.deployment;

import org.springframework.beans.factory.annotation.Value;
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
}
