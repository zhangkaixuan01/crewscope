package io.crewscope.server.config.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the Jackson 2 mapper required by AgentScope 2.0 and Coding infrastructure adapters. */
@Configuration(proxyBeanMethods = false)
public class LegacyJacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper legacyObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
