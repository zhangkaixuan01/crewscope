package io.crewscope.server.observability;

import io.crewscope.application.coding.CodingArtifactContent;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/** Emits one safe audit fact for every authorized Coding Artifact transfer. */
@Component
public final class CodingArtifactDownloadRecorder {

    public static final String REQUESTS = "crewscope.coding.artifact.downloads";

    private static final Logger LOGGER = LoggerFactory.getLogger(CodingArtifactDownloadRecorder.class);

    private final MeterRegistry registry;

    public CodingArtifactDownloadRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void record(
            Kind kind,
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            UUID correlationId,
            CodingArtifactContent content,
            boolean rangedRequest) {
        Kind requiredKind = Objects.requireNonNull(kind, "kind");
        CodingArtifactContent requiredContent = Objects.requireNonNull(content, "content");
        String mode = rangedRequest ? "partial" : "complete";
        Counter.builder(REQUESTS)
                .description("Authorized Coding Artifact transfers")
                .tags(
                        "kind", requiredKind.name().toLowerCase(Locale.ROOT),
                        "mode", mode)
                .register(registry)
                .increment();

        LoggingEventBuilder event = LOGGER.atInfo()
                .addKeyValue("event", "coding_artifact_download")
                .addKeyValue("kind", requiredKind.name())
                .addKeyValue("mode", mode)
                .addKeyValue("organizationId", Objects.requireNonNull(organizationId, "organizationId"))
                .addKeyValue("teamId", Objects.requireNonNull(teamId, "teamId"))
                .addKeyValue("taskId", Objects.requireNonNull(taskId, "taskId"))
                .addKeyValue("taskExecutionId", Objects.requireNonNull(executionId, "executionId"))
                .addKeyValue("artifactId", requiredContent.artifactId())
                .addKeyValue("actorPrincipalId", Objects.requireNonNull(access, "access").actor().id())
                .addKeyValue("correlationId", Objects.requireNonNull(correlationId, "correlationId"))
                .addKeyValue("responseBytes", requiredContent.contentLength());
        event.log("Coding Artifact download authorized");
    }

    public enum Kind {
        PATCH,
        BUILD_LOG,
        TEST_REPORT
    }
}
