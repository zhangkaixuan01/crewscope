package io.crewscope.server.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    @GetMapping("/info")
    public SystemInfo info() {
        return new SystemInfo(
                "CrewScope",
                "0.1.0-SNAPSHOT",
                "AgentScope Java 2.0.0",
                List.of("conversation", "team-collaboration", "durable-execution", "provider"));
    }

    public record SystemInfo(
            String product, String version, String agentRuntime, List<String> capabilities) {}
}
