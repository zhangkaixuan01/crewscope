package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/**
 * Reports a Team Skill draft whose document matches a protected disclosure family.
 * Unlike knowledge entries, the scan is unconditional: a published skill is injected
 * into the prompts of every future task in the Team, so no origin distinction applies
 * (ADR-031 §2 — the disclosure check is a command-level gate).
 */
public final class TeamSkillDisclosureViolationException extends DomainException {

    public TeamSkillDisclosureViolationException(String patternFamily) {
        super(new DomainError(
                DomainErrorCode.SKILL_DISCLOSURE_DENIED,
                "Team Skill document matches a protected pattern family and cannot be published",
                Map.of("patternFamily", Objects.requireNonNull(patternFamily, "patternFamily"))));
    }
}
