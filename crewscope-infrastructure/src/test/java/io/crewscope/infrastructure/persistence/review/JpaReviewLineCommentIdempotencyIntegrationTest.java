package io.crewscope.infrastructure.persistence.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.review.ReviewLineCommentRepository;
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
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The receipt key of a comment is a write-path invariant, not a formatting detail: the create key
 * has to stay queryable for as long as the comment exists, otherwise a client that retries its
 * original create after an edit no longer matches a receipt and silently writes a second comment.
 *
 * <p>This is the persistence half of {@code ReviewLineCommentCommandService}'s replay branch. The
 * command service compares the stored comment against the request, so the only thing it needs from
 * here is that the lookup still finds the row.
 */
@SpringBootTest(
        classes = JpaReviewLineCommentIdempotencyIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false",
            /*
             * The receipt columns are what this test is about, so the comment is persisted without
             * the ReviewRequest and Principal graph the production path would have created first.
             */
            "spring.datasource.hikari.connection-init-sql=SET session_replication_role = replica"
        })
class JpaReviewLineCommentIdempotencyIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-09-17T02:00:00Z");
    private static final UtcTimestamp EDITED_AT = UtcTimestamp.parse("2026-09-17T02:05:00Z");
    private static final String CREATE_KEY = "m9-f5-create-key";
    private static final String EDIT_KEY = "m9-f5-edit-key";
    private static final String CONTENT = "已确认：这一行保留了旧分支的空值判断";
    private static final String EDITED_CONTENT = "已确认：这一行保留了旧分支的空值判断（补充）";

    @Autowired
    private ReviewLineCommentRepository comments;

    @Autowired
    private JdbcTemplate jdbc;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final ReviewRequestId requestId = ReviewRequestId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(), PrincipalScope.team(organizationId, teamId), PrincipalType.USER,
            Optional.empty(), "Comment author", Optional.empty(), PrincipalVisibility.TEAM,
            CREATED_AT);

    @Test
    void keepsTheCreateReceiptReachableAfterALaterEdit() {
        ReviewLineComment created = comments.create(comment(CONTENT), CREATE_KEY);

        comments.update(created.edit(actor, 0, EDITED_CONTENT, EDITED_AT), 0, EDIT_KEY);

        assertEquals(
                created.id(),
                comments.findByIdempotencyKey(organizationId, CREATE_KEY).orElseThrow().id(),
                "the create key must still resolve after an edit stored its own key");
    }

    @Test
    void refusesASecondCreateThatReusesTheReceiptKeyAfterALaterEdit() {
        ReviewLineComment created = comments.create(comment(CONTENT), CREATE_KEY);
        ReviewLineComment edited = created.edit(actor, 0, EDITED_CONTENT, EDITED_AT);
        comments.update(edited, 0, EDIT_KEY);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> comments.create(comment(CONTENT), CREATE_KEY),
                "replaying the create key must not be able to write a duplicate comment");
        assertEquals(1, countComments(), "a replayed create must not add a row");
    }

    @Test
    void stillReplaysAnEditByItsOwnKey() {
        ReviewLineComment created = comments.create(comment(CONTENT), CREATE_KEY);

        comments.update(created.edit(actor, 0, EDITED_CONTENT, EDITED_AT), 0, EDIT_KEY);

        assertEquals(
                created.id(),
                comments.findByIdempotencyKey(organizationId, EDIT_KEY).orElseThrow().id(),
                "the edit key has to resolve for the edit replay branch to fire");
    }

    private int countComments() {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.review_line_comment", Integer.class);
        return total == null ? 0 : total;
    }

    private ReviewLineComment comment(String content) {
        WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        ReviewCommentAnchor anchor = new ReviewCommentAnchor(
                new FindingLocation(new DiffPath("src/main/java/App.java"), 100, 100),
                ReviewCommentSide.NEW, "@@ -40 +100 @@", RuntimeContentHash.sha256("new value"),
                DiffGeneration.first());
        return ReviewLineComment.add(
                ReviewLineCommentId.generate(), scope, requestId, taskId, 1, executionId, anchor,
                actor, content, CREATED_AT);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "io.crewscope.infrastructure.persistence.review")
    @Import(JpaReviewLineCommentRepositoryAdapter.class)
    static class TestApplication {}
}
