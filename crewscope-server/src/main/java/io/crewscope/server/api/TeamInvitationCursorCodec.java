package io.crewscope.server.api;

import io.crewscope.application.team.TeamInvitationCursor;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInvitationId;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Versioned opaque transport codec for invitation created-time/ID keyset positions. */
public final class TeamInvitationCursorCodec {

    private static final byte VERSION = 1;
    private static final int BINARY_SIZE = 1 + Long.BYTES + Integer.BYTES + 2 * Long.BYTES;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(TeamInvitationCursor cursor) {
        TeamInvitationCursor source = Objects.requireNonNull(cursor, "cursor");
        Instant instant = source.createdAt().value();
        UUID id = source.invitationId().value();
        ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE)
                .put(VERSION)
                .putLong(instant.getEpochSecond())
                .putInt(instant.getNano())
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public TeamInvitationCursor decode(String token) {
        if (token == null
                || token.isBlank()
                || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalidCursor();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != BINARY_SIZE
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(bytes)
                            .equals(token)) {
                throw invalidCursor();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            return new TeamInvitationCursor(
                    UtcTimestamp.from(Instant.ofEpochSecond(buffer.getLong(), buffer.getInt())),
                    new TeamInvitationId(new UUID(buffer.getLong(), buffer.getLong())));
        } catch (ApiRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    private static ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or unsupported",
                Map.of("parameter", "after"));
    }
}
