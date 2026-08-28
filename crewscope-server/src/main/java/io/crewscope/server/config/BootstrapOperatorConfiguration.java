package io.crewscope.server.config;

import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.BootstrapOperatorLock;
import io.crewscope.application.identity.BootstrapOperatorPasswordHasher;
import io.crewscope.application.identity.BootstrapOperatorProvisioning;
import io.crewscope.application.identity.BootstrapOperatorProvisioningService;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conditional startup assembly for the durable Bootstrap Operator identity chain. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "crewscope.security.operator-bootstrap.enabled",
        havingValue = "true")
@EnableConfigurationProperties(BootstrapOperatorProperties.class)
public class BootstrapOperatorConfiguration {

    @Bean
    BootstrapOperatorProvisioningService bootstrapOperatorProvisioningService(
            BootstrapOperatorLock bootstrapLock,
            PrincipalRepository principals,
            UserAccountRepository accounts,
            LoginIdentityRepository loginIdentities,
            LocalCredentialStore credentials,
            AccountOrganizationBindingRepository bindings,
            BootstrapOperatorPasswordHasher passwordHasher,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new BootstrapOperatorProvisioningService(
                bootstrapLock,
                principals,
                accounts,
                loginIdentities,
                credentials,
                bindings,
                passwordHasher,
                transactions,
                timeProvider);
    }

    @Bean
    ApplicationRunner bootstrapOperatorProvisioningRunner(
            BootstrapOperatorProvisioningService service,
            BootstrapOperatorProperties properties,
            @Value("${crewscope.security.bootstrap.password:}") String password) {
        BootstrapOperatorProvisioning provisioning = new BootstrapOperatorProvisioning(
                OrganizationId.from(properties.getOrganizationId()),
                properties.getUsername(),
                properties.getEmail(),
                properties.getDisplayName(),
                password);
        return ignored -> service.provision(provisioning);
    }
}
