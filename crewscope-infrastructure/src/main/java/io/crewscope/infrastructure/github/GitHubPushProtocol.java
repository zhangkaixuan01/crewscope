package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.application.github.GitHubPushOutcome;
import io.crewscope.application.github.GitHubPushResult;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.infrastructure.workspace.git.GitAskPassEnvironment;
import io.crewscope.infrastructure.workspace.git.GitCommandError;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Exact remote-Head, fast-forward, lease and unknown-outcome protocol for one Push action. */
final class GitHubPushProtocol {

    private final GitCommandExecutor gitCommands;

    GitHubPushProtocol(GitCommandExecutor gitCommands) {
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
    }

    GitHubPushResult push(
            Path mirror,
            URI remote,
            GitAskPassEnvironment askPass,
            PushBranchActionParameters action) {
        Optional<RepositoryCommitId> current = findRemoteHead(mirror, remote, askPass, action);
        if (current.filter(action.deliveryHead()::equals).isPresent()) {
            return result(GitHubPushOutcome.ALREADY_PRESENT, action);
        }
        if (!current.equals(action.expectedRemoteHead())) {
            throw failure(
                    GitHubPushErrorCode.REMOTE_HEAD_CONFLICT,
                    "GitHub branch no longer matches the confirmed action");
        }
        if (current.isPresent()) {
            try {
                gitCommands.fetchRemoteBranch(mirror, remote, action.branch(), askPass);
                Optional<RepositoryCommitId> rechecked = findRemoteHead(
                        mirror, remote, askPass, action);
                if (!current.equals(rechecked)) {
                    throw failure(
                            GitHubPushErrorCode.REMOTE_HEAD_CONFLICT,
                            "GitHub branch changed during delivery validation");
                }
                if (!gitCommands.isAncestor(
                        mirror, current.orElseThrow(), action.deliveryHead())) {
                    throw failure(
                            GitHubPushErrorCode.NON_FAST_FORWARD,
                            "GitHub delivery is not a fast-forward");
                }
            } catch (GitHubPushException failure) {
                throw failure;
            } catch (GitCommandException failure) {
                throw failure(
                        GitHubPushErrorCode.UNKNOWN,
                        "GitHub fast-forward preflight could not be completed",
                        failure);
            }
        }
        try {
            gitCommands.pushBranch(
                    mirror,
                    remote,
                    action.branch(),
                    action.deliveryHead(),
                    action.expectedRemoteHead(),
                    askPass);
            return result(GitHubPushOutcome.PUSHED, action);
        } catch (GitCommandException pushFailure) {
            if (pushFailure.error() == GitCommandError.TIMEOUT) {
                return reconcileUnknown(mirror, remote, askPass, action, pushFailure);
            }
            if (pushFailure.error() == GitCommandError.CONFLICT) {
                throw failure(
                        GitHubPushErrorCode.REMOTE_HEAD_CONFLICT,
                        "GitHub branch changed before Push",
                        pushFailure);
            }
            if (pushFailure.error() == GitCommandError.REMOTE_REJECTED) {
                throw failure(
                        GitHubPushErrorCode.PROTECTED_BRANCH,
                        "GitHub branch policy rejected Push",
                        pushFailure);
            }
            throw failure(
                    GitHubPushErrorCode.PUSH_REJECTED,
                    "GitHub rejected Push",
                    pushFailure);
        }
    }

    /** Reads the current remote Head and never invokes a Git Push command. */
    Optional<RepositoryCommitId> query(
            Path mirror,
            URI remote,
            GitAskPassEnvironment askPass,
            PushBranchActionParameters action) {
        return findRemoteHead(
                Objects.requireNonNull(mirror, "mirror"),
                Objects.requireNonNull(remote, "remote"),
                Objects.requireNonNull(askPass, "askPass"),
                Objects.requireNonNull(action, "action"));
    }

    private GitHubPushResult reconcileUnknown(
            Path mirror,
            URI remote,
            GitAskPassEnvironment askPass,
            PushBranchActionParameters action,
            GitCommandException pushFailure) {
        try {
            Optional<RepositoryCommitId> remoteHead = findRemoteHead(
                    mirror, remote, askPass, action);
            if (remoteHead.filter(action.deliveryHead()::equals).isPresent()) {
                return result(GitHubPushOutcome.RECOVERED_AFTER_UNKNOWN, action);
            }
        } catch (RuntimeException reconciliationFailure) {
            pushFailure.addSuppressed(reconciliationFailure);
        }
        throw failure(
                GitHubPushErrorCode.UNKNOWN,
                "GitHub Push outcome requires reconciliation",
                pushFailure);
    }

    private Optional<RepositoryCommitId> findRemoteHead(
            Path mirror,
            URI remote,
            GitAskPassEnvironment askPass,
            PushBranchActionParameters action) {
        try {
            return gitCommands.findRemoteBranchHead(
                    mirror, remote, action.branch(), askPass);
        } catch (GitCommandException failure) {
            throw failure(
                    GitHubPushErrorCode.UNKNOWN,
                    "GitHub branch Head could not be verified",
                    failure);
        }
    }

    private static GitHubPushResult result(
            GitHubPushOutcome outcome, PushBranchActionParameters action) {
        return new GitHubPushResult(
                outcome, action.repositoryId(), action.branch(), action.deliveryHead());
    }

    private static GitHubPushException failure(
            GitHubPushErrorCode code, String summary) {
        return new GitHubPushException(code, summary);
    }

    private static GitHubPushException failure(
            GitHubPushErrorCode code, String summary, Throwable cause) {
        return new GitHubPushException(code, summary, cause);
    }
}
