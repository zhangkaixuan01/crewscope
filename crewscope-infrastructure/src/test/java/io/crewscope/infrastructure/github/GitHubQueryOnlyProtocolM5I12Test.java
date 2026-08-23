package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.infrastructure.workspace.git.GitAskPassEnvironment;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M5-I12 guard that the branch reconciliation protocol can never execute a Git Push. */
class GitHubQueryOnlyProtocolM5I12Test {

    @Test
    void queryReadsTheRemoteHeadWithoutInvokingAnyPushCommand() {
        GitCommandExecutor git = mock(GitCommandExecutor.class);
        Path mirror = Path.of("/tmp/crewscope-m5-i12-mirror.git");
        URI remote = URI.create("https://github.com/crewscope/crewscope-java.git");
        GitAskPassEnvironment askPass = new GitAskPassEnvironment(
                Path.of("/tmp/crewscope-m5-i12-askpass"),
                Path.of("/tmp/crewscope-m5-i12-secret"));
        RepositoryCommitId delivery = new RepositoryCommitId("b".repeat(40));
        PushBranchActionParameters action = new PushBranchActionParameters(
                new ExternalRepositoryId("101"),
                new RepositoryBranchReference("refs/heads/crewscope/m5-i12"),
                delivery,
                Optional.empty(),
                ConnectionId.generate());
        when(git.findRemoteBranchHead(mirror, remote, action.branch(), askPass))
                .thenReturn(Optional.of(delivery));

        Optional<RepositoryCommitId> result =
                new GitHubPushProtocol(git).query(mirror, remote, askPass, action);

        assertEquals(Optional.of(delivery), result);
        verify(git).findRemoteBranchHead(mirror, remote, action.branch(), askPass);
        verify(git, never()).pushBranch(any(), any(), any(), any(), any(), any());
    }
}
