package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxCursor;
import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSourceStatus;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Canonical opaque Inbox cursor bound to route, filters and projection generation. */
public final class InboxCursorCodec {

    private static final byte VERSION = 1;
    private static final int TOKEN_BYTES = 95;
    private static final int MAX_TOKEN_LENGTH = 160;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(
            InboxCursor cursor,
            OrganizationId organizationId,
            TeamId teamId,
            InboxFilter filter) {
        InboxCursor value = java.util.Objects.requireNonNull(cursor, "cursor");
        ByteBuffer buffer = ByteBuffer.allocate(TOKEN_BYTES);
        buffer.put(VERSION);
        putUuid(buffer, organizationId.value());
        putUuid(buffer, teamId.value());
        buffer.putLong(value.generation().value());
        buffer.put((byte) value.priority().ordinal());
        buffer.put((byte) (value.deadline().isPresent() ? 1 : 0));
        putInstant(buffer, value.deadline().map(UtcTimestamp::value).orElse(Instant.EPOCH));
        putInstant(buffer, value.openedAt().value());
        putUuid(buffer, value.inboxItemId().value());
        buffer.putInt(mask(filter.itemTypes()));
        buffer.putInt(mask(filter.sourceStatuses()));
        buffer.putInt(mask(filter.dispositionStatuses()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public InboxCursor decode(
            String token,
            OrganizationId organizationId,
            TeamId teamId,
            InboxFilter filter) {
        try {
            if (token == null
                    || token.isBlank()
                    || token.length() > MAX_TOKEN_LENGTH
                    || !TOKEN_FORMAT.matcher(token).matches()) {
                throw invalid();
            }
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != TOKEN_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
                throw invalid();
            }
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            if (buffer.get() != VERSION
                    || !readUuid(buffer).equals(organizationId.value())
                    || !readUuid(buffer).equals(teamId.value())) {
                throw invalid();
            }
            ProjectionGeneration generation = new ProjectionGeneration(buffer.getLong());
            InboxPriority priority = enumAt(InboxPriority.values(), buffer.get());
            byte hasDeadline = buffer.get();
            if (hasDeadline != 0 && hasDeadline != 1) {
                throw invalid();
            }
            Instant deadlineValue = readInstant(buffer);
            Optional<UtcTimestamp> deadline = hasDeadline == 1
                    ? Optional.of(UtcTimestamp.from(deadlineValue))
                    : Optional.empty();
            if (hasDeadline == 0 && !deadlineValue.equals(Instant.EPOCH)) {
                throw invalid();
            }
            UtcTimestamp openedAt = UtcTimestamp.from(readInstant(buffer));
            InboxItemId itemId = new InboxItemId(readUuid(buffer));
            if (buffer.getInt() != mask(filter.itemTypes())
                    || buffer.getInt() != mask(filter.sourceStatuses())
                    || buffer.getInt() != mask(filter.dispositionStatuses())
                    || buffer.hasRemaining()) {
                throw invalid();
            }
            return new InboxCursor(generation, priority, deadline, openedAt, itemId);
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static <E extends Enum<E>> int mask(java.util.Set<E> values) {
        int mask = 0;
        for (E value : values) {
            mask |= 1 << value.ordinal();
        }
        return mask;
    }

    private static <E> E enumAt(E[] values, byte encoded) {
        int index = Byte.toUnsignedInt(encoded);
        if (index >= values.length) {
            throw invalid();
        }
        return values[index];
    }

    private static void putInstant(ByteBuffer buffer, Instant value) {
        buffer.putLong(value.getEpochSecond()).putInt(value.getNano());
    }

    private static Instant readInstant(ByteBuffer buffer) {
        return Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static ApiRequestException invalid() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another Inbox query",
                Map.of("parameter", "after"));
    }
}
