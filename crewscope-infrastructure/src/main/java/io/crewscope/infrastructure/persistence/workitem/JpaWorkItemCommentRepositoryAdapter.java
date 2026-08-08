package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkItemCommentRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Append-only JPA adapter for WorkItem comments. */
@Repository
public class JpaWorkItemCommentRepositoryAdapter implements WorkItemCommentRepository {
    private final WorkPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaWorkItemCommentRepositoryAdapter(WorkPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public WorkItemComment create(WorkItemComment value) {
        WorkItemComment required = Objects.requireNonNull(value, "comment");
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemComment> findById(OrganizationId organizationId, WorkItemCommentId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemCommentEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        WorkItemCommentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItemComment> findByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemCommentEntity value
                        WHERE value.organizationId = :organizationId AND value.workItemId = :workItemId
                        ORDER BY value.createdAt, value.id
                        """,
                        WorkItemCommentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
