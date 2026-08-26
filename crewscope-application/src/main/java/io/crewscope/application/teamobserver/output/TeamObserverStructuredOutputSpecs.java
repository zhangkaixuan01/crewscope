package io.crewscope.application.teamobserver.output;

import io.crewscope.application.execution.StructuredOutputSpec;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Expanded strict decoder schema equivalent to the frozen `team-observer@1` `$defs` schema. */
public final class TeamObserverStructuredOutputSpecs {

    public static final StructuredOutputSpec<TeamSummaryOutputV1> TEAM_SUMMARY =
            StructuredOutputSpec.strict(
                    "team-summary/v1", TeamSummaryOutputV1.class, schema());

    private TeamObserverStructuredOutputSpecs() {}

    private static Map<String, Object> schema() {
        LinkedHashMap<String, Object> entryProperties = new LinkedHashMap<>();
        entryProperties.put(
                "summary", string(1, TeamSummaryEntry.MAX_SUMMARY_LENGTH));
        entryProperties.put(
                "evidencePath", string(1, TeamSummaryEntry.MAX_EVIDENCE_PATH_LENGTH));
        Map<String, Object> entry = object(entryProperties);

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("progress", array(entry));
        root.put("blockers", array(entry));
        root.put("reviewBacklog", array(entry));
        root.put("pendingConfirmations", array(entry));
        root.put("anomalies", array(entry));
        return object(root);
    }

    private static Map<String, Object> object(LinkedHashMap<String, Object> properties) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        schema.put("minItems", 0);
        schema.put("maxItems", TeamSummaryRequest.MAX_ITEMS_PER_SECTION);
        return schema;
    }

    private static Map<String, Object> string(int minimum, int maximum) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", minimum);
        schema.put("maxLength", maximum);
        return schema;
    }
}
