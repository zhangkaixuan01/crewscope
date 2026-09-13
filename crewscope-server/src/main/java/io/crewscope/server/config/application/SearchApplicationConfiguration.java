package io.crewscope.server.config.application;

import io.crewscope.application.search.SearchAccessPolicy;
import io.crewscope.application.search.SearchIndexPort;
import io.crewscope.application.search.SearchQueryService;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit constructor composition for the member-scoped search boundary. */
@Configuration(proxyBeanMethods = false)
public class SearchApplicationConfiguration {
  @Bean SearchAccessPolicy searchAccessPolicy(WorkItemAccessPolicy accessPolicy) { return new SearchAccessPolicy(accessPolicy); }
  @Bean SearchQueryService searchQueryService(SearchIndexPort index, SearchAccessPolicy access) { return new SearchQueryService(index, access); }
}
