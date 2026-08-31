package io.crewscope.agentscope.teamobserver;

import io.crewscope.agentscope.ModelToolNamePolicy;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Maps stable dotted policy keys to collision-free provider-compatible runtime Tool names. */
public final class TeamObserverToolNames {

    public static final String TEAM_ACTIVITY_READ = "team_activity_read";
    public static final String TEAM_INBOX_SUMMARY_READ = "team_inbox_summary_read";
    public static final String WORK_ITEM_SUMMARY_READ = "workitem_summary_read";
    public static final String TASK_SUMMARY_READ = "task_summary_read";
    public static final String ARTIFACT_SUMMARY_READ = "artifact_summary_read";

    private static final Map<String, String> CANONICAL_TO_RUNTIME = Map.of(
            TeamObserverTemplate.TEAM_ACTIVITY_READ.toString(), TEAM_ACTIVITY_READ,
            TeamObserverTemplate.TEAM_INBOX_SUMMARY_READ.toString(), TEAM_INBOX_SUMMARY_READ,
            TeamObserverTemplate.WORK_ITEM_SUMMARY_READ.toString(), WORK_ITEM_SUMMARY_READ,
            TeamObserverTemplate.TASK_SUMMARY_READ.toString(), TASK_SUMMARY_READ,
            TeamObserverTemplate.ARTIFACT_SUMMARY_READ.toString(), ARTIFACT_SUMMARY_READ);
    private static final Set<String> RUNTIME_NAMES =
            ModelToolNamePolicy.requireCompatibleNames(Set.copyOf(CANONICAL_TO_RUNTIME.values()));

    private TeamObserverToolNames() {}

    /** Requires the exact immutable Observer policy surface before returning runtime aliases. */
    public static Set<String> runtimeNamesFor(Set<String> canonicalNames) {
        Set<String> required = Set.copyOf(Objects.requireNonNull(canonicalNames, "canonicalNames"));
        if (!required.equals(CANONICAL_TO_RUNTIME.keySet())
                || !ModelToolNamePolicy.runtimeAliases(required).equals(RUNTIME_NAMES)) {
            throw new IllegalArgumentException(
                    "Team Observer Tool aliases require the exact canonical Tool surface");
        }
        return RUNTIME_NAMES;
    }

    public static Set<String> runtimeNames() {
        return RUNTIME_NAMES;
    }
}
