package io.crewscope.application.workdesk;

/** Read port for the bounded, derived WorkDesk projection. */
public interface WorkDeskRepository {
  WorkDeskSummary summarize(WorkDeskQuery query);
}
