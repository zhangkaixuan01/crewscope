package io.crewscope.server.config.application;

import io.crewscope.application.github.GitHubRepositoryImportWorker;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Non-overlapping poll loop backed by durable import rows and fenced Worker leases. */
final class GitHubRepositoryImportWorkerScheduler {

    private final GitHubRepositoryImportWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();

    GitHubRepositoryImportWorkerScheduler(GitHubRepositoryImportWorker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Scheduled(fixedDelayString = "${crewscope.github-import.worker.poll-interval:1s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.runOnce();
        } finally {
            polling.set(false);
        }
    }
}
