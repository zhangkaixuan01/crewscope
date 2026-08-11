package io.crewscope.application.conversation;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.shared.id.TeamId;

/** M2-A07 Port for one atomic TaskIntent confirmation and Native WorkItem creation command. */
public interface TaskIntentConfirmationCommandPort {

  CommandExecution<TaskIntentConfirmationResult> confirm(
      TeamCommandContext context,
      TeamId teamId,
      ConversationIdAndTaskIntentId target,
      ConfirmTaskIntentCommand command);
}
