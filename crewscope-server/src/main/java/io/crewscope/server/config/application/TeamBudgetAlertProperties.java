package io.crewscope.server.config.application;

import io.crewscope.application.observability.TeamBudgetAlertSettings;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code crewscope.observability.budget.*} (M10-F03). The scan ships disabled: a
 * budget of zero (or a missing currency entry) leaves that dimension off, so an
 * operator opts in team budgeting one number at a time. Amounts are per ISO 4217
 * code and are never converted.
 */
@ConfigurationProperties(prefix = "crewscope.observability.budget")
public class TeamBudgetAlertProperties {

    private boolean enabled = false;
    private long monthlyTokenBudget;
    private BigDecimal warningThreshold = new BigDecimal("0.8");
    private Map<String, BigDecimal> monthlyAmountBudget = new LinkedHashMap<>();

    /** Off by default — a reminder must be a deliberate operator choice. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Whole tokens per reporting month; zero disables the token dimension. */
    public long getMonthlyTokenBudget() {
        return monthlyTokenBudget;
    }

    public void setMonthlyTokenBudget(long monthlyTokenBudget) {
        this.monthlyTokenBudget = monthlyTokenBudget;
    }

    /** The WARNING line as a share of the budget, within (0, 1]. */
    public BigDecimal getWarningThreshold() {
        return warningThreshold;
    }

    public void setWarningThreshold(BigDecimal warningThreshold) {
        this.warningThreshold = warningThreshold;
    }

    /** Monthly amount budget per ISO 4217 code; a missing code disables that currency. */
    public Map<String, BigDecimal> getMonthlyAmountBudget() {
        return monthlyAmountBudget;
    }

    public void setMonthlyAmountBudget(Map<String, BigDecimal> monthlyAmountBudget) {
        this.monthlyAmountBudget = monthlyAmountBudget;
    }

    /** Snapshots the raw configuration into the validated application settings. */
    public TeamBudgetAlertSettings toSettings() {
        return new TeamBudgetAlertSettings(
                enabled, monthlyTokenBudget, warningThreshold, monthlyAmountBudget);
    }
}
