package io.crewscope.server.config.application;

import io.crewscope.application.observability.sli.OrganizationSliQueryService;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.observability.sli.SliStatisticsRepository;
import io.crewscope.application.observability.sli.SliTimerStatistics;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Server composition for the M12-I03 SLI query surface. The SQL adapter is
 * component-scanned (@Repository) and the Timer reader is a @Component; this
 * configuration owns the service assembly and pins the same reporting zone as the
 * other observability surfaces (default UTC, shipped configuration Asia/Shanghai).
 */
@Configuration(proxyBeanMethods = false)
public class SliConfiguration {

    @Bean
    OrganizationSliQueryService organizationSliQueryService(
            SliStatisticsRepository statistics,
            SliTimerStatistics timerStatistics,
            OrganizationAuthorization authorization,
            TimeProvider timeProvider,
            @Value("${crewscope.observability.reporting-zone:UTC}") String reportingZone) {
        return new OrganizationSliQueryService(
                statistics, timerStatistics, authorization, timeProvider, ZoneId.of(reportingZone));
    }
}
