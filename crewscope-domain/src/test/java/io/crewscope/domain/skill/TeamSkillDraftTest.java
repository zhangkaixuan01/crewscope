package io.crewscope.domain.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** Frontmatter contract of the SKILL.md draft document (M10-A03a). */
final class TeamSkillDraftTest {

    private static final String DOCUMENT = """
            ---
            name: deploy-runbook-v2
            description: Standard rollback drill for the staging deploy.
            ---

            # Deploy runbook v2

            Verify the migration tip before promoting.
            """;

    @Test
    void parsesTheFrontmatterOfAWellFormedDocument() {
        TeamSkillDraft draft = new TeamSkillDraft(DOCUMENT);

        assertEquals("deploy-runbook-v2", draft.name());
        assertEquals("Standard rollback drill for the staging deploy.", draft.description());
        assertEquals(draft.contentHash(), SkillContentHash.of(draft.content()));
    }

    @Test
    void toleratesExtraFrontmatterFieldsAndTrailingWhitespace() {
        TeamSkillDraft draft = new TeamSkillDraft(DOCUMENT.replace(
                "description: Standard rollback drill for the staging deploy.",
                "description: Standard rollback drill for the staging deploy.\nlabels: draft"));

        assertEquals("Standard rollback drill for the staging deploy.", draft.description());
    }

    @Test
    void refusesDuplicateNameOrDescriptionFields() {
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft(DOCUMENT.replace(
                        "description: Standard rollback drill for the staging deploy.",
                        "name: deploy-runbook-v2\ndescription: Standard rollback drill for the staging deploy.")));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft(DOCUMENT.replace(
                        "description: Standard rollback drill for the staging deploy.",
                        "description: Standard rollback drill for the staging deploy.\ndescription: A smuggled second value.")));
    }

    @Test
    void refusesADocumentWithoutFrontmatter() {
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("# Just a heading\n\nNo frontmatter at all."));
    }

    @Test
    void refusesAnUnclosedFrontmatterBlock() {
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("---\nname: x\ndescription: y\n"));
    }

    @Test
    void refusesAMissingNameOrDescription() {
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("---\ndescription: missing name\n---\nbody"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("---\nname: x\n---\nbody"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("---\nname: \ndescription: blank name\n---\nbody"));
    }

    @Test
    void refusesABlankBodyOrOverlongFields() {
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("---\nname: x\ndescription: d\n---\n   \n"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft(
                        "---\nname: x\ndescription: " + "y".repeat(201) + "\n---\nbody"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSkillDraft("---\nname: x\ndescription: d\n---\n"
                        + "z".repeat(TeamSkillDraft.MAX_CONTENT_LENGTH + 1)));
    }
}
