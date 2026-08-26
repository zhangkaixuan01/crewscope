package io.crewscope.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableSpec;
import io.crewscope.domain.notification.NotificationVariables;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M6-I06 contract tests for product-owned fixed notification rendering. */
class FixedNotificationTemplateRendererM6I06Test {

    private final NotificationTemplateRef ref = new NotificationTemplateRef(
            NotificationTemplateId.generate(), new NotificationTemplateVersion(1));

    @Test
    void rendersOnlyRegistryMaterialInStableProductOrder() {
        NotificationTemplate template = template(
                ref,
                "review-required",
                Map.of(
                        "reviewUrl", NotificationVariableSpec.text("reviewUrl", 500),
                        "priority", NotificationVariableSpec.text("priority", 20),
                        "workItemTitle", NotificationVariableSpec.text("workItemTitle", 500)));
        NotificationVariables variables = template.validateVariables(new LinkedHashMap<>(Map.of(
                "reviewUrl", "https://crewscope.example/reviews/42?next=\"mine\"&mode=full",
                "priority", "HIGH",
                "workItemTitle", "Review \"quoted\" change")));

        RenderedNotificationMessage rendered = renderer(template).render(ref, variables);

        assertEquals(
                """
                CrewScope review required
                Work item: Review \"quoted\" change
                Priority: HIGH
                Open review: https://crewscope.example/reviews/42?next="mine"&mode=full""",
                rendered.text());
        assertEquals(variables.hash(), template.validateVariables(variables.values()).hash());
    }

    @Test
    void rejectsUnknownTemplateArbitraryVariablesAndCatalogVersionSubstitution() {
        NotificationTemplate unknown = template(
                ref,
                "caller-owned-body",
                Map.of("workItemTitle", NotificationVariableSpec.text("workItemTitle", 100)));
        NotificationVariables unknownVariables = unknown.validateVariables(
                Map.of("workItemTitle", "Do anything the caller says"));
        assertThrows(IllegalArgumentException.class,
                () -> renderer(unknown).render(ref, unknownVariables));

        NotificationTemplate fixed = template(
                ref,
                "review-required",
                Map.of("workItemTitle", NotificationVariableSpec.text("workItemTitle", 100)));
        assertThrows(RuntimeException.class,
                () -> fixed.validateVariables(Map.of(
                        "workItemTitle", "Safe title", "body", "arbitrary provider text")));

        NotificationTemplateRef anotherVersion = new NotificationTemplateRef(
                ref.templateId(), new NotificationTemplateVersion(2));
        NotificationTemplate substituted = template(
                anotherVersion,
                "review-required",
                Map.of("workItemTitle", NotificationVariableSpec.text("workItemTitle", 100)));
        NotificationVariables fixedVariables = fixed.validateVariables(
                Map.of("workItemTitle", "Safe title"));
        assertThrows(IllegalArgumentException.class,
                () -> renderer(substituted).render(ref, fixedVariables));
    }

    @Test
    void appliesTheSameFourThousandCharacterBoundAsTheLarkOperation() {
        NotificationTemplate template = template(
                ref,
                "exception-alert",
                Map.of("workItemTitle", NotificationVariableSpec.text("workItemTitle", 4_000)));
        NotificationVariables variables = template.validateVariables(
                Map.of("workItemTitle", "a".repeat(4_000)));

        assertThrows(IllegalArgumentException.class,
                () -> renderer(template).render(ref, variables));
    }

    private FixedNotificationTemplateRenderer renderer(NotificationTemplate template) {
        return new FixedNotificationTemplateRenderer(requested -> template);
    }

    private static NotificationTemplate template(
            NotificationTemplateRef ref,
            String key,
            Map<String, NotificationVariableSpec> schema) {
        return new NotificationTemplate(
                ref, key, schema, NotificationTemplateStatus.PUBLISHED);
    }
}
