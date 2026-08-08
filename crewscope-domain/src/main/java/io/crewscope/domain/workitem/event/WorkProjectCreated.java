package io.crewscope.domain.workitem.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectStatus;
import java.util.Objects;

/** Version 1 business payload emitted after a WorkProject is created. */
public record WorkProjectCreated(String projectKey, String name, WorkProjectStatus status)
    implements DomainEvent {

  public WorkProjectCreated {
    projectKey = new WorkProjectKey(projectKey).value();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    name = name.strip();
    status = Objects.requireNonNull(status, "status");
  }

  public static WorkProjectCreated from(WorkProject project) {
    WorkProject source = Objects.requireNonNull(project, "project");
    return new WorkProjectCreated(source.key().value(), source.name(), source.status());
  }
}
