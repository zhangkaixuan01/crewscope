package io.crewscope.infrastructure.workspace.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only Spring wiring for TaskExecution-level AgentScope Docker Sandbox ownership. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(TaskExecutionSandboxProperties.class)
public class TaskExecutionSandboxConfiguration {

    @Bean
    @ConditionalOnMissingBean(DockerSandboxControl.class)
    DockerSandboxControl dockerSandboxControl(
            ObjectMapper objectMapper, TaskExecutionSandboxProperties properties) {
        return new DockerCliSandboxControl(
                objectMapper, properties.requiredDockerCommandTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(TaskExecutionSandboxFactory.class)
    TaskExecutionSandboxFactory taskExecutionSandboxFactory(
            TaskExecutionSandboxProperties properties,
            DockerSandboxControl dockerControl) {
        return new TaskExecutionSandboxFactory(properties, dockerControl, Clock.systemUTC());
    }
}
