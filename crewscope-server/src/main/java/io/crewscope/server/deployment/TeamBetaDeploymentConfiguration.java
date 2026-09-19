package io.crewscope.server.deployment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

/** Optional legacy deployment validation; the simple Team Beta profile does not require it. */
@Configuration(proxyBeanMethods = false)
@Profile("team-beta")
@ConditionalOnProperty(
        prefix = "crewscope.deployment.guard",
        name = "enabled",
        havingValue = "true")
public class TeamBetaDeploymentConfiguration {

    @Bean
    TeamBetaDeploymentGuard teamBetaDeploymentGuard(Environment environment) {
        return new TeamBetaDeploymentGuard(environment);
    }
}
