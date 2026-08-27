package io.crewscope.server.config.application;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires current-member Inbox reads and durable strong-ETag disposition commands. */
@Configuration(proxyBeanMethods = false)
public class InboxApplicationConfiguration {

    @Bean
    InboxApplicationService inboxApplicationService(
            InboxItemQueryPort queries, WorkItemAccessPolicy accessPolicy) {
        return new InboxApplicationService(queries, accessPolicy);
    }

    @Bean
    InboxDispositionApplicationService inboxDispositionApplicationService(
            InboxItemQueryPort queries,
            InboxDispositionRepository dispositions,
            TeamMembershipQuery memberships,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new InboxDispositionApplicationService(
                queries, dispositions, memberships, transactions, timeProvider);
    }

    @Bean
    InboxDispositionCommandService inboxDispositionCommandService(
            InboxApplicationService authorizationQueries,
            InboxDispositionApplicationService dispositions,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new InboxDispositionCommandService(
                authorizationQueries, dispositions, receipts, transactions, timeProvider);
    }
}
