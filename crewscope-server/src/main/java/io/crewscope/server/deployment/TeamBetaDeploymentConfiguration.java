package io.crewscope.server.deployment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Activates fail-closed deployment validation only for the explicit Team Beta profile. */
@Configuration(proxyBeanMethods = false)
@Profile("team-beta")
public class TeamBetaDeploymentConfiguration {

    @Bean
    TeamBetaDeploymentGuard teamBetaDeploymentGuard(Environment environment) {
        return new TeamBetaDeploymentGuard(environment);
    }
}
