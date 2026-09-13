package io.crewscope.infrastructure.persistence.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Scalar JPA representation of a Review line comment; no patch or credential data is stored. */
@Entity
@Table(name = "review_line_comment", schema = "crewscope")
public class ReviewLineCommentEntity {
    @Id private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "team_id", nullable = false) private UUID teamId;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "task_id", nullable = false) private UUID taskId;
    @Column(name = "task_execution_id", nullable = false) private UUID taskExecutionId;
    @Column(name = "attempt", nullable = false) private int attempt;
    @Column(name = "review_request_id", nullable = false) private UUID reviewRequestId;
    @Column(name = "file_path", nullable = false) private String filePath;
    @Column(name = "side", nullable = false) private String side;
    @Column(name = "line_number", nullable = false) private int lineNumber;
    @Column(name = "hunk_header", nullable = false) private String hunkHeader;
    @Column(name = "line_content_hash", nullable = false, length = 64, columnDefinition = "char(64)") private String lineContentHash;
    @Column(name = "diff_generation", nullable = false) private long diffGeneration;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Column(name = "author_principal_id", nullable = false) private UUID authorPrincipalId;
    @Column(name = "anchor_state", nullable = false) private String anchorState;
    @Column(nullable = false) private boolean deleted;
    @Column(nullable = false) private long version;
    @Column(name = "idempotency_key", nullable = false, length = 200) private String idempotencyKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) private UUID createdBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) private UUID updatedBy;

    protected ReviewLineCommentEntity() {}

    public ReviewLineCommentEntity(UUID id, UUID organizationId, UUID teamId, UUID workspaceId,
            UUID projectId, UUID taskId, UUID taskExecutionId, int attempt, UUID reviewRequestId,
            String filePath, String side, int lineNumber, String hunkHeader, String lineContentHash,
            long diffGeneration, String content, UUID authorPrincipalId, String anchorState,
            boolean deleted, long version, String idempotencyKey, Instant createdAt, UUID createdBy,
            Instant updatedAt, UUID updatedBy) {
        this.id=id; this.organizationId=organizationId; this.teamId=teamId; this.workspaceId=workspaceId;
        this.projectId=projectId; this.taskId=taskId; this.taskExecutionId=taskExecutionId; this.attempt=attempt;
        this.reviewRequestId=reviewRequestId; this.filePath=filePath; this.side=side; this.lineNumber=lineNumber;
        this.hunkHeader=hunkHeader; this.lineContentHash=lineContentHash; this.diffGeneration=diffGeneration;
        this.content=content; this.authorPrincipalId=authorPrincipalId; this.anchorState=anchorState;
        this.deleted=deleted; this.version=version; this.idempotencyKey=idempotencyKey; this.createdAt=createdAt;
        this.createdBy=createdBy; this.updatedAt=updatedAt; this.updatedBy=updatedBy;
    }
    UUID id(){return id;} UUID organizationId(){return organizationId;} UUID teamId(){return teamId;}
    UUID workspaceId(){return workspaceId;} UUID projectId(){return projectId;} UUID taskId(){return taskId;}
    UUID taskExecutionId(){return taskExecutionId;} int attempt(){return attempt;} UUID reviewRequestId(){return reviewRequestId;}
    String filePath(){return filePath;} String side(){return side;} int lineNumber(){return lineNumber;}
    String hunkHeader(){return hunkHeader;} String lineContentHash(){return lineContentHash;} long diffGeneration(){return diffGeneration;}
    String content(){return content;} UUID authorPrincipalId(){return authorPrincipalId;} String anchorState(){return anchorState;}
    boolean deleted(){return deleted;} long version(){return version;} String idempotencyKey(){return idempotencyKey;}
    Instant createdAt(){return createdAt;} UUID createdBy(){return createdBy;} Instant updatedAt(){return updatedAt;} UUID updatedBy(){return updatedBy;}
}
