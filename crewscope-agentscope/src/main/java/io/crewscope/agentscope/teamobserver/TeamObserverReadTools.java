package io.crewscope.agentscope.teamobserver;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.util.JsonUtils;
import io.crewscope.application.teamobserver.TeamObserverReadService;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/** Invocation-bound implementation of the five fixed, parameter-free Observer read Tools. */
final class TeamObserverReadTools {

    private final TeamObserverReadService reads;
    private final TeamSummaryRequest request;
    private final TeamObserverEvidenceCatalog evidence;

    TeamObserverReadTools(
            TeamObserverReadService reads,
            TeamSummaryRequest request,
            TeamObserverEvidenceCatalog evidence) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.request = Objects.requireNonNull(request, "request");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Tool(
            name = "team.activity.read",
            readOnly = true,
            description = "Read bounded member-visible Team progress, blocker and anomaly activity.")
    public String teamActivity() {
        return read(TeamSummaryDataScope.TEAM_ACTIVITY);
    }

    @Tool(
            name = "team.inbox.summary.read",
            readOnly = true,
            description = "Read bounded member-visible Review, confirmation and anomaly Inbox summaries.")
    public String teamInboxSummary() {
        return read(TeamSummaryDataScope.TEAM_INBOX_SUMMARY);
    }

    @Tool(
            name = "workitem.summary.read",
            readOnly = true,
            description = "Read bounded member-visible WorkItem progress, blockers and Review backlog.")
    public String workItemSummary() {
        return read(TeamSummaryDataScope.WORK_ITEM_SUMMARY);
    }

    @Tool(
            name = "task.summary.read",
            readOnly = true,
            description = "Read bounded member-visible Task progress, blockers, Review and anomalies.")
    public String taskSummary() {
        return read(TeamSummaryDataScope.TASK_SUMMARY);
    }

    @Tool(
            name = "artifact.summary.read",
            readOnly = true,
            description = "Read bounded member-visible Artifact progress summaries.")
    public String artifactSummary() {
        return read(TeamSummaryDataScope.ARTIFACT_SUMMARY);
    }

    private String read(TeamSummaryDataScope dataScope) {
        List<TeamSummaryEntry> values = reads.read(request, dataScope);
        evidence.observe(values);
        List<Map<String, String>> minimized = values.stream().map(entry -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("section", entry.section().name());
            item.put("summary", entry.summary());
            item.put("evidencePath", entry.evidencePath());
            return Collections.unmodifiableMap(item);
        }).toList();
        return JsonUtils.getJsonCodec().toJson(minimized);
    }
}
