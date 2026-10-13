package io.crewscope.application.quota;

import io.crewscope.application.observability.ModelUsageMonthKey;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Read-side quota usage view (M12-A02): configured limits beside their consumed facts for one
 * reporting month. Reserved tokens are admission-time upper bounds (commitment semantics);
 * actual tokens fold the org+month rollup — the two are presented side by side precisely
 * because no per-task actual exists to reconcile them.
 */
public final class OrganizationQuotaUsageQueryService {

    private final OrganizationQuotaConfigurationRepository configurations;
    private final OrganizationQuotaUsageQueryRepository usageFacts;
    private final OrganizationAuthorization authorization;
    private final TimeProvider timeProvider;
    private final ZoneId reportingZone;

    public OrganizationQuotaUsageQueryService(
            OrganizationQuotaConfigurationRepository configurations,
            OrganizationQuotaUsageQueryRepository usageFacts,
            OrganizationAuthorization authorization,
            TimeProvider timeProvider,
            ZoneId reportingZone) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.usageFacts = Objects.requireNonNull(usageFacts, "usageFacts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.reportingZone = Objects.requireNonNull(reportingZone, "reportingZone");
    }

    /**
     * Rejects malformed and future months ({@code IllegalArgumentException}) so transport
     * adapters can map both to one invalid-field response; the current month stays
     * requestable because in-flight months are a legitimate view.
     */
    public String requireRequestableMonth(String usageMonth) {
        String month = ModelUsageMonthKey.formatChecked(usageMonth);
        YearMonth current = YearMonth.from(timeProvider.now().value().atZone(reportingZone));
        if (YearMonth.parse(month).isAfter(current)) {
            throw new IllegalArgumentException("usage month must not be in the future: " + month);
        }
        return month;
    }

    /** {@code month} null resolves to the current reporting month. */
    public QuotaUsageView findUsage(
            OrganizationAccessContext access, OrganizationId organizationId, String month) {
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.SETTINGS_READ);
        String usageMonth = month == null
                ? ModelUsageMonthKey.of(timeProvider.now(), reportingZone).value()
                : requireRequestableMonth(month);

        OrganizationQuotaConfiguration configuration =
                configurations.findByOrganization(organizationId).orElse(null);
        QuotaMonthCalendar.MonthWindow window = QuotaMonthCalendar.windowOf(usageMonth, reportingZone);
        long intakeCount = usageFacts.countTasksCreatedIn(
                organizationId, window.startInclusive(), window.endExclusive());
        long reservedTokens = usageFacts.findTokenLedger(organizationId, usageMonth)
                .map(OrganizationQuotaUsageQueryRepository.TokenLedgerSnapshot::reservedTokens)
                .orElse(0L);
        OrganizationQuotaUsageQueryRepository.ActualTokenUsage actual =
                usageFacts.sumActualTokens(organizationId, usageMonth);

        return new QuotaUsageView(
                usageMonth,
                new IntakeUsage(
                        configuration == null
                                ? null
                                : configuration.monthlyTaskIntakeLimit().orElse(null),
                        intakeCount),
                new TokenBudgetUsage(
                        configuration == null
                                ? null
                                : (configuration.monthlyTokenBudget().isPresent()
                                        ? configuration.monthlyTokenBudget().orElseThrow()
                                        : null),
                        reservedTokens,
                        new ActualTokens(
                                actual.inputTokens(),
                                actual.outputTokens(),
                                actual.cachedInputTokens(),
                                actual.totalTokens())),
                configuration != null);
    }

    /** One month's view; null limits mark unconfigured dimensions, zero counts are facts. */
    public record QuotaUsageView(
            String month,
            IntakeUsage monthlyTaskIntake,
            TokenBudgetUsage monthlyTokenBudget,
            boolean materialized) {
    }

    public record IntakeUsage(Integer limit, long count) {
    }

    public record TokenBudgetUsage(Long limit, long reservedTokens, ActualTokens actualTokens) {
    }

    public record ActualTokens(
            long input, long output, long cachedInput, long total) {
    }
}
