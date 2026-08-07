package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

class WorkItemCommentTest {

    @Test
    void appendsNormalizedNativeMarkdownComment() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItem item = fixture.nativeWorkItem();

        WorkItemComment comment = WorkItemComment.addNative(
                WorkItemCommentId.generate(),
                item,
                fixture.owner,
                "  **Implementation** is ready.  ",
                WorkItemDomainFixture.CREATED_AT);

        assertEquals(item.id(), comment.workItemId());
        assertEquals(item.scope(), comment.scope());
        assertEquals("**Implementation** is ready.", comment.content());
        assertEquals(WorkItemSource.CREWSCOPE, comment.source());
        assertTrue(comment.externalId().isEmpty());
        assertEquals(fixture.owner.id(), comment.authorPrincipalId());
    }

    @Test
    void externalCommentRequiresProviderId() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItemComment comment = WorkItemComment.addExternalProjection(
                WorkItemCommentId.generate(),
                fixture.nativeWorkItem(),
                fixture.owner,
                "Synced comment",
                WorkItemSource.JIRA,
                "comment-88",
                WorkItemDomainFixture.CREATED_AT);

        assertEquals(WorkItemSource.JIRA, comment.source());
        assertEquals("comment-88", comment.externalId().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> WorkItemComment.addExternalProjection(
                        WorkItemCommentId.generate(),
                        fixture.nativeWorkItem(),
                        fixture.owner,
                        "Missing id",
                        WorkItemSource.JIRA,
                        " ",
                        WorkItemDomainFixture.CREATED_AT));
    }

    @Test
    void rejectsBlankAndOversizedContent() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> WorkItemComment.addNative(
                        WorkItemCommentId.generate(),
                        fixture.nativeWorkItem(),
                        fixture.owner,
                        " ",
                        WorkItemDomainFixture.CREATED_AT));
        DomainValidationException oversized = assertThrows(
                DomainValidationException.class,
                () -> WorkItemComment.addNative(
                        WorkItemCommentId.generate(),
                        fixture.nativeWorkItem(),
                        fixture.owner,
                        "x".repeat(WorkItemComment.MAX_CONTENT_LENGTH + 1),
                        WorkItemDomainFixture.CREATED_AT));

        assertEquals("workItemComment.content", oversized.error().details().get("field"));
    }

    @Test
    void rejectsAuthorOutsideWorkItemScope() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        Principal outside = WorkItemDomainFixture.activeUser(
                io.crewscope.domain.shared.id.OrganizationId.generate(), "Outside");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkItemComment.addNative(
                        WorkItemCommentId.generate(),
                        fixture.nativeWorkItem(),
                        outside,
                        "Unauthorized",
                        WorkItemDomainFixture.CREATED_AT));

        assertEquals(
                "workItemComment.authorPrincipalId",
                failure.error().details().get("field"));
    }

    @Test
    void archivedWorkItemRejectsNewComment() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItem archived = fixture.nativeWorkItem()
                .transitionTo(
                        WorkItemStatus.CANCELLED,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:01:00Z"))
                .transitionTo(
                        WorkItemStatus.ARCHIVED,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:02:00Z"));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkItemComment.addNative(
                        WorkItemCommentId.generate(),
                        archived,
                        fixture.owner,
                        "Too late",
                        UtcTimestamp.parse("2026-08-07T22:03:00Z")));

        assertEquals("workItemComment.workItemId", failure.error().details().get("field"));
    }
}
