package io.crewscope.agentscope.agui;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.crewscope.domain.conversation.MessageContent;

/**
 * Message-only input accepted by the CrewScope AG-UI boundary.
 *
 * <p>Unknown JSON properties fail deserialization so Agent, Session, Tool and identity control
 * fields cannot be smuggled through the protocol body.
 */
public final class ControlledAguiClientInput {

    private final String message;

    @JsonCreator
    public ControlledAguiClientInput(@JsonProperty("message") String message) {
        this.message = new MessageContent(message).markdown();
    }

    public String getMessage() {
        return message;
    }

    /** Rejects control-field injection even when the shared ObjectMapper ignores unknown fields. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported AG-UI request property");
    }
}
