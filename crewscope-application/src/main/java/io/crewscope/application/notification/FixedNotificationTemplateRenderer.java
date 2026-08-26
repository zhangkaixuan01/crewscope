package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationVariables;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Product-owned fixed text renderer.
 *
 * <p>The renderer accepts only published Registry templates and the closed M6 variable vocabulary.
 * It never accepts a caller-supplied body, format string or Provider payload.
 */
public final class FixedNotificationTemplateRenderer {

    private static final Map<String, String> HEADINGS = Map.of(
            "ownership-assigned", "CrewScope ownership assignment",
            "execution-assigned", "CrewScope execution assignment",
            "review-required", "CrewScope review required",
            "confirmation-required", "CrewScope confirmation required",
            "exception-alert", "CrewScope execution alert");
    private static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("workItemTitle", "Work item");
        labels.put("itemType", "Type");
        labels.put("sourceType", "Source type");
        labels.put("sourceId", "Source reference");
        labels.put("sourceRevision", "Revision");
        labels.put("priority", "Priority");
        labels.put("deadline", "Deadline");
        labels.put("inboxUrl", "Open inbox");
        labels.put("reviewUrl", "Open review");
        labels.put("confirmationUrl", "Open confirmation");
        labels.put("taskUrl", "Open task");
        labels.put("sourceUrl", "Open source");
        // Preserve product-owned field order so retries render byte-for-byte identical content.
        LABELS = Collections.unmodifiableMap(labels);
    }

    private final NotificationTemplateCatalog templates;

    public FixedNotificationTemplateRenderer(NotificationTemplateCatalog templates) {
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    public RenderedNotificationMessage render(
            NotificationTemplateRef templateRef, NotificationVariables variables) {
        NotificationTemplateRef requested = Objects.requireNonNull(templateRef, "templateRef");
        NotificationTemplate template = templates.requireCurrentPublished(requested);
        if (!requested.equals(template.ref())) {
            throw new IllegalArgumentException(
                    "Notification template catalog returned a different version");
        }
        NotificationVariables validated = template.validateVariables(
                Objects.requireNonNull(variables, "variables").values());
        if (!validated.hash().equals(variables.hash())) {
            throw new IllegalArgumentException("Notification variable hash changed during rendering");
        }
        String heading = HEADINGS.get(template.serverTemplateKey());
        if (heading == null || !LABELS.keySet().containsAll(template.schema().keySet())) {
            throw new IllegalArgumentException("Notification template is not in the fixed MVP registry");
        }
        StringBuilder text = new StringBuilder(heading);
        for (Map.Entry<String, String> label : LABELS.entrySet()) {
            String value = validated.values().get(label.getKey());
            if (value != null) {
                text.append('\n').append(label.getValue()).append(": ").append(value);
            }
        }
        return new RenderedNotificationMessage(text.toString());
    }

    public Set<String> supportedTemplateKeys() {
        return HEADINGS.keySet();
    }
}
