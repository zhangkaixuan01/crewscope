package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LegacyJacksonConfigurationM4Q03Test {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LegacyJacksonConfiguration.class);

    @Test
    void publishesOneLegacyMapperForAgentScopeAndCodingAdapters() {
        context.run(application -> assertThat(application)
                .hasNotFailed()
                .hasSingleBean(ObjectMapper.class));
    }

    @Test
    void preservesAnExplicitApplicationMapper() {
        ObjectMapper explicit = new ObjectMapper();

        context.withBean(ObjectMapper.class, () -> explicit).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(ObjectMapper.class);
            assertSame(explicit, application.getBean(ObjectMapper.class));
        });
    }
}
