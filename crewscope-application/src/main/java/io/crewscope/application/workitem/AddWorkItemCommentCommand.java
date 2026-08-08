package io.crewscope.application.workitem;

import java.util.Objects;

/** User-authored Markdown content appended to one WorkItem. */
public record AddWorkItemCommentCommand(String content) {

  public AddWorkItemCommentCommand {
    content = Objects.requireNonNull(content, "content");
  }
}
