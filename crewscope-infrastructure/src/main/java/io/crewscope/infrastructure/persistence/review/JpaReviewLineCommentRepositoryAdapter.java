package io.crewscope.infrastructure.persistence.review;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;
import io.crewscope.application.review.ReviewLineCommentRepository;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.review.FindingLocation;
import io.crewscope.domain.review.ReviewCommentAnchor;
import io.crewscope.domain.review.ReviewCommentAnchorState;
import io.crewscope.domain.review.ReviewCommentSide;
import io.crewscope.domain.review.ReviewLineComment;
import io.crewscope.domain.review.ReviewLineCommentId;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter with tenant predicates and optimistic version checks for line comments. */
@Repository
public class JpaReviewLineCommentRepositoryAdapter implements ReviewLineCommentRepository {
    @PersistenceContext private EntityManager entityManager;

    @Override @Transactional
    public ReviewLineComment create(ReviewLineComment value, String idempotencyKey) {
        entityManager.persist(toEntity(value, idempotencyKey));
        entityManager.flush();
        return value;
    }

    @Override @Transactional
    public ReviewLineComment update(ReviewLineComment value, long expectedVersion, String idempotencyKey) {
        var scope = value.scope();
        var anchor = value.anchor();
        var audit = value.audit();
        int affected = entityManager.createQuery("""
                UPDATE ReviewLineCommentEntity item
                   SET item.filePath = :filePath,
                       item.side = :side,
                       item.lineNumber = :lineNumber,
                       item.hunkHeader = :hunkHeader,
                       item.lineContentHash = :lineContentHash,
                       item.diffGeneration = :diffGeneration,
                       item.content = :content,
                       item.anchorState = :anchorState,
                       item.deleted = :deleted,
                       item.version = :version,
                       /* The create key is immutable: an update must not free it for reuse. */
                       item.lastCommandIdempotencyKey = :lastCommandKey,
                       item.updatedAt = :updatedAt,
                       item.updatedBy = :updatedBy
                 WHERE item.organizationId = :organizationId
                   AND item.teamId = :teamId
                   AND item.workspaceId = :workspaceId
                   AND item.projectId = :projectId
                   AND item.id = :id
                   AND item.version = :expectedVersion
                """)
                .setParameter("filePath", anchor.location().path().value())
                .setParameter("side", anchor.side().name())
                .setParameter("lineNumber", anchor.location().startLine())
                .setParameter("hunkHeader", anchor.hunkHeader())
                .setParameter("lineContentHash", anchor.lineContentHash().value())
                .setParameter("diffGeneration", anchor.diffGeneration().value())
                .setParameter("content", value.content())
                .setParameter("anchorState", value.anchorState().name())
                .setParameter("deleted", value.deleted())
                .setParameter("version", value.version())
                .setParameter("lastCommandKey", Objects.requireNonNull(idempotencyKey, "idempotencyKey"))
                .setParameter("updatedAt", audit.updatedAt().value())
                .setParameter("updatedBy", audit.updatedBy().orElse(value.authorPrincipalId()).value())
                .setParameter("organizationId", scope.organizationId().value())
                .setParameter("teamId", scope.teamId().value())
                .setParameter("workspaceId", scope.workspaceId().value())
                .setParameter("projectId", scope.projectId().value())
                .setParameter("id", value.id().value())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        entityManager.clear();
        if (affected == 0) {
            var actual = entityManager.createQuery("""
                    SELECT item.version FROM ReviewLineCommentEntity item
                     WHERE item.organizationId = :organizationId
                       AND item.teamId = :teamId
                       AND item.workspaceId = :workspaceId
                       AND item.projectId = :projectId
                       AND item.id = :id
                    """, Long.class)
                    .setParameter("organizationId", scope.organizationId().value())
                    .setParameter("teamId", scope.teamId().value())
                    .setParameter("workspaceId", scope.workspaceId().value())
                    .setParameter("projectId", scope.projectId().value())
                    .setParameter("id", value.id().value())
                    .getResultStream().findFirst();
            if (actual.isEmpty()) {
                throw new io.crewscope.domain.shared.error.AggregateNotFoundException("ReviewLineComment", value.id());
            }
            throw new io.crewscope.domain.shared.error.OptimisticLockConflictException(
                    "ReviewLineComment", value.id(), expectedVersion, actual.orElseThrow());
        }
        entityManager.flush();
        return value;
    }

    @Override @Transactional(readOnly = true)
    public Optional<ReviewLineComment> findById(OrganizationId organizationId, ReviewLineCommentId id) {
        return entityManager.createQuery("SELECT v FROM ReviewLineCommentEntity v WHERE v.organizationId = :org AND v.id = :id", ReviewLineCommentEntity.class)
                .setParameter("org", organizationId.value()).setParameter("id", id.value()).getResultStream().findFirst().map(this::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public Optional<ReviewLineComment> findByIdempotencyKey(OrganizationId organizationId, String key) {
        /*
         * Both slots: a create replay matches the immutable create key, an edit replay matches the
         * newest update key. Reusing either key for the opposite command therefore also lands here,
         * where the command service compares the stored comment against the request and refuses it.
         */
        return entityManager.createQuery("SELECT v FROM ReviewLineCommentEntity v WHERE v.organizationId = :org"
                + " AND (v.idempotencyKey = :key OR v.lastCommandIdempotencyKey = :key)", ReviewLineCommentEntity.class)
                .setParameter("org", organizationId.value()).setParameter("key", key).getResultStream().findFirst().map(this::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public List<ReviewLineComment> findByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId, String afterId, int limit) {
        String jpql = "SELECT v FROM ReviewLineCommentEntity v WHERE v.organizationId = :org"
                + " AND v.reviewRequestId = :request"
                + (afterId == null ? "" : " AND v.id > :after") + " ORDER BY v.id";
        var query = entityManager.createQuery(jpql, ReviewLineCommentEntity.class)
                .setParameter("org", organizationId.value())
                .setParameter("request", reviewRequestId.value())
                .setMaxResults(limit);
        if (afterId != null) query.setParameter("after", java.util.UUID.fromString(afterId));
        return query.getResultList().stream().map(this::toDomain).toList();
    }

    private ReviewLineCommentEntity toEntity(ReviewLineComment v, String key) {
        var s=v.scope(); var a=v.anchor(); var audit=v.audit();
        return new ReviewLineCommentEntity(
                v.id().value(), s.organizationId().value(), s.teamId().value(), s.workspaceId().value(),
                s.projectId().value(), v.taskId().value(), v.taskExecutionId().value(), v.attempt(),
                v.reviewRequestId().value(), a.location().path().value(), a.side().name(),
                a.location().startLine(), a.hunkHeader(), a.lineContentHash().value(),
                a.diffGeneration().value(), v.content(), v.authorPrincipalId().value(),
                v.anchorState().name(), v.deleted(), v.version(), key, null, audit.createdAt().value(),
                audit.createdBy().orElse(v.authorPrincipalId()).value(), audit.updatedAt().value(),
                audit.updatedBy().orElse(v.authorPrincipalId()).value());
    }

    private ReviewLineComment toDomain(ReviewLineCommentEntity v) {
        WorkItemScope scope = new WorkItemScope(
                new OrganizationId(v.organizationId()), new TeamId(v.teamId()),
                new WorkspaceId(v.workspaceId()), new WorkProjectId(v.projectId()));
        ReviewCommentAnchor anchor = new ReviewCommentAnchor(
                new FindingLocation(new DiffPath(v.filePath()), v.lineNumber(), v.lineNumber()),
                ReviewCommentSide.valueOf(v.side()), v.hunkHeader(),
                new io.crewscope.domain.task.RuntimeContentHash(v.lineContentHash()),
                new DiffGeneration(v.diffGeneration()));
        return ReviewLineComment.reconstitute(
                new ReviewLineCommentId(v.id()), scope, new ReviewRequestId(v.reviewRequestId()),
                new io.crewscope.domain.task.TaskId(v.taskId()), v.attempt(),
                new TaskExecutionId(v.taskExecutionId()), new PrincipalId(v.authorPrincipalId()), anchor,
                v.content(), ReviewCommentAnchorState.valueOf(v.anchorState()), v.deleted(), v.version(),
                audit(v.createdBy(), v.createdAt(), v.updatedBy(), v.updatedAt()));
    }
}
