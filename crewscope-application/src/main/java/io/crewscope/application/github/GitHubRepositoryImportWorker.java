package io.crewscope.application.github;

import io.crewscope.application.coding.CreateRepositoryBindingCommand;
import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Claims durable import jobs and executes all repository I/O inside a Worker deployment. */
public final class GitHubRepositoryImportWorker {

    private final GitHubRepositoryImportJobRepository jobs;
    private final GitHubRepositoryImportAuthorizationService authorization;
    private final GitHubProviderPort provider;
    private final GitHubRepositoryImportPort importer;
    private final RepositoryBindingApplicationService bindings;
    private final RepositoryBindingRepository bindingRepository;
    private final PrincipalRepository principals;
    private final TimeProvider timeProvider;
    private final String workerId;
    private final Duration leaseDuration;

    public GitHubRepositoryImportWorker(
            GitHubRepositoryImportJobRepository jobs,
            GitHubRepositoryImportAuthorizationService authorization,
            GitHubProviderPort provider,
            GitHubRepositoryImportPort importer,
            RepositoryBindingApplicationService bindings,
            RepositoryBindingRepository bindingRepository,
            PrincipalRepository principals,
            TimeProvider timeProvider,
            String workerId,
            Duration leaseDuration) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.workerId = requireWorkerId(workerId);
        this.leaseDuration = requireLeaseDuration(leaseDuration);
    }

    /** Executes at most one claim so the scheduler can remain non-overlapping and bounded. */
    public boolean runOnce() {
        Optional<GitHubRepositoryImportLease> claimed = jobs.claimNext(
                workerId, timeProvider.now(), leaseDuration);
        if (claimed.isEmpty()) {
            return false;
        }
        execute(claimed.orElseThrow());
        return true;
    }

    private void execute(GitHubRepositoryImportLease lease) {
        GitHubRepositoryImportJob current = lease.job();
        try {
            var actor = principals.findById(current.organizationId(), current.createdBy())
                    .filter(value -> value.canAct())
                    .orElseThrow(() -> new GitHubProviderException(
                            GitHubProviderErrorCode.PERMISSION_DENIED,
                            "GitHub import actor is unavailable"));
            TeamCommandContext context = new TeamCommandContext(
                    new TeamAccessContext(actor, current.createdByPlatformAdministrator()),
                    new IdempotencyKey("github-import:" + current.id()),
                    current.id(),
                    Optional.empty());
            GitHubRepositoryImportAuthorization authorized = authorization.authorize(
                    context.access(),
                    context.correlationId(),
                    current.organizationId(),
                    current.teamId(),
                    current.projectId(),
                    current.connectionId(),
                    current.connectionVersion(),
                    current.grantId(),
                    current.grantVersion(),
                    current.externalRepositoryId());
            if (!authorized.catalog().fullName().equals(current.repositoryFullName())) {
                throw new GitHubProviderException(
                        GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                        "GitHub repository coordinates have changed");
            }
            provider.preflightRepository(new PreflightGitHubRepositoryRequest(
                    authorized.access(),
                    current.externalRepositoryId(),
                    current.defaultBranch(),
                    authorized.policy()));
            UtcTimestamp importingAt = timeProvider.now();
            current = current.progress(
                    GitHubRepositoryImportStatus.IMPORTING,
                    35,
                    Optional.empty(),
                    Optional.empty(),
                    current.attempt(),
                    importingAt);
            Optional<GitHubRepositoryImportJob> importing = jobs.updateClaimed(
                    current, workerId, importingAt, leaseDuration);
            if (importing.isEmpty()) {
                return;
            }
            current = importing.orElseThrow();
            importer.importRepository(new GitHubRepositoryImportRequest(
                    authorized.access(),
                    current.externalRepositoryId(),
                    current.repositoryFullName(),
                    current.repositoryKey(),
                    current.defaultBranch()));
            RepositoryBinding binding = ensureBinding(context, current);
            UtcTimestamp readyAt = timeProvider.now();
            jobs.updateClaimed(
                    current.progress(
                            GitHubRepositoryImportStatus.READY,
                            100,
                            Optional.empty(),
                            Optional.of(binding.id()),
                            current.attempt(),
                            readyAt),
                    workerId,
                    readyAt,
                    leaseDuration);
        } catch (GitHubProviderException failure) {
            fail(current, failure.code().name());
        } catch (RuntimeException failure) {
            fail(current, "IMPORT_FAILED");
        }
    }

    private RepositoryBinding ensureBinding(
            TeamCommandContext context, GitHubRepositoryImportJob job) {
        Optional<RepositoryBinding> existing = bindingRepository.findByKey(
                job.organizationId(), job.teamId(), job.projectId(), job.repositoryKey());
        if (existing.isPresent()) {
            return requireCompatible(existing.orElseThrow(), job);
        }
        try {
            return bindings.create(
                            context,
                            job.teamId(),
                            job.projectId(),
                            new CreateRepositoryBindingCommand(
                                    job.repositoryKey().value(), job.defaultBranch().value()))
                    .result()
                    .orElseThrow();
        } catch (RuntimeException concurrentOrFailed) {
            return bindingRepository.findByKey(
                            job.organizationId(), job.teamId(), job.projectId(), job.repositoryKey())
                    .map(value -> requireCompatible(value, job))
                    .orElseThrow(() -> concurrentOrFailed);
        }
    }

    private static RepositoryBinding requireCompatible(
            RepositoryBinding binding, GitHubRepositoryImportJob job) {
        if (!binding.defaultBranch().equals(job.defaultBranch())) {
            throw new GitHubProviderException(
                    GitHubProviderErrorCode.CONFLICT,
                    "Repository Binding already uses a different default branch");
        }
        return binding;
    }

    private void fail(GitHubRepositoryImportJob job, String failureCode) {
        UtcTimestamp failedAt = timeProvider.now();
        jobs.updateClaimed(
                job.progress(
                        GitHubRepositoryImportStatus.FAILED,
                        100,
                        Optional.of(failureCode),
                        Optional.empty(),
                        job.attempt(),
                        failedAt),
                workerId,
                failedAt,
                leaseDuration);
    }

    private static String requireWorkerId(String value) {
        String normalized = Objects.requireNonNull(value, "workerId").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("workerId must contain 1 to 160 characters");
        }
        return normalized;
    }

    private static Duration requireLeaseDuration(Duration value) {
        Duration required = Objects.requireNonNull(value, "leaseDuration");
        if (required.compareTo(Duration.ofSeconds(5)) < 0
                || required.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be between 5 seconds and 1 hour");
        }
        return required;
    }
}
