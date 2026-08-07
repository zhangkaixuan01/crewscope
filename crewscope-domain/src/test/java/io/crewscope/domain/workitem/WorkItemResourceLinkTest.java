package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkItemResourceLinkTest {

    @Test
    void linksPullRequestWithNormalizedReferenceAndLabel() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkItem item = fixture.nativeWorkItem();

        WorkItemResourceLink link = WorkItemResourceLink.link(
                WorkItemResourceLinkId.generate(),
                item,
                WorkItemResourceType.PULL_REQUEST,
                "  github:crewscope/crewscope-java#42  ",
                Optional.of("  Draft PR  "),
                fixture.owner,
                WorkItemDomainFixture.CREATED_AT);

        assertEquals(item.id(), link.workItemId());
        assertEquals(item.scope(), link.scope());
        assertEquals("github:crewscope/crewscope-java#42", link.resourceReference());
        assertEquals("Draft PR", link.label().orElseThrow());
        assertEquals(fixture.owner.id(), link.audit().createdBy().orElseThrow());
    }

    @Test
    void rejectsBlankOrOversizedReference() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> WorkItemResourceLink.link(
                        WorkItemResourceLinkId.generate(),
                        fixture.nativeWorkItem(),
                        WorkItemResourceType.ARTIFACT,
                        " ",
                        Optional.empty(),
                        fixture.owner,
                        WorkItemDomainFixture.CREATED_AT));
        DomainValidationException oversized = assertThrows(
                DomainValidationException.class,
                () -> WorkItemResourceLink.link(
                        WorkItemResourceLinkId.generate(),
                        fixture.nativeWorkItem(),
                        WorkItemResourceType.EXTERNAL_URL,
                        "x".repeat(WorkItemResourceLink.MAX_REFERENCE_LENGTH + 1),
                        Optional.empty(),
                        fixture.owner,
                        WorkItemDomainFixture.CREATED_AT));

        assertEquals(
                "workItemResourceLink.resourceReference",
                oversized.error().details().get("field"));
    }

    @Test
    void rejectsCreatorOutsideWorkItemScope() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        Principal outside = WorkItemDomainFixture.activeUser(
                io.crewscope.domain.shared.id.OrganizationId.generate(), "Outside");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkItemResourceLink.link(
                        WorkItemResourceLinkId.generate(),
                        fixture.nativeWorkItem(),
                        WorkItemResourceType.TASK,
                        "task:123",
                        Optional.empty(),
                        outside,
                        WorkItemDomainFixture.CREATED_AT));

        assertEquals(
                "workItemResourceLink.createdByPrincipalId",
                failure.error().details().get("field"));
    }

    @Test
    void archivedWorkItemRejectsNewResourceLink() {
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
                () -> WorkItemResourceLink.link(
                        WorkItemResourceLinkId.generate(),
                        archived,
                        WorkItemResourceType.ARTIFACT,
                        "artifact:123",
                        Optional.empty(),
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:03:00Z")));

        assertEquals(
                "workItemResourceLink.workItemId",
                failure.error().details().get("field"));
    }
}
