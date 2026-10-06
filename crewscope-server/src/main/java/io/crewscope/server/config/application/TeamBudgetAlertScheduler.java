package io.crewscope.server.config.application;

import io.crewscope.application.observability.TeamBudgetAlertService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Non-overlapping wake-up loop for the soft budget scan (M10-F03). Worker-profile
 * only; the enabled flag itself is checked inside {@link TeamBudgetAlertService},
 * so flipping it on never needs a restart of the schedule.
 */
final class TeamBudgetAlertScheduler {

    private final TeamBudgetAlertService scan;
    private final AtomicBoolean scanning = new AtomicBoolean();

    TeamBudgetAlertScheduler(TeamBudgetAlertService scan) {
        this.scan = Objects.requireNonNull(scan, "scan");
    }

    @Scheduled(fixedDelayString = "${crewscope.observability.budget.scan-fixed-delay:300s}")
    void scan() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            scan.scanOnce();
        } finally {
            scanning.set(false);
        }
    }
}
