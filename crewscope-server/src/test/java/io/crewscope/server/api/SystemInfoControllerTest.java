package io.crewscope.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class SystemInfoControllerTest {

    private final WebTestClient client =
            WebTestClient.bindToController(new SystemInfoController()).build();

    @Test
    void exposesTheRuntimeBaseline() {
        client.get()
                .uri("/api/v1/system/info")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.product")
                .isEqualTo("CrewScope")
                .jsonPath("$.agentRuntime")
                .isEqualTo("AgentScope Java 2.0.0");
    }
}
