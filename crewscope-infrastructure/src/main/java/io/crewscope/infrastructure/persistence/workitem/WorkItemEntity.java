package io.crewscope.infrastructure.persistence.workitem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA persistence model for the complete M1 WorkItem snapshot.
 *
 * <p>Scope identifiers remain scalar UUID columns so the adapter cannot cross tenant boundaries
 * through implicit ORM relationships. M1 extends the product fields while retaining this mapping.
 */
@Entity
@Table(name = "work_item", schema = "crewscope")
public class WorkItemEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "item_key", nullable = false, length = 32)
    private String itemKey;

    @Column(name = "item_type", nullable = false, length = 32)
    private String itemType;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "priority", nullable = false, length = 32)
    private String priority;

    @Column(name = "source_provider", nullable = false, length = 32)
    private String sourceProvider;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "labels", nullable = false, columnDefinition = "jsonb")
    private List<String> labels;

    @Column(name = "due_at")
    private Instant dueAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id")
    private UUID createdByPrincipalId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id")
    private UUID updatedByPrincipalId;

    protected WorkItemEntity() {}

    WorkItemEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            String itemKey,
            String itemType,
            String title,
            String description,
            String status,
            String priority,
            String sourceProvider,
            String sourceRef,
            List<String> labels,
            Instant dueAt,
            long version,
            Instant createdAt,
            UUID createdByPrincipalId,
            Instant updatedAt,
            UUID updatedByPrincipalId) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.itemKey = itemKey;
        this.itemType = itemType;
        this.title = title;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.sourceProvider = sourceProvider;
        this.sourceRef = sourceRef;
        this.labels = List.copyOf(labels);
        this.dueAt = dueAt;
        this.version = version;
        this.createdAt = createdAt;
        this.createdByPrincipalId = createdByPrincipalId;
        this.updatedAt = updatedAt;
        this.updatedByPrincipalId = updatedByPrincipalId;
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    UUID teamId() {
        return teamId;
    }

    UUID workspaceId() {
        return workspaceId;
    }

    UUID projectId() {
        return projectId;
    }

    String itemKey() {
        return itemKey;
    }

    String itemType() {
        return itemType;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    String status() {
        return status;
    }

    String priority() {
        return priority;
    }

    String sourceProvider() {
        return sourceProvider;
    }

    String sourceRef() {
        return sourceRef;
    }

    List<String> labels() {
        return labels;
    }

    Instant dueAt() {
        return dueAt;
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    UUID createdByPrincipalId() {
        return createdByPrincipalId;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    UUID updatedByPrincipalId() {
        return updatedByPrincipalId;
    }
}
