package io.crewscope.application.skill;

import java.util.Objects;

/**
 * Draft replacement. The document is the full SKILL.md; every non-publish mutation of
 * the catalog content travels through here.
 */
public record UpdateTeamSkillDraftCommand(String content) {

    public UpdateTeamSkillDraftCommand {
        Objects.requireNonNull(content, "content");
    }
}
