package io.crewscope.integration.provider.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.domain.provider.ProviderType;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Locks the product-owned NativeWorkItem registration contract used by bootstrap and runtime. */
class NativeWorkItemProviderTest {

  @Test
  void exposesTheStableConnectionlessWorkItemContract() {
    NativeWorkItemProvider provider = new NativeWorkItemProvider();
    var registration = provider.registration();

    assertEquals(ProviderType.WORK_ITEM, registration.type());
    assertEquals("work-item", registration.definitionKey());
    assertEquals("1.0.0", registration.interfaceVersion());
    assertEquals("CrewScope WorkItem", registration.displayName());
    assertEquals("native-work-item", registration.implementationKey());
    assertEquals("1.0.0", registration.implementationVersion());
    assertEquals(
        Set.of(
            "workitem.read",
            "workitem.create",
            "workitem.update",
            "workitem.comment",
            "workitem.resource-link"),
        registration.capabilities().values().stream()
            .map(value -> value.value())
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(registration.descriptor(), provider.descriptor());
  }
}
