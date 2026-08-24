package io.crewscope.application.conversation;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import java.util.Objects;
import java.util.Optional;

/** Owner-visible comparison between one Session pin and the current Agent configuration. */
public record ConversationConfigurationStatus(
        AgentRuntimeSession session,
        AgentConfigurationVersion currentConfiguration,
        boolean refreshRequired) {

    public ConversationConfigurationStatus {
        session = Objects.requireNonNull(session, "session");
        currentConfiguration = Objects.requireNonNull(
                currentConfiguration, "currentConfiguration");
        Optional<Long> pinned = session.configurationPin()
                .flatMap(pin -> pin.configurationRevision())
                .map(revision -> revision.value());
        refreshRequired = pinned.isEmpty()
                || pinned.orElseThrow() < currentConfiguration.revision().value();
    }

    public static ConversationConfigurationStatus from(
            AgentRuntimeSession session, AgentConfigurationVersion configuration) {
        return new ConversationConfigurationStatus(session, configuration, false);
    }
}
