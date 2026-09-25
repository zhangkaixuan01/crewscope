package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.crewscope.application.workitem.UpdateWorkItemContentCommand;
import io.crewscope.application.workitem.UpdateWorkItemContentCommand.Field;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/** Setter presence preserves omitted versus explicit null without accepting immutable fields. */
public final class WorkItemContentRequest {
  private Field<String> title = Field.absent();
  private Field<String> description = Field.absent();
  private Field<WorkItemPriority> priority = Field.absent();
  private Field<Set<String>> labels = Field.absent();
  private Field<Instant> dueAt = Field.absent();

  @JsonSetter public void setTitle(String value) { title = Field.of(value); }
  @JsonSetter public void setDescription(String value) { description = Field.of(value); }
  @JsonSetter public void setPriority(WorkItemPriority value) { priority = Field.of(value); }
  @JsonSetter public void setLabels(Set<String> value) { labels = Field.of(value); }
  @JsonSetter public void setDueAt(Instant value) { dueAt = Field.of(value); }
  public String getTitle() { return title.value(); }
  public String getDescription() { return description.value(); }
  public WorkItemPriority getPriority() { return priority.value(); }
  public Set<String> getLabels() { return labels.value(); }
  public Instant getDueAt() { return dueAt.value(); }
  @JsonAnySetter public void unknown(String name, Object value) {
    throw new DomainValidationException("workItem", "contains an unsupported content field");
  }

  UpdateWorkItemContentCommand command(long version) {
    if (labels.value() != null && labels.value().contains(null))
      throw new DomainValidationException("workItem.labels", "must not contain null");
    return new UpdateWorkItemContentCommand(title, description, priority,
        new Field<>(labels.present(), labels.value() == null ? null : labels.value().stream()
            .map(String::strip).filter(value -> !value.isEmpty()).map(WorkItemLabel::new).collect(Collectors.toSet())),
        new Field<>(dueAt.present(), dueAt.value() == null ? null : UtcTimestamp.from(dueAt.value())), version);
  }
}
