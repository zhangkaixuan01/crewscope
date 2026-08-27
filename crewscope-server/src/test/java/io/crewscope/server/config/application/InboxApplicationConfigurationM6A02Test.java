package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.inbox.InboxApplicationService;
import io.crewscope.application.inbox.InboxDispositionApplicationService;
import io.crewscope.application.inbox.InboxDispositionCommandService;
import io.crewscope.application.inbox.InboxDispositionRepository;
import io.crewscope.application.inbox.InboxItemQueryPort;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Constructor-injection composition proof for M6-A02. */
class InboxApplicationConfigurationM6A02Test {

    @Test
    void wiresReadDispositionAndIdempotentCommandServicesExactlyOnce() {
        new ApplicationContextRunner()
                .withUserConfiguration(InboxApplicationConfiguration.class)
                .withBean(InboxItemQueryPort.class, () -> mock(InboxItemQueryPort.class))
                .withBean(
                        InboxDispositionRepository.class,
                        () -> mock(InboxDispositionRepository.class))
                .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
                .withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InboxApplicationService.class);
                    assertThat(context).hasSingleBean(InboxDispositionApplicationService.class);
                    assertThat(context).hasSingleBean(InboxDispositionCommandService.class);
                });
    }
}
