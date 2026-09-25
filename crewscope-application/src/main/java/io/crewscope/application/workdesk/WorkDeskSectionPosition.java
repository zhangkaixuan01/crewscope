package io.crewscope.application.workdesk;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The keyset position one WorkDesk section continues from (M9b-A06).
 *
 * <p>Every section orders by one time-then-ID pair — an {@code (updated_at, id)}-shaped tail —
 * except the Inbox section, whose ordering is the member Inbox's own {@code (priority rank,
 * deadline, opened_at, item id)}. The position carries exactly those facts: the shared
 * {@code sortTime}/{@code sortId} pair plus the Inbox-only leading keys, which are empty for every
 * other section. The HTTP layer signs this value; the repository turns it back into the section's
 * keyset predicate.
 */
public record WorkDeskSectionPosition(
    String sectionKey,
    OptionalInt primaryRank,
    Optional<UtcTimestamp> deadline,
    UtcTimestamp sortTime,
    UUID sortId) {

    public WorkDeskSectionPosition {
        if (sectionKey == null || sectionKey.isBlank()) {
            throw new IllegalArgumentException("sectionKey must not be blank");
        }
        primaryRank = Objects.requireNonNull(primaryRank, "primaryRank");
        deadline = Objects.requireNonNull(deadline, "deadline");
        sortTime = Objects.requireNonNull(sortTime, "sortTime");
        sortId = Objects.requireNonNull(sortId, "sortId");
    }

    /** The non-Inbox shape: a bare time-then-ID tail. */
    public static WorkDeskSectionPosition of(String sectionKey, Instant sortTime, UUID sortId) {
        return new WorkDeskSectionPosition(
            sectionKey, OptionalInt.empty(), Optional.empty(),
            UtcTimestamp.from(sortTime), sortId);
    }

    /** The Inbox shape: the member Inbox's four ordering keys. */
    public static WorkDeskSectionPosition inbox(
        int priorityRank, Optional<UtcTimestamp> deadline, Instant openedAt, UUID inboxItemId) {
        return new WorkDeskSectionPosition(
            "INBOX", OptionalInt.of(priorityRank), deadline, UtcTimestamp.from(openedAt), inboxItemId);
    }
}
