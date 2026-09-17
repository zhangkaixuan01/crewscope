package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.review.FindingLocation;
import io.crewscope.domain.review.ReviewCommentAnchor;
import io.crewscope.domain.review.ReviewCommentSide;
import io.crewscope.domain.review.ReviewLineComment;
import io.crewscope.domain.review.ReviewLineCommentId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The domain stores comment content stripped, so the command must carry the same form: a client that
 * retries the identical request with surrounding whitespace has to get its original receipt back
 * instead of "the key is already used by another comment request".
 */
class ReviewLineCommentCommandServiceIdempotencyTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-17T02:00:00Z");
    private static final String KEY = "m9-agentId-f3-replay";
    private static final String CONTENT = "已确认：这一行保留了旧分支的空值判断";

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final ReviewRequestId requestId = ReviewRequestId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
    private final Principal actor = Principal.create(
            PrincipalId.generate(), PrincipalScope.team(organizationId, teamId), PrincipalType.USER,
            Optional.empty(), "Comment author", Optional.empty(), PrincipalVisibility.TEAM, NOW);
    private final ReviewCommentAnchor anchor = new ReviewCommentAnchor(
            new FindingLocation(new DiffPath("src/main/java/App.java"), 100, 100),
            ReviewCommentSide.NEW, "@@ -40 +100 @@", RuntimeContentHash.sha256("new value"), DiffGeneration.first());

    @Test
    void replaysTheOriginalReceiptWhenTheSameBodyRepeatsWithSurroundingWhitespace() {
        Fixture fixture = new Fixture();
        ReviewLineComment prior = fixture.stored();

        ReviewLineComment result = fixture.service.add(
                fixture.access(), organizationId, teamId, taskId, executionId, requestId,
                new AddReviewLineCommentCommand(anchor, "  " + CONTENT + " \n"), KEY);

        assertEquals(prior.id(), result.id());
        assertEquals(CONTENT, result.content());
    }

    @Test
    void stillRejectsAReplayThatCarriesDifferentContent() {
        Fixture fixture = new Fixture();
        fixture.stored();

        assertThrows(DomainValidationException.class, () -> fixture.service.add(
                fixture.access(), organizationId, teamId, taskId, executionId, requestId,
                new AddReviewLineCommentCommand(anchor, "换一种说法"), KEY));
    }

    @Test
    void normalisesWhitespaceOnlyContentToTheEmptyStringTheDomainRejects() {
        // Stripping happens in the command, so blank content reaches the domain as "" and is refused
        // there ("must not be blank") rather than being compared against a stored blank comment.
        assertEquals("", new AddReviewLineCommentCommand(anchor, " \n\t ").content());
    }

    /** The replay path only needs the request identity and the stored comment it compares against. */
    private final class Fixture {
        private final ReviewLineCommentRepository comments = mock(ReviewLineCommentRepository.class);
        private final ReviewLineCommentCommandService service;

        private Fixture() {
            WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
            ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
            ContextPackageRepository contexts = mock(ContextPackageRepository.class);
            ReviewRequest request = mock(ReviewRequest.class);
            when(request.id()).thenReturn(requestId);
            when(request.scope()).thenReturn(scope);
            when(request.taskId()).thenReturn(taskId);
            when(request.taskExecutionId()).thenReturn(executionId);
            when(request.attempt()).thenReturn(1);
            when(requests.findById(organizationId, requestId)).thenReturn(Optional.of(request));
            TimeProvider timeProvider = () -> NOW;
            service = new ReviewLineCommentCommandService(
                    comments, requests, contexts, accessPolicy, timeProvider);
        }

        private TeamAccessContext access() {
            return new TeamAccessContext(actor, false);
        }

        private ReviewLineComment stored() {
            ReviewLineComment prior = ReviewLineComment.add(
                    ReviewLineCommentId.generate(), scope, requestId, taskId, 1, executionId, anchor,
                    actor, CONTENT, NOW);
            when(comments.findByIdempotencyKey(organizationId, IdempotencyKey.from(KEY).value()))
                    .thenReturn(Optional.of(prior));
            return prior;
        }
    }
}
