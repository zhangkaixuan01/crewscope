package io.crewscope.probe.ws;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Admin fan-out entry for the load scenarios: emits change notifications
 * (coordinates + version, zero payload) to the subscribers of a scope.
 * {@code count} lets the slow-client scenario fill a bounded buffer in one
 * burst.
 */
@RestController
public class NotifyController {

    private final CollaborationProbeHandler handler;

    public NotifyController(CollaborationProbeHandler handler) {
        this.handler = handler;
    }

    public record NotifyRequest(String organizationId, String teamId,
            String resourceType, String resourceId, long version, int count) {}

    @PostMapping("/probe/admin/notify")
    public Mono<Map<String, Object>> notify(@RequestBody NotifyRequest request) {
        return Mono.fromCallable(() -> {
            int count = Math.max(1, request.count());
            int delivered = handler.notifyChanged(request.organizationId(), request.teamId(),
                    request.resourceType(), request.resourceId(), request.version(), count);
            return Map.<String, Object>of("emitted", count, "deliveredLast", delivered);
        });
    }
}
