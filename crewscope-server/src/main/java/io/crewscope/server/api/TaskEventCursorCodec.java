package io.crewscope.server.api;

import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Canonical opaque codec binding one durable position to its complete Task route. */
public final class TaskEventCursorCodec {

    private static final byte VERSION = 1;
    private static final int BINARY_SIZE = 1 + (4 * 2 * Long.BYTES) + Long.BYTES;
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(TaskEventCursor cursor) {
        TaskEventCursor source = Objects.requireNonNull(cursor, "cursor");
        ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE).put(VERSION);
        putUuid(buffer, source.organizationId().value());
        putUuid(buffer, source.teamId().value());
        putUuid(buffer, source.taskId().value());
        buffer.putLong(source.position());
        putUuid(buffer, source.eventId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public TaskEventCursor decode(
            String token,
            OrganizationId expectedOrganizationId,
            TeamId expectedTeamId,
            TaskId expectedTaskId) {
        if (!validToken(token)) {
            throw invalidCursor();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != BINARY_SIZE || !canonical(bytes).equals(token)) {
                throw invalidCursor();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            return new TaskEventCursor(
                            new OrganizationId(readUuid(buffer)),
                            new TeamId(readUuid(buffer)),
                            new TaskId(readUuid(buffer)),
                            buffer.getLong(),
                            readUuid(buffer))
                    .requireStream(expectedOrganizationId, expectedTeamId, expectedTaskId);
        } catch (ApiRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static boolean validToken(String token) {
        return token != null
                && !token.isBlank()
                && token.length() <= MAX_TOKEN_LENGTH
                && TOKEN_FORMAT.matcher(token).matches();
    }

    private static String canonical(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another Task stream",
                Map.of("parameter", "after"));
    }
}
