package io.crewscope.server.api;

import io.crewscope.application.coding.query.CodingEvidenceCursor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Opaque keyset bound to one tenant, Task, attempt and evidence collection. */
public final class CodingEvidenceCursorCodec {

    private static final byte VERSION = 1;
    private static final int BINARY_SIZE = 1 + (16 * 5) + 1 + 8;
    private static final int MAX_TOKEN_LENGTH = 160;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(
            CodingEvidenceCursor cursor,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            Collection collection) {
        ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE).put(VERSION);
        putUuid(buffer, Objects.requireNonNull(organizationId).value());
        putUuid(buffer, Objects.requireNonNull(teamId).value());
        putUuid(buffer, Objects.requireNonNull(taskId).value());
        putUuid(buffer, Objects.requireNonNull(executionId).value());
        putUuid(buffer, Objects.requireNonNull(cursor).id());
        buffer.put(collection.code).putLong(cursor.sequence());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public CodingEvidenceCursor decode(
            String token,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            Collection collection) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH
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
            if (buffer.get() != VERSION
                    || !readUuid(buffer).equals(organizationId.value())
                    || !readUuid(buffer).equals(teamId.value())
                    || !readUuid(buffer).equals(taskId.value())
                    || !readUuid(buffer).equals(executionId.value())) {
                throw invalidCursor();
            }
            UUID id = readUuid(buffer);
            if (buffer.get() != collection.code) {
                throw invalidCursor();
            }
            return new CodingEvidenceCursor(buffer.getLong(), id);
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

    private static ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid, unsupported, or belongs to another Coding evidence stream",
                Map.of("parameter", "after"));
    }

    public enum Collection {
        COMMANDS((byte) 1),
        TEST_EVIDENCE((byte) 2);

        private final byte code;

        Collection(byte code) {
            this.code = code;
        }
    }
}
