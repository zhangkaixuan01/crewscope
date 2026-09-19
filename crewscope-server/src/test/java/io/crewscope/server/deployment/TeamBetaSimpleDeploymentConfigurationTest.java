package io.crewscope.server.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.crewscope.application.model.PlatformModelCatalogInitializer;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.persistence.search.JdbcSearchQueryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workdesk.JdbcWorkDeskRepositoryAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the actual bean conditions without starting a database, server or Provider. */
class TeamBetaSimpleDeploymentConfigurationTest {

    @Test
    void readRepositoriesSupportClassBasedTransactionProxies() {
        JdbcTemplate jdbc = new JdbcTemplate();
        for (Object repository : new Object[] {
                new JdbcSearchQueryRepositoryAdapter(jdbc), new JdbcWorkDeskRepositoryAdapter(jdbc)}) {
            ProxyFactory factory = new ProxyFactory(repository);
            factory.setProxyTargetClass(true);
            assertThat(AopUtils.isCglibProxy(factory.getProxy())).isTrue();
        }
    }

    @Test
    void combinedProfileDoesNotEnableLegacyGuardByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(TeamBetaDeploymentConfiguration.class)
                .withPropertyValues("spring.profiles.active=team-beta",
                        "crewscope.runtime.execution-profile=all")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TeamBetaDeploymentGuard.class);
                });
    }

    @Test
    void explicitlyEnabledLegacyGuardStillRejectsCombinedRole() {
        new ApplicationContextRunner()
                .withUserConfiguration(TeamBetaDeploymentConfiguration.class)
                .withPropertyValues("spring.profiles.active=team-beta",
                        "crewscope.runtime.execution-profile=all",
                        "crewscope.deployment.guard.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void combinedAndServerRolesInitializeCatalogInBothDeploymentProfiles() {
        for (String profile : new String[] {"team-beta", "local-demo"}) {
            for (String role : new String[] {"all", "server"}) {
                PlatformModelCatalogInitializer catalog = mock(PlatformModelCatalogInitializer.class);
                TimeProvider time = TimeProvider.from(
                        Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));
                bootstrap(profile, role)
                        .withBean(PlatformModelCatalogInitializer.class, () -> catalog)
                        .withBean(TimeProvider.class, () -> time)
                        .run(context -> {
                            assertThat(context).hasNotFailed();
                            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
                            verify(catalog).initialize(PrincipalId.from(
                                    "0198a475-0831-7000-8000-000000000002"), time.now());
                        });
            }
        }
    }

    @Test
    void workerRoleDoesNotRequireCatalogDependencies() {
        bootstrap("team-beta", "worker").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ApplicationRunner.class);
        });
    }

    private ApplicationContextRunner bootstrap(String profile, String role) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TeamBetaBootstrapConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + profile,
                        "crewscope.runtime.execution-profile=" + role,
                        "crewscope.deployment.bootstrap.enabled=true",
                        "crewscope.deployment.bootstrap.organization-name=Test Team",
                        "crewscope.deployment.bootstrap.organization-id=0198a475-0831-7000-8000-000000000001",
                        "crewscope.deployment.bootstrap.runtime-principal-id=0198a475-0831-7000-8000-000000000002");
    }
}
