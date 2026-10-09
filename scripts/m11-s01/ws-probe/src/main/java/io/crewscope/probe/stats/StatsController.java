package io.crewscope.probe.stats;

import io.crewscope.probe.ws.ConnectionRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Live counters for the load scenarios (connection/subscription/fanout/heap). */
@RestController
public class StatsController {

    private final ConnectionRegistry registry;

    public StatsController(ConnectionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/probe/admin/stats")
    public Mono<Map<String, Object>> stats() {
        return Mono.fromCallable(() -> {
            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("connections", registry.connectionCount());
            body.put("subscriptions", registry.connections().stream()
                    .mapToInt(c -> c.subscriptions().size()).sum());
            body.put("deniedSubscriptions", registry.deniedSubscriptions().get());
            body.put("framesIn", registry.framesIn().get());
            body.put("framesOut", registry.framesOut().get());
            body.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
            body.put("heapMaxMb", runtime.maxMemory() >> 20);
            body.put("totalCpuSeconds", ProcessHandle.current().info()
                    .totalCpuDuration().map(d -> d.toMillis() / 1000.0).orElse(-1.0));
            return body;
        });
    }
}
