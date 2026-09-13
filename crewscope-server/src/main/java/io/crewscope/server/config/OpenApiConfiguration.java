package io.crewscope.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the generated document metadata stable across local, CI and production profiles.
 * Endpoint paths and DTO schemas continue to come from Spring WebFlux controllers.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI crewScopeOpenAPI(
            @Value("${spring.application.name:crewscope-server}") String applicationName,
            @Value("${crewscope.api.version:0.1.0}") String apiVersion) {
        return new OpenAPI()
                .info(new Info()
                        .title("CrewScope API")
                        .description("Team-collaborative AI work execution API")
                        .version(apiVersion)
                        .contact(new io.swagger.v3.oas.models.info.Contact()
                                .name(applicationName)));
    }
}
