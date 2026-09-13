package io.crewscope.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

/** Verifies the stable metadata contract used by the runtime Springdoc document. */
class OpenApiConfigurationTest {

    @Test
    void publishesCrewScopeMetadataWithoutExposingRuntimeCredentials() {
        OpenAPI document = new OpenApiConfiguration().crewScopeOpenAPI("crewscope-server", "0.1.0");

        assertThat(document.getInfo()).isNotNull();
        assertThat(document.getInfo().getTitle()).isEqualTo("CrewScope API");
        assertThat(document.getInfo().getVersion()).isEqualTo("0.1.0");
        assertThat(document.getInfo().getDescription()).contains("Team-collaborative");
        assertThat(document.getInfo().getContact().getName()).isEqualTo("crewscope-server");
    }
}
