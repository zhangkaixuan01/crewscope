package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxSourceTarget;
import java.util.Objects;
import org.springframework.web.util.UriComponentsBuilder;

/** Authorized internal destination generated exclusively from server-owned route templates. */
public record InboxTargetResponse(String kind, String href) {

    static InboxTargetResponse from(InboxSourceTarget target) {
        InboxSourceTarget value = Objects.requireNonNull(target, "target");
        UriComponentsBuilder uri;
        if (value.kind() == InboxSourceTarget.Kind.NOTIFICATION) {
            uri = UriComponentsBuilder.fromPath("/settings/integrations")
                    .queryParam("team", value.teamId())
                    .queryParam("notificationDelivery", value.sourceId());
        } else {
            uri = UriComponentsBuilder.fromPath("/work")
                    .queryParam("team", value.teamId())
                    .queryParam("project", value.projectId().orElseThrow())
                    .queryParam("workItem", value.workItemId().orElseThrow());
            value.taskId().ifPresent(id -> uri.queryParam("task", id));
            value.taskExecutionId().ifPresent(id -> uri.queryParam("execution", id));
            switch (value.kind()) {
                case REVIEW -> uri.queryParam("review", value.sourceId());
                case ACTION -> uri.queryParam("action", value.sourceId());
                case WORK_ITEM, TASK, NOTIFICATION -> { }
            }
        }
        return new InboxTargetResponse(
                value.kind().name(), uri.build().encode().toUriString());
    }
}
