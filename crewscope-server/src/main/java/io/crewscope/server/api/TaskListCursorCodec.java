package io.crewscope.server.api;

import io.crewscope.application.task.TaskListCursor;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Versioned opaque Task keyset bound to its tenant route and active filters. */
public final class TaskListCursorCodec {

    private static final byte VERSION = 2;
    private static final int BINARY_SIZE = 1 + 16 + 16 + 1 + 16 + 1 + 1 + 8 + 4 + 16;
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(
            TaskListCursor cursor,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Optional<TaskStatus> status) {
        TaskListCursor source = Objects.requireNonNull(cursor, "cursor");
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        Optional<WorkProjectId> project = Objects.requireNonNull(projectId, "projectId");
        Optional<TaskStatus> taskStatus = Objects.requireNonNull(status, "status");
        Instant instant = source.updatedAt().value();
        UUID id = source.id().value();
        ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE)
                .put(VERSION);
        putUuid(buffer, organization.value());
        putUuid(buffer, team.value());
        buffer.put((byte) (project.isPresent() ? 1 : 0));
        putUuid(buffer, project.map(WorkProjectId::value).orElse(new UUID(0, 0)));
        buffer.put((byte) (taskStatus.isPresent() ? 1 : 0));
        buffer.put(taskStatus.map(TaskListCursorCodec::statusCode).orElse((byte) 0))
                .putLong(instant.getEpochSecond())
                .putInt(instant.getNano())
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public TaskListCursor decode(
            String token,
            OrganizationId expectedOrganizationId,
            TeamId expectedTeamId,
            Optional<WorkProjectId> expectedProjectId,
            Optional<TaskStatus> expectedStatus) {
        OrganizationId organization = Objects.requireNonNull(
                expectedOrganizationId, "expectedOrganizationId");
        TeamId team = Objects.requireNonNull(expectedTeamId, "expectedTeamId");
        Optional<WorkProjectId> project = Objects.requireNonNull(
                expectedProjectId, "expectedProjectId");
        Optional<TaskStatus> status = Objects.requireNonNull(expectedStatus, "expectedStatus");
        if (token == null
                || token.isBlank()
                || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalidCursor();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != BINARY_SIZE
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw invalidCursor();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            if (!readUuid(buffer).equals(organization.value())
                    || !readUuid(buffer).equals(team.value())) {
                throw invalidCursor();
            }
            boolean hasProject = flag(buffer.get());
            UUID encodedProject = readUuid(buffer);
            if (hasProject != project.isPresent()
                    || (hasProject && !encodedProject.equals(project.orElseThrow().value()))
                    || (!hasProject && !encodedProject.equals(new UUID(0, 0)))) {
                throw invalidCursor();
            }
            boolean hasStatus = flag(buffer.get());
            byte encodedStatus = buffer.get();
            if (hasStatus != status.isPresent()
                    || (hasStatus && statusCode(status.orElseThrow()) != encodedStatus)
                    || (!hasStatus && encodedStatus != 0)) {
                throw invalidCursor();
            }
            Instant instant = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID id = new UUID(buffer.getLong(), buffer.getLong());
            return new TaskListCursor(UtcTimestamp.from(instant), new TaskId(id));
        } catch (ApiRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    private static boolean flag(byte value) {
        if (value != 0 && value != 1) {
            throw invalidCursor();
        }
        return value == 1;
    }

    private static byte statusCode(TaskStatus status) {
        return switch (status) {
            case CREATED -> 1;
            case ACTIVE -> 2;
            case WAITING -> 3;
            case COMPLETED -> 4;
            case FAILED -> 5;
            case CANCELLED -> 6;
        };
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid, unsupported, or belongs to another Task collection",
                Map.of("parameter", "after"));
    }
}
