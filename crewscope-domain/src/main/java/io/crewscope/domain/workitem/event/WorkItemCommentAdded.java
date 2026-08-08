package io.crewscope.domain.workitem.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemSource;
import java.util.Objects;
import java.util.UUID;

/** Version 1 business payload emitted after a native WorkItem comment is committed. */
public record WorkItemCommentAdded(
    UUID workItemId, UUID authorPrincipalId, String content, WorkItemSource source)
    implements DomainEvent {

  public WorkItemCommentAdded {
    workItemId = AggregateId.requireValue(workItemId, "WorkItemCommentAdded.workItemId");
    authorPrincipalId =
        AggregateId.requireValue(authorPrincipalId, "WorkItemCommentAdded.authorPrincipalId");
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("WorkItemCommentAdded.content must not be blank");
    }
    content = content.strip();
    if (content.length() > WorkItemComment.MAX_CONTENT_LENGTH) {
      throw new IllegalArgumentException("WorkItemCommentAdded.content is too long");
    }
    source = Objects.requireNonNull(source, "source");
  }

  public static WorkItemCommentAdded from(WorkItemComment comment) {
    WorkItemComment source = Objects.requireNonNull(comment, "comment");
    return new WorkItemCommentAdded(
        source.workItemId().value(),
        source.authorPrincipalId().value(),
        source.content(),
        source.source());
  }
}
