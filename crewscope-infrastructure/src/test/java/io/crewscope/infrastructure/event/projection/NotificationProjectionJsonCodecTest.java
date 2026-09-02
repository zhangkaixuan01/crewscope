package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.inbox.InboxItemType;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Verifies bounded JSON parsing stays isolated from projection and JDBC orchestration. */
class NotificationProjectionJsonCodecTest {

    private final NotificationProjectionJsonCodec codec = new NotificationProjectionJsonCodec(new ObjectMapper());

    @Test
    void acceptsOnlyAllowlistedNotificationVariables() {
        assertEquals(Map.of("itemType", "TASK"), codec.variableValues("{\"itemType\":\"TASK\"}"));
        assertThrows(IllegalStateException.class, () -> codec.variableValues("{\"secret\":\"value\"}"));
    }

    @Test
    void parsesTypedFactsAndSerializesFixedCapabilities() {
        assertEquals(Set.of(InboxItemType.OWNERSHIP), codec.itemTypes("[\"OWNERSHIP\"]"));
        assertThrows(IllegalArgumentException.class, () -> codec.itemTypes("[\"not-a-type\"]"));
        assertEquals("[\"collaboration.notification.send-fixed-template\"]", codec.capabilityJson());
    }
}
