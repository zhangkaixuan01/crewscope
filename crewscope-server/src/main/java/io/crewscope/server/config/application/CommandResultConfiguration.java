package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandResultQueryService;
import io.crewscope.application.command.CommandResultStore;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One result query contract shared by creation flows, without changing legacy receipt responses. */
@Configuration(proxyBeanMethods = false)
public class CommandResultConfiguration {
  @Bean
  CommandResultQueryService commandResultQueryService(CommandResultStore results,
      WorkItemAccessPolicy workAccess, ConversationApplicationService conversations,
      TeamMemberRepository members, TransactionExecutor transactions) {
    return new CommandResultQueryService(results, workAccess, conversations, members,
        transactions);
  }
}
