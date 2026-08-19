package io.crewscope.infrastructure.workspace.git;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the host Git command boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitCommandProperties.class)
public class GitCommandConfiguration {

    @Bean
    @ConditionalOnMissingBean(GitCommandExecutor.class)
    GitCommandExecutor gitCommandExecutor(GitCommandProperties properties) {
        return new GitCommandExecutor(properties.toPolicy());
    }
}
