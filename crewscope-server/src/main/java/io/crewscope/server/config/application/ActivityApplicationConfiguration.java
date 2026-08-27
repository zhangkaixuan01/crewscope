package io.crewscope.server.config.application;

import io.crewscope.application.activity.ActivityApplicationService;
import io.crewscope.application.activity.ActivityQueryPort;
import io.crewscope.application.activity.TeamRealtimeEventStore;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires authorization-aware Team and WorkItem Activity application queries. */
@Configuration(proxyBeanMethods = false)
public class ActivityApplicationConfiguration {

  @Bean
  ActivityApplicationService activityApplicationService(
      ActivityQueryPort queries,
      TeamRealtimeEventStore realtimeStore,
      WorkItemAccessPolicy accessPolicy,
      WorkItemRepository workItems,
      TeamRoleRepository teamRoles,
      MemberRoleRepository memberRoles,
      TimeProvider timeProvider) {
    return new ActivityApplicationService(
        queries,
        realtimeStore,
        accessPolicy,
        workItems,
        teamRoles,
        memberRoles,
        timeProvider);
  }
}
