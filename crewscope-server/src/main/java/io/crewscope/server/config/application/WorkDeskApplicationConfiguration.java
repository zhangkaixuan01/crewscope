package io.crewscope.server.config.application;

import io.crewscope.application.workdesk.WorkDeskAccessPolicy;
import io.crewscope.application.workdesk.WorkDeskQueryService;
import io.crewscope.application.workdesk.WorkDeskRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit constructor composition for the personal WorkDesk read boundary. */
@Configuration(proxyBeanMethods = false)
public class WorkDeskApplicationConfiguration {
  @Bean
  WorkDeskAccessPolicy workDeskAccessPolicy(WorkItemAccessPolicy accessPolicy) {
    return new WorkDeskAccessPolicy(accessPolicy);
  }

  @Bean
  WorkDeskQueryService workDeskQueryService(
      WorkDeskRepository repository, WorkDeskAccessPolicy accessPolicy) {
    return new WorkDeskQueryService(repository, accessPolicy);
  }
}
