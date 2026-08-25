package io.crewscope.domain.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxDomainM6D02Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000401");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000402");
    private static final TeamMemberId MEMBER_ID =
            TeamMemberId.from("00000000-0000-0000-0000-000000000403");
    private static final PrincipalId PRINCIPAL_ID =
            PrincipalId.from("00000000-0000-0000-0000-000000000404");
    private static final UUID SOURCE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000405");
    private static final UtcTimestamp OPENED_AT =
            UtcTimestamp.parse("2026-08-25T09:00:00Z");
    private static final UtcTimestamp CLOSED_AT =
            UtcTimestamp.parse("2026-08-25T10:00:00Z");
    private static final ProjectionName PROJECTION_NAME = new ProjectionName("member-inbox");

    @Test
    void derivesTheSameItemAcrossGenerationsAndSeparatesNewSourceRevision() {
        InboxSource source = source(
                MEMBER_ID,
                InboxItemType.REVIEW,
                InboxSourceType.REVIEW_REQUEST,
                InboxSourceRevision.INITIAL);
        InboxItem first = item(source, ProjectionGeneration.FIRST);
        InboxItem duplicate = item(source, ProjectionGeneration.FIRST);
        InboxItem rebuilt = item(source, new ProjectionGeneration(2));
        InboxItem revised = item(
                source(
                        MEMBER_ID,
                        InboxItemType.REVIEW,
                        InboxSourceType.REVIEW_REQUEST,
                        InboxSourceRevision.INITIAL.next()),
                new ProjectionGeneration(2));

        assertEquals(first, duplicate);
        assertEquals(first.id(), rebuilt.id());
        assertNotEquals(first.projectionGeneration(), rebuilt.projectionGeneration());
        assertNotEquals(first.id(), revised.id());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InboxItem(
                        new InboxItemId(UUID.randomUUID()),
                        TEAM_ID,
                        PROJECTION_NAME,
                        ProjectionGeneration.FIRST,
                        SchemaVersion.V1,
                        source));
    }

    @Test
    void closesResponsibilityChangesWithoutDeletingTheHistoricalSource() {
        InboxItem ownership = item(
                source(
                        MEMBER_ID,
                        InboxItemType.OWNERSHIP,
                        InboxSourceType.RESPONSIBILITY_ASSIGNMENT,
                        InboxSourceRevision.INITIAL),
                ProjectionGeneration.FIRST);
        InboxItem closed = ownership.close(InboxCloseReason.RESPONSIBILITY_REPLACED, CLOSED_AT);
        InboxItem replacement = item(
                source(
                        TeamMemberId.generate(),
                        InboxItemType.OWNERSHIP,
                        InboxSourceType.RESPONSIBILITY_ASSIGNMENT,
                        InboxSourceRevision.INITIAL),
                ProjectionGeneration.FIRST);

        assertFalse(closed.source().isOpen());
        assertEquals(ownership.id(), closed.id());
        assertEquals(
                InboxCloseReason.RESPONSIBILITY_REPLACED,
                closed.source().closeReason().orElseThrow());
        assertNotEquals(closed.id(), replacement.id());
        assertSame(
                closed,
                closed.close(InboxCloseReason.RESPONSIBILITY_REPLACED, CLOSED_AT));
    }

    @Test
    void closesReviewConfirmationAndExceptionWithTypedReasons() {
        InboxItem review = item(
                source(
                        MEMBER_ID,
                        InboxItemType.REVIEW,
                        InboxSourceType.REVIEW_REQUEST,
                        InboxSourceRevision.INITIAL),
                ProjectionGeneration.FIRST);
        InboxItem confirmation = item(
                source(
                        MEMBER_ID,
                        InboxItemType.CONFIRMATION,
                        InboxSourceType.ACTION_CONFIRMATION,
                        InboxSourceRevision.INITIAL),
                ProjectionGeneration.FIRST);
        InboxItem exception = item(
                source(
                        MEMBER_ID,
                        InboxItemType.EXCEPTION,
                        InboxSourceType.TASK_EXECUTION,
                        InboxSourceRevision.INITIAL),
                ProjectionGeneration.FIRST);

        assertEquals(
                InboxCloseReason.REVIEW_SUPERSEDED,
                review.close(InboxCloseReason.REVIEW_SUPERSEDED, CLOSED_AT)
                        .source()
                        .closeReason()
                        .orElseThrow());
        assertEquals(
                InboxCloseReason.CONFIRMATION_EXPIRED,
                confirmation.close(InboxCloseReason.CONFIRMATION_EXPIRED, CLOSED_AT)
                        .source()
                        .closeReason()
                        .orElseThrow());
        assertEquals(
                InboxCloseReason.EXCEPTION_RECOVERED,
                exception.close(InboxCloseReason.EXCEPTION_RECOVERED, CLOSED_AT)
                        .source()
                        .closeReason()
                        .orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> review.close(InboxCloseReason.EXCEPTION_RECOVERED, CLOSED_AT));
    }

    @Test
    void validatesSourceTypeDeadlineAndRevisionBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> key(
                        MEMBER_ID,
                        InboxItemType.REVIEW,
                        InboxSourceType.ACTION_CONFIRMATION,
                        InboxSourceRevision.INITIAL));
        assertThrows(
                DomainValidationException.class,
                () -> InboxSource.open(
                        key(
                                MEMBER_ID,
                                InboxItemType.REVIEW,
                                InboxSourceType.REVIEW_REQUEST,
                                InboxSourceRevision.INITIAL),
                        InboxPriority.HIGH,
                        Optional.of(UtcTimestamp.parse("2026-08-25T08:59:59Z")),
                        OPENED_AT));
        assertThrows(IllegalArgumentException.class, () -> new InboxSourceRevision(-1));
        assertThrows(
                IllegalStateException.class,
                () -> new InboxSourceRevision(Long.MAX_VALUE).next());
    }

    @Test
    void enforcesMonotonicDispositionAndExactVersion() {
        InboxItem item = item(
                source(
                        MEMBER_ID,
                        InboxItemType.REVIEW,
                        InboxSourceType.REVIEW_REQUEST,
                        InboxSourceRevision.INITIAL),
                ProjectionGeneration.FIRST);
        InboxDisposition read = InboxDisposition.create(
                item, InboxDispositionStatus.READ, 0, PRINCIPAL_ID, CLOSED_AT);
        InboxDisposition acted = read.transitionTo(
                InboxDispositionStatus.ACTED, 1, PRINCIPAL_ID, CLOSED_AT);
        InboxDisposition archived = acted.transitionTo(
                InboxDispositionStatus.ARCHIVED, 2, PRINCIPAL_ID, CLOSED_AT);

        assertEquals(1, read.version());
        assertEquals(3, archived.version());
        assertTrue(archived.belongsTo(item));
        assertSame(
                archived,
                archived.transitionTo(
                        InboxDispositionStatus.ARCHIVED, 3, PRINCIPAL_ID, CLOSED_AT));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> read.transitionTo(
                        InboxDispositionStatus.ACTED, 0, PRINCIPAL_ID, CLOSED_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> acted.transitionTo(
                        InboxDispositionStatus.READ, 2, PRINCIPAL_ID, CLOSED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> InboxDisposition.create(
                        item, InboxDispositionStatus.UNREAD, 0, PRINCIPAL_ID, CLOSED_AT));
    }

    private static InboxSource source(
            TeamMemberId memberId,
            InboxItemType itemType,
            InboxSourceType sourceType,
            InboxSourceRevision revision) {
        return InboxSource.open(
                key(memberId, itemType, sourceType, revision),
                InboxPriority.HIGH,
                Optional.of(UtcTimestamp.parse("2026-08-26T09:00:00Z")),
                OPENED_AT);
    }

    private static InboxSourceKey key(
            TeamMemberId memberId,
            InboxItemType itemType,
            InboxSourceType sourceType,
            InboxSourceRevision revision) {
        return new InboxSourceKey(
                ORGANIZATION_ID, memberId, itemType, sourceType, SOURCE_ID, revision);
    }

    private static InboxItem item(InboxSource source, ProjectionGeneration generation) {
        return InboxItem.project(
                TEAM_ID, PROJECTION_NAME, generation, SchemaVersion.V1, source);
    }
}
