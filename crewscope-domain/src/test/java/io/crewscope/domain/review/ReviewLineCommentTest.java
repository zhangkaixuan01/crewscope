package io.crewscope.domain.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewLineCommentTest {
    @Test
    void enforcesAuthorVersionAndSoftDelete() {
        OrganizationId organization = OrganizationId.generate();
        Principal author = Principal.create(io.crewscope.domain.shared.id.PrincipalId.generate(),
                PrincipalScope.organization(organization), PrincipalType.USER, Optional.empty(), "Author",
                Optional.empty(), PrincipalVisibility.ORGANIZATION, UtcTimestamp.parse("2026-01-01T00:00:00Z"));
        WorkItemScope scope = new WorkItemScope(organization, TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
        ReviewLineComment value = ReviewLineComment.add(ReviewLineCommentId.generate(), scope,
                ReviewRequestId.generate(), TaskId.generate(), 1, TaskExecutionId.generate(),
                new ReviewCommentAnchor(new FindingLocation("src/App.java", 3, 3), ReviewCommentSide.NEW,
                        "@@ -1 +1 @@", RuntimeContentHash.sha256("return 1;"), DiffGeneration.first()),
                author, "Please add a test", UtcTimestamp.parse("2026-01-01T00:00:00Z"));
        ReviewLineComment deleted = value.delete(author, 0, UtcTimestamp.parse("2026-01-01T00:01:00Z"));
        assertEquals(1, deleted.version());
        assertEquals("Please add a test", deleted.content());
        assertThrows(RuntimeException.class, () -> deleted.edit(author, 1, "changed", UtcTimestamp.parse("2026-01-01T00:02:00Z")));
    }
}
