package io.crewscope.server.config.application;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.observability.ModelUsageRollupService;
import io.crewscope.application.observability.RealtimeModelUsageFactService;
import io.crewscope.application.observability.TaskQualityStatisticsRepository;
import io.crewscope.application.observability.TeamBudgetAlertService;
import io.crewscope.application.observability.TeamBudgetAlertSettings;
import io.crewscope.application.observability.TeamBudgetAlertWriter;
import io.crewscope.application.observability.TeamBudgetQueryRepository;
import io.crewscope.application.observability.TeamObservabilityQueryService;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.event.JdbcDomainEventJsonMapper;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventExistenceCheck;
import io.crewscope.infrastructure.persistence.observability.JdbcModelUsageFactScan;
import io.crewscope.infrastructure.persistence.observability.JdbcModelUsageRollupQueryAdapter;
import io.crewscope.infrastructure.persistence.observability.JdbcModelUsageRollupWriter;
import io.crewscope.infrastructure.persistence.observability.JdbcTaskQualityStatisticsAdapter;
import io.crewscope.infrastructure.persistence.observability.JdbcTeamBudgetAlertWriter;
import io.crewscope.infrastructure.persistence.observability.JdbcTeamBudgetQueryAdapter;
import io.crewscope.infrastructure.persistence.observability.ModelUsageRollupConsumer;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import io.crewscope.server.config.runtime.WorkerCapableProfileCondition;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Server composition for usage and quality observability (M10-F03). The rollup
 * projection is always assembled: its tables exist on every deployment (V64 is plain
 * PostgreSQL), the white-listed consumer is inert without usage events, and reads must
 * stay available while later switches (budget alerts) stay off. The reporting zone is
 * a deployment concern — the code default is UTC, the shipped configuration pins
 * Asia/Shanghai (user decision M10-F03).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TeamBudgetAlertProperties.class)
public class ObservabilityConfiguration {

    @Bean
    JdbcModelUsageRollupWriter modelUsageRollupWriter(JdbcTemplate jdbcTemplate) {
        return new JdbcModelUsageRollupWriter(jdbcTemplate);
    }

    @Bean
    JdbcModelUsageFactScan modelUsageFactScan(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcModelUsageFactScan(
                jdbcTemplate, new JdbcDomainEventJsonMapper(objectMapper));
    }

    @Bean
    ModelUsageRollupService modelUsageRollupService(
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            JdbcModelUsageRollupWriter rollup,
            JdbcModelUsageFactScan scan,
            @Value("${crewscope.observability.reporting-zone:UTC}") String reportingZone,
            ObjectMapper objectMapper) {
        return new ModelUsageRollupService(
                catalogs, prices, rollup, scan, ZoneId.of(reportingZone), objectMapper);
    }

    @Bean
    DomainEventConsumer modelUsageRollupConsumer(
            ObjectMapper objectMapper, ModelUsageRollupService rollup) {
        return new ModelUsageRollupConsumer(objectMapper, rollup);
    }

    @Bean
    JdbcDomainEventExistenceCheck jdbcDomainEventExistenceCheck(JdbcTemplate jdbcTemplate) {
        return new JdbcDomainEventExistenceCheck(jdbcTemplate);
    }

    /** Realtime Task-runtime usage facts: deterministic ids plus an existence probe. */
    @Bean
    RealtimeModelUsageFactService realtimeModelUsageFactService(
            DomainEventStore events,
            OutboxRepository outbox,
            JdbcDomainEventExistenceCheck existence,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider) {
        return new RealtimeModelUsageFactService(
                events, outbox, existence, transactions, timeProvider);
    }

    @Bean
    JdbcModelUsageRollupQueryAdapter modelUsageRollupQueryAdapter(JdbcTemplate jdbcTemplate) {
        return new JdbcModelUsageRollupQueryAdapter(jdbcTemplate);
    }

    @Bean
    TaskQualityStatisticsRepository taskQualityStatisticsRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcTaskQualityStatisticsAdapter(jdbcTemplate);
    }

    @Bean
    TeamObservabilityQueryService teamObservabilityQueryService(
            JdbcModelUsageRollupQueryAdapter rollupQueries,
            TaskQualityStatisticsRepository quality,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TimeProvider timeProvider,
            @Value("${crewscope.observability.reporting-zone:UTC}") String reportingZone) {
        return new TeamObservabilityQueryService(
                rollupQueries, quality, teams, memberships,
                timeProvider, ZoneId.of(reportingZone));
    }

    /** The soft budget scan: one pass, one transaction per first crossing. */
    @Bean
    TeamBudgetAlertService teamBudgetAlertService(
            TeamBudgetQueryRepository budgetQueries,
            TeamBudgetAlertWriter budgetAlerts,
            DomainEventStore events,
            OutboxRepository outbox,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            @Value("${crewscope.observability.reporting-zone:UTC}") String reportingZone,
            TeamBudgetAlertProperties properties) {
        TeamBudgetAlertSettings settings = properties.toSettings();
        return new TeamBudgetAlertService(
                budgetQueries, budgetAlerts, events, outbox, transactions,
                timeProvider, ZoneId.of(reportingZone), settings);
    }

    @Bean
    JdbcTeamBudgetQueryAdapter teamBudgetQueryAdapter(JdbcTemplate jdbcTemplate) {
        return new JdbcTeamBudgetQueryAdapter(jdbcTemplate);
    }

    @Bean
    TeamBudgetAlertWriter teamBudgetAlertWriter(
            JdbcTemplate jdbcTemplate, AuthoritativeTimeProvider timeProvider) {
        return new JdbcTeamBudgetAlertWriter(jdbcTemplate, timeProvider);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TeamBudgetAlertScheduler teamBudgetAlertScheduler(TeamBudgetAlertService scan) {
        return new TeamBudgetAlertScheduler(scan);
    }
}
