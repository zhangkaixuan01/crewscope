package io.crewscope.agentscope.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Read-only evidence repository over one attempt's resolved dynamic Team Skills
 * (M10-A03b): exact-keyed reads, parse failures at construction, and a write
 * surface that refuses everything.
 */
class InMemoryTeamSkillRepositoryTest {

    @Test
    void exposesExactlyTheResolvedSkillsUnderTheirKeys() {
        InMemoryTeamSkillRepository repository = new InMemoryTeamSkillRepository(List.of(
                published("code-review", 2, "Review diffs line by line."),
                published("deploy-runbook", 7, "Drain the pool first.")));

        assertEquals(List.of("code-review", "deploy-runbook"), repository.getAllSkillNames());
        assertTrue(repository.skillExists("code-review"));
        assertFalse(repository.skillExists("ghost"));
        assertNull(repository.getSkill("ghost"));

        AgentSkill skill = repository.getSkill("deploy-runbook");
        assertEquals("deploy-runbook", skill.getMetadata().get("name"));
        assertTrue(skill.getSkillContent().contains("Drain the pool first."));
        assertEquals("team-skill:deploy-runbook:7", skill.getSource());

        assertEquals(2, repository.getAllSkills().size());
        assertEquals("team-skill", repository.getRepositoryInfo().getType());
        assertEquals("memory:code-review@2,deploy-runbook@7,",
                repository.getRepositoryInfo().getLocation());
        assertFalse(repository.getRepositoryInfo().isWritable());
        assertFalse(repository.isWriteable());
        assertEquals("team-skill", repository.getSource());
    }

    @Test
    void rejectsDocumentsWhoseFrontmatterNameIsNotTheSkillKey() {
        TeamSkillExecutionSource.PublishedTeamSkill forged = new TeamSkillExecutionSource.PublishedTeamSkill(
                "code-review", 1,
                document("other-name", "Mismatched name.", "Body"),
                "0".repeat(64));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new InMemoryTeamSkillRepository(List.of(forged)));

        assertTrue(failure.getMessage().contains("does not match"));
    }

    @Test
    void rejectsDuplicateKeysAndMalformedMarkdown() {
        TeamSkillExecutionSource.PublishedTeamSkill first =
                published("code-review", 1, "One.");
        TeamSkillExecutionSource.PublishedTeamSkill second =
                published("code-review", 2, "Two.");

        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryTeamSkillRepository(List.of(first, second)));
        assertThrows(RuntimeException.class, () -> new InMemoryTeamSkillRepository(
                List.of(new TeamSkillExecutionSource.PublishedTeamSkill(
                        "broken", 1, "no frontmatter at all", "0".repeat(64)))));
    }

    @Test
    void refusesEveryWriteOperation() {
        InMemoryTeamSkillRepository repository = new InMemoryTeamSkillRepository(
                List.of(published("code-review", 1, "Body.")));

        AgentSkill attempt = repository.getSkill("code-review");
        assertThrows(IllegalStateException.class,
                () -> repository.save(List.of(attempt), true));
        assertThrows(IllegalStateException.class, () -> repository.delete("code-review"));

        repository.setWriteable(true);
        assertFalse(repository.isWriteable(),
                "the evidence repository stays read-only regardless of the flag");
        assertEquals(1, repository.getAllSkillNames().size(),
                "a forced setWriteable call must not enable any mutation");
    }

    private static TeamSkillExecutionSource.PublishedTeamSkill published(
            String key, long revision, String body) {
        String content = document(key, "Distilled " + key + " procedure.", body);
        return new TeamSkillExecutionSource.PublishedTeamSkill(
                key, revision, content, "0".repeat(64));
    }

    private static String document(String key, String description, String body) {
        return "---\nname: " + key + "\ndescription: " + description + "\n---\n\n" + body;
    }
}
