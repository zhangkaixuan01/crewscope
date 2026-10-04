package io.crewscope.agentscope.coding;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only in-memory skill repository over one attempt's resolved dynamic Team Skills
 * (M10-A03b). Each instance is private to one short-lived agent and is built from the
 * exact resolved (skillKey, revision, content) triples the sealed injection manifest
 * proves, so the loader can never serve a version the evidence does not cover. Write
 * operations are refused: the catalog's lifecycle commands are the only writers.
 */
public final class InMemoryTeamSkillRepository implements AgentSkillRepository {

    private static final String REPOSITORY_TYPE = "team-skill";
    private static final String SOURCE = "team-skill";

    private final Map<String, AgentSkill> skills;
    private final String location;

    /** Parses every resolved document up front, so a malformed skill fails the load fast. */
    public InMemoryTeamSkillRepository(List<TeamSkillExecutionSource.PublishedTeamSkill> resolved) {
        List<TeamSkillExecutionSource.PublishedTeamSkill> required =
                Objects.requireNonNull(resolved, "resolved");
        Map<String, AgentSkill> parsed = new LinkedHashMap<>();
        StringBuilder locationBuilder = new StringBuilder("memory:");
        for (TeamSkillExecutionSource.PublishedTeamSkill published : required) {
            if (parsed.put(published.skillKey(), parse(published)) != null) {
                throw new IllegalArgumentException(
                        "duplicate dynamic Team Skill key: " + published.skillKey());
            }
            locationBuilder.append(published.skillKey())
                    .append('@').append(published.revision()).append(',');
        }
        this.skills = Collections.unmodifiableMap(parsed);
        this.location = locationBuilder.toString();
    }

    private static AgentSkill parse(TeamSkillExecutionSource.PublishedTeamSkill published) {
        MarkdownSkillParser.ParsedMarkdown document =
                MarkdownSkillParser.parse(published.content());
        Object name = document.getMetadata().get("name");
        if (!published.skillKey().equals(name)) {
            throw new IllegalStateException(
                    "dynamic Team Skill document name does not match its key: "
                            + published.skillKey());
        }
        return new AgentSkill(
                document.getMetadata(),
                document.getContent(),
                Map.of(),
                SOURCE + ":" + published.skillKey() + ":" + published.revision());
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skills.get(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(skills.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return List.copyOf(skills.values());
    }

    @Override
    public boolean save(List<AgentSkill> values, boolean overwrite) {
        throw new IllegalStateException(
                "the dynamic Team Skill repository is read-only evidence");
    }

    @Override
    public boolean delete(String name) {
        throw new IllegalStateException(
                "the dynamic Team Skill repository is read-only evidence");
    }

    @Override
    public boolean skillExists(String name) {
        return skills.containsKey(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(REPOSITORY_TYPE, location, false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
        // Read-only by design; the catalog's lifecycle commands are the only writers.
    }

    @Override
    public boolean isWriteable() {
        return false;
    }
}
