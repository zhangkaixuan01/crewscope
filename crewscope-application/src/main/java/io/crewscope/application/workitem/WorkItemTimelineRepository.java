package io.crewscope.application.workitem;

/** Query Port for the M1 DomainEvent/Audit-backed WorkItem timeline. */
@FunctionalInterface
public interface WorkItemTimelineRepository {

  /** Returns canonical, deduplicated events in occurred-time/event-ID descending order. */
  WorkItemTimelinePage findPage(WorkItemTimelineQuery query);
}
