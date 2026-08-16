package io.crewscope.server.config.runtime;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenAuthenticator;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenJtiGenerator;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.DurableTaskTokenAuthenticator;
import io.crewscope.infrastructure.runtime.DurableTaskTokenService;
import io.crewscope.infrastructure.runtime.SecureTaskTokenJtiGenerator;
import io.crewscope.infrastructure.runtime.TaskTokenServiceSpec;
import io.crewscope.infrastructure.runtime.TaskTokenCurrentAuthorization;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.server.security.NimbusTaskTokenCodec;
import io.crewscope.server.security.TaskTokenWebFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Task Token key ring, profile-neutral verifier and Worker-capable issuance authority. */
@Configuration
@EnableConfigurationProperties(TaskTokenSigningProperties.class)
@ConditionalOnProperty(prefix = "crewscope.security.task-token", name = "enabled", havingValue = "true")
public class TaskTokenSecurityConfiguration {

    @Bean
    TaskTokenCodec taskTokenCodec(TaskTokenSigningProperties properties) {
        return new NimbusTaskTokenCodec(
                properties.getIssuer(), properties.getCurrentKeyId(), properties.getKeys());
    }

    @Bean("taskTokenAuthenticator")
    TaskTokenAuthenticator taskTokenAuthenticator(
            TaskCredentialGrantRepository grantRepository,
            ExecutionLeaseRepository leaseRepository,
            TaskTokenCurrentAuthorization currentAuthorization,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            TaskTokenCodec codec) {
        return new DurableTaskTokenAuthenticator(
                grantRepository, leaseRepository, currentAuthorization,
                transactionExecutor, timeProvider, codec);
    }

    @Bean
    TaskTokenCurrentAuthorization taskTokenCurrentAuthorization(
            TaskExecutionRepository executionRepository,
            TaskRepository taskRepository,
            PrincipalRepository principalRepository,
            ResponsibilityAssignmentRepository assignmentRepository,
            TeamMemberRepository memberRepository) {
        return new TaskTokenCurrentAuthorization(
                executionRepository, taskRepository, principalRepository,
                assignmentRepository, memberRepository);
    }

    @Bean
    TaskTokenWebFilter taskTokenWebFilter(
            @Qualifier("taskTokenAuthenticator") TaskTokenAuthenticator authenticator) {
        return new TaskTokenWebFilter(authenticator);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskTokenJtiGenerator taskTokenJtiGenerator() {
        return new SecureTaskTokenJtiGenerator();
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskTokenServiceSpec taskTokenServiceSpec(RuntimeWorkerRegistrationSpec registrationSpec) {
        return new TaskTokenServiceSpec(
                registrationSpec.organizationId(),
                registrationSpec.environment(),
                registrationSpec.actor());
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskTokenService taskTokenService(
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            TaskCredentialGrantRepository grantRepository,
            TaskTokenCurrentAuthorization currentAuthorization,
            ProviderBindingRepository bindingRepository,
            ConnectionGrantRepository connectionGrantRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            TaskTokenJtiGenerator jtiGenerator,
            TaskTokenCodec codec,
            TaskTokenServiceSpec spec) {
        return new DurableTaskTokenService(
                executionRepository, leaseRepository, policyRepository, overlayRepository,
                grantRepository, currentAuthorization, bindingRepository,
                connectionGrantRepository, transactionExecutor, timeProvider,
                jtiGenerator, codec, spec);
    }
}
