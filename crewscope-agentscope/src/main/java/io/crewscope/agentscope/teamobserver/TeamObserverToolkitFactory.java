package io.crewscope.agentscope.teamobserver;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.crewscope.application.teamobserver.TeamObserverReadService;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.Objects;

/** Creates one fresh invocation-bound Toolkit with no writable or parameterized Tool surface. */
final class TeamObserverToolkitFactory {

    private final TeamObserverReadService reads;
    private final TeamObserverTemplateRuntimeRegistry templates;

    TeamObserverToolkitFactory(
            TeamObserverReadService reads, TeamObserverTemplateRuntimeRegistry templates) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    Toolkit create(TeamSummaryRequest request, TeamObserverEvidenceCatalog evidence) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TeamObserverReadTools(reads, request, evidence));
        if (!toolkit.getToolNames().equals(templates.runtimeToolNames())) {
            throw new IllegalStateException(
                    "Team Observer Toolkit must contain exactly the five fixed read Tools");
        }
        for (String name : templates.runtimeToolNames()) {
            AgentTool tool = Objects.requireNonNull(toolkit.getTool(name), "tool " + name);
            if (!tool.isReadOnly()) {
                throw new IllegalStateException("Team Observer Tool must be read-only: " + name);
            }
        }
        return toolkit;
    }
}
