package io.crewscope.domain.workitem;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Immutable Markdown comment appended to one WorkItem. */
public final class WorkItemComment {

    public static final int MAX_CONTENT_LENGTH = 50_000;
    public static final int MAX_EXTERNAL_ID_LENGTH = 500;

    private final WorkItemCommentId id;
    private final WorkItemScope scope;
    private final WorkItemId workItemId;
    private final PrincipalId authorPrincipalId;
    private final String content;
    private final WorkItemSource source;
    private final Optional<String> externalId;
    private final AuditMetadata audit;

    private WorkItemComment(
            WorkItemCommentId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            PrincipalId authorPrincipalId,
            String content,
            WorkItemSource source,
            Optional<String> externalId,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.authorPrincipalId = Objects.requireNonNull(authorPrincipalId, "authorPrincipalId");
        this.content = requireContent(content);
        this.source = Objects.requireNonNull(source, "source");
        this.externalId = requireExternalId(this.source, externalId);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Appends a native comment after validating WorkItem lifecycle and author scope. */
    public static WorkItemComment addNative(
            WorkItemCommentId id,
            WorkItem workItem,
            Principal author,
            String content,
            UtcTimestamp occurredAt) {
        return create(
                id,
                workItem,
                author,
                content,
                WorkItemSource.CREWSCOPE,
                Optional.empty(),
                occurredAt);
    }

    /** Adds an externally sourced comment projection with its provider idempotency key. */
    public static WorkItemComment addExternalProjection(
            WorkItemCommentId id,
            WorkItem workItem,
            Principal actor,
            String content,
            WorkItemSource source,
            String externalId,
            UtcTimestamp occurredAt) {
        WorkItemSource requiredSource = Objects.requireNonNull(source, "source");
        if (requiredSource.isNative()) {
            throw new DomainValidationException(
                    "workItemComment.source", "must reference an external WorkItem provider");
        }
        return create(
                id,
                workItem,
                actor,
                content,
                requiredSource,
                Optional.ofNullable(externalId),
                occurredAt);
    }

    /** Reconstitutes an immutable committed comment. */
    public static WorkItemComment reconstitute(
            WorkItemCommentId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            PrincipalId authorPrincipalId,
            String content,
            WorkItemSource source,
            Optional<String> externalId,
            AuditMetadata audit) {
        return new WorkItemComment(
                id,
                scope,
                workItemId,
                authorPrincipalId,
                content,
                source,
                externalId,
                audit);
    }

    public WorkItemCommentId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public WorkItemId workItemId() {
        return workItemId;
    }

    public PrincipalId authorPrincipalId() {
        return authorPrincipalId;
    }

    public String content() {
        return content;
    }

    public WorkItemSource source() {
        return source;
    }

    public Optional<String> externalId() {
        return externalId;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static WorkItemComment create(
            WorkItemCommentId id,
            WorkItem workItem,
            Principal actor,
            String content,
            WorkItemSource source,
            Optional<String> externalId,
            UtcTimestamp occurredAt) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        if (!requiredWorkItem.acceptsCollaboration()) {
            throw new DomainValidationException(
                    "workItemComment.workItemId", "must not reference an archived WorkItem");
        }
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                actor,
                requiredWorkItem.scope().organizationId(),
                requiredWorkItem.scope().teamId(),
                "workItemComment.authorPrincipalId");
        return new WorkItemComment(
                id,
                requiredWorkItem.scope(),
                requiredWorkItem.id(),
                actorId,
                content,
                source,
                externalId,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("workItemComment.content", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new DomainValidationException(
                    "workItemComment.content",
                    "must contain at most " + MAX_CONTENT_LENGTH + " characters");
        }
        return normalized;
    }

    private static Optional<String> requireExternalId(
            WorkItemSource source, Optional<String> value) {
        Optional<String> normalized = Objects.requireNonNull(value, "externalId")
                .map(String::strip)
                .filter(text -> !text.isEmpty());
        if (normalized.filter(text -> text.length() > MAX_EXTERNAL_ID_LENGTH).isPresent()) {
            throw new DomainValidationException(
                    "workItemComment.externalId",
                    "must contain at most " + MAX_EXTERNAL_ID_LENGTH + " characters");
        }
        if (source.isNative() && normalized.isPresent()) {
            throw new DomainValidationException(
                    "workItemComment.externalId", "must be empty for a native comment");
        }
        if (!source.isNative() && normalized.isEmpty()) {
            throw new DomainValidationException(
                    "workItemComment.externalId", "is required for an external comment");
        }
        return normalized;
    }
}
