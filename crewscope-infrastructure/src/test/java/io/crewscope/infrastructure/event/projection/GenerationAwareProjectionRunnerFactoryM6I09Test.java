package io.crewscope.infrastructure.event.projection;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring 7 constructor-selection regression proof for the production projection factory. */
class GenerationAwareProjectionRunnerFactoryM6I09Test {

    @Test
    void selectsTheProductionConstructorWhenThePackagePrivateTestConstructorAlsoExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(GenerationAwareProjectionRunnerFactory.class)
                .withBean(JdbcProjectionGenerationRegistry.class,
                        () -> mock(JdbcProjectionGenerationRegistry.class))
                .withBean(JdbcGenerationProjectionStore.class,
                        () -> mock(JdbcGenerationProjectionStore.class))
                .withBean(ProjectionEventJsonMapper.class,
                        () -> mock(ProjectionEventJsonMapper.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(GenerationAwareProjectionRunnerFactory.class));
    }

    @Test
    void selectsTheProductionConstructorForTheGenerationLifecycle() {
        new ApplicationContextRunner()
                .withUserConfiguration(JdbcProjectionGenerationLifecycle.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(JdbcProjectionGenerationLifecycle.class));
    }
}
