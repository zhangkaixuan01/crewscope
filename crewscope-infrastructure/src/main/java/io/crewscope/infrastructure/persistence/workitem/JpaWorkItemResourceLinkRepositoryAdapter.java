package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkItemResourceLinkRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Append-only JPA adapter for WorkItem resource links. */
@Repository
public class JpaWorkItemResourceLinkRepositoryAdapter implements WorkItemResourceLinkRepository {
    private final WorkPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaWorkItemResourceLinkRepositoryAdapter(WorkPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public WorkItemResourceLink create(WorkItemResourceLink value) {
        WorkItemResourceLink required = Objects.requireNonNull(value, "link");
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemResourceLink> findById(
            OrganizationId organizationId, WorkItemResourceLinkId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemResourceLinkEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        WorkItemResourceLinkEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItemResourceLink> findByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemResourceLinkEntity value
                        WHERE value.organizationId = :organizationId AND value.workItemId = :workItemId
                        ORDER BY value.createdAt, value.id
                        """,
                        WorkItemResourceLinkEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
