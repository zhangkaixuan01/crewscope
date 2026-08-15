package io.crewscope.server.api;

import io.crewscope.application.task.TaskAssociationCursor;
import io.crewscope.application.task.TaskAssociationSourceType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Versioned opaque codec that binds an association keyset to its complete source route. */
public final class TaskAssociationCursorCodec {

    private static final byte VERSION = 1;
    private static final int BINARY_SIZE = 1 + 16 + 16 + 1 + 16 + 8 + 4 + 16;
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(TaskAssociationCursor cursor) {
        TaskAssociationCursor source = Objects.requireNonNull(cursor, "cursor");
        ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE).put(VERSION);
        putUuid(buffer, source.organizationId().value());
        putUuid(buffer, source.teamId().value());
        buffer.put(sourceType(source.sourceType()));
        putUuid(buffer, source.sourceId());
        Instant instant = source.associatedAt().value();
        buffer.putLong(instant.getEpochSecond()).putInt(instant.getNano());
        putUuid(buffer, source.targetId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public TaskAssociationCursor decode(
            String token,
            OrganizationId organizationId,
            TeamId teamId,
            TaskAssociationSourceType sourceType,
            UUID sourceId) {
        if (token == null
                || token.isBlank()
                || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalidCursor();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != BINARY_SIZE
                    || !Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(bytes).equals(token)) {
                throw invalidCursor();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            TaskAssociationCursor cursor = new TaskAssociationCursor(
                    new OrganizationId(readUuid(buffer)),
                    new TeamId(readUuid(buffer)),
                    sourceType(buffer.get()),
                    readUuid(buffer),
                    UtcTimestamp.from(Instant.ofEpochSecond(buffer.getLong(), buffer.getInt())),
                    readUuid(buffer));
            cursor.requireSource(organizationId, teamId, sourceType, sourceId);
            return cursor;
        } catch (ApiRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    private static byte sourceType(TaskAssociationSourceType type) {
        return switch (type) {
            case WORK_ITEM -> 1;
            case CONVERSATION -> 2;
            case TASK -> 3;
        };
    }

    private static TaskAssociationSourceType sourceType(byte value) {
        return switch (value) {
            case 1 -> TaskAssociationSourceType.WORK_ITEM;
            case 2 -> TaskAssociationSourceType.CONVERSATION;
            case 3 -> TaskAssociationSourceType.TASK;
            default -> throw invalidCursor();
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
                "Cursor is invalid, unsupported, or belongs to another association source",
                Map.of("parameter", "after"));
    }
}
