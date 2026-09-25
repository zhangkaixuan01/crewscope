package io.crewscope.server.config.application;

import io.crewscope.application.workdesk.WorkDeskAccessPolicy;
import io.crewscope.application.workdesk.WorkDeskQueryService;
import io.crewscope.application.workdesk.WorkDeskRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemSummaryRepository;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityProjector;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import io.crewscope.server.api.WorkDeskSectionCursorCodec;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit constructor composition for the personal WorkDesk read boundary.
 *
 * <p>The section cursor reuses the Team Activity key ring under its own signing domain, exactly as
 * the WorkItem list cursor does, so desk tokens are interchangeable with no other surface's.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkDeskQueryProperties.class)
public class WorkDeskApplicationConfiguration {
  @Bean
  WorkDeskAccessPolicy workDeskAccessPolicy(WorkItemAccessPolicy accessPolicy) {
    return new WorkDeskAccessPolicy(accessPolicy);
  }

  @Bean
  WorkDeskQueryService workDeskQueryService(
      WorkDeskRepository repository,
      WorkItemSummaryRepository summaryRepository,
      WorkDeskAccessPolicy accessPolicy,
      WorkItemAccessPolicy workItemAccessPolicy,
      WorkItemTransitionAvailabilityProjector transitions,
      TimeProvider timeProvider) {
    return new WorkDeskQueryService(
        repository, summaryRepository, accessPolicy, workItemAccessPolicy, transitions, timeProvider);
  }

  @Bean
  WorkDeskSectionCursorCodec workDeskSectionCursorCodec(
      TeamActivityCursorKeyRing keyRing, WorkDeskQueryProperties properties) {
    return new WorkDeskSectionCursorCodec(
        keyRing, Clock.systemUTC(), properties.getCursorMaximumAge());
  }
}
