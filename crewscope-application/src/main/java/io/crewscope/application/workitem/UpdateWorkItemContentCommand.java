package io.crewscope.application.workitem;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.*;
import java.util.*;

/** Presence is part of the intent: omitted means unchanged, null clears only nullable fields. */
public record UpdateWorkItemContentCommand(
    Field<String> title, Field<String> description, Field<WorkItemPriority> priority,
    Field<Set<WorkItemLabel>> labels, Field<UtcTimestamp> dueAt, long expectedVersion) {
  public record Field<T>(boolean present, T value) {
    public static <T> Field<T> absent() { return new Field<>(false, null); }
    public static <T> Field<T> of(T value) { return new Field<>(true, value); }
  }
  public UpdateWorkItemContentCommand {
    Objects.requireNonNull(title); Objects.requireNonNull(description); Objects.requireNonNull(priority);
    Objects.requireNonNull(labels); Objects.requireNonNull(dueAt);
    if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
    if (title.present()) {
      if (title.value() == null || title.value().isBlank() || title.value().strip().length() > 240)
        throw new DomainValidationException("workItem.title", "must contain 1–240 characters");
      title = Field.of(title.value().strip());
    }
    if (priority.present() && priority.value() == null)
      throw new DomainValidationException("workItem.priority", "must not be null");
    if (description.present() && description.value() != null)
      description = Field.of(description.value().strip().isEmpty() ? null : description.value().strip());
    if (labels.present()) labels = Field.of(labels.value() == null ? Set.of() : Set.copyOf(labels.value()));
    if (!title.present() && !description.present() && !priority.present() && !labels.present() && !dueAt.present())
      throw new DomainValidationException("workItem", "at least one content field is required");
  }
  public List<String> fingerprint() {
    List<String> parts = new ArrayList<>();
    for (Field<?> field : List.of(title, description, priority, dueAt)) {
      parts.add(Boolean.toString(field.present()));
      parts.add(field.value() == null ? "" : field.value().toString());
    }
    parts.add(Boolean.toString(labels.present()));
    var values = labels.value() == null ? List.<String>of()
        : labels.value().stream().map(WorkItemLabel::value).sorted().toList();
    parts.add(Integer.toString(values.size())); parts.addAll(values);
    return parts;
  }
}
