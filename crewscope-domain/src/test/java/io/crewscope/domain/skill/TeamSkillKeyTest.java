package io.crewscope.domain.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** Regex gate and the built-in reserved-name refusal of the Team Skill business key. */
final class TeamSkillKeyTest {

    @Test
    void acceptsLowercaseSlugKeys() {
        assertEquals("deploy-runbook-v2", TeamSkillKey.parse("deploy-runbook-v2").value());
        assertEquals("a", TeamSkillKey.parse("a").value());
        assertEquals("9" + "-x".repeat(26), TeamSkillKey.parse("9" + "-x".repeat(26)).value());
    }

    @Test
    void rejectsKeysOutsideTheSlugFormat() {
        assertThrows(DomainValidationException.class, () -> TeamSkillKey.parse(null));
        assertThrows(DomainValidationException.class, () -> TeamSkillKey.parse(""));
        assertThrows(DomainValidationException.class, () -> TeamSkillKey.parse("Upper"));
        assertThrows(DomainValidationException.class, () -> TeamSkillKey.parse("-leading"));
        assertThrows(DomainValidationException.class, () -> TeamSkillKey.parse("has_underscore"));
        assertThrows(
                DomainValidationException.class,
                () -> TeamSkillKey.parse("a".repeat(64)));
    }

    @Test
    void refusesTheBuiltInReservedName() {
        // java-spring-v1 belongs to the pinned built-in bundle (ADR-031 §4); a Team Skill
        // may never publish under it, and the guard already fires at key construction.
        DomainValidationException failure = assertThrows(
                DomainValidationException.class, () -> TeamSkillKey.parse("java-spring-v1"));
        assertEquals("teamSkill.skillKey", failure.error().details().get("field"));
    }
}
