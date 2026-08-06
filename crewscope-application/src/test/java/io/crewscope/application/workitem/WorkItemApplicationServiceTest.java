package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkItemApplicationServiceTest {

    @Test
    void createsACommandScopedAndAuditedAggregateUsingTheInjectedClock() {
        CapturingRepository repository = new CapturingRepository();
        TimeProvider timeProvider = TimeProvider.from(
                Clock.fixed(Instant.parse("2026-08-06T12:34:56.123456789Z"), ZoneOffset.UTC));
        WorkItemApplicationService service =
                new WorkItemApplicationService(repository, timeProvider);
        WorkItemCommandContext context = new WorkItemCommandContext(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                PrincipalId.generate());
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                WorkProjectId.generate(), "CRW-42", "Repository baseline");

        WorkItem created = service.create(context, command);

        assertEquals(context.organizationId(), created.scope().organizationId());
        assertEquals(context.teamId(), created.scope().teamId());
        assertEquals(context.workspaceId(), created.scope().workspaceId());
        assertEquals(command.projectId(), created.scope().projectId());
        assertEquals(context.actorId(), created.audit().createdBy().orElseThrow());
        assertEquals(
                UtcTimestamp.parse("2026-08-06T12:34:56.123456Z"),
                created.audit().createdAt());
        assertEquals(created, repository.created);
    }

    @Test
    void rejectsAnOversizedTitleBeforeCallingTheRepository() {
        CapturingRepository repository = new CapturingRepository();
        WorkItemApplicationService service = new WorkItemApplicationService(
                repository,
                () -> UtcTimestamp.parse("2026-08-06T12:34:56Z"));
        WorkItemCommandContext context = new WorkItemCommandContext(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                PrincipalId.generate());
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                WorkProjectId.generate(),
                "CRW-43",
                "x".repeat(WorkItem.MAX_TITLE_LENGTH + 1));

        assertThrows(DomainValidationException.class, () -> service.create(context, command));
        assertNull(repository.created);
    }

    private static final class CapturingRepository implements WorkItemRepository {

        private WorkItem created;

        @Override
        public WorkItem create(WorkItem workItem) {
            created = workItem;
            return workItem;
        }

        @Override
        public WorkItem update(WorkItem workItem) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkItem> findById(
                OrganizationId organizationId, WorkItemId id) {
            return Optional.empty();
        }

        @Override
        public WorkItemPage findPage(WorkItemQuery query) {
            return new WorkItemPage(java.util.List.of(), Optional.empty());
        }
    }
}
