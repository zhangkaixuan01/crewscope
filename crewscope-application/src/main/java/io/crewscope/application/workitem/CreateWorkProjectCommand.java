package io.crewscope.application.workitem;

import java.util.Objects;

/** User-authored fields required to create a WorkProject inside a Team Workspace. */
public record CreateWorkProjectCommand(String key, String name) {

  public CreateWorkProjectCommand {
    key = Objects.requireNonNull(key, "key");
    name = Objects.requireNonNull(name, "name");
  }
}
