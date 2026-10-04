package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stable, human-readable business key of one Team Skill catalog entry. The built-in
 * read-only skill name is reserved (ADR-031 §4): a Team Skill may never publish under
 * it, so the guard lives at key construction, not just at publish time.
 */
public record TeamSkillKey(String value) {

    public static final String FORMAT_REGEX = "[a-z0-9][a-z0-9-]{0,62}";

    /** Built-in skill names a Team Skill may never take over (same value as the bundle pin). */
    public static final Set<String> RESERVED_SKILL_KEYS = Set.of("java-spring-v1");

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public TeamSkillKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(
                    "teamSkill.skillKey", "must match " + FORMAT_REGEX);
        }
        if (RESERVED_SKILL_KEYS.contains(value)) {
            throw new DomainValidationException(
                    "teamSkill.skillKey",
                    "is reserved for the built-in skill and cannot be used by a Team Skill");
        }
    }

    public static TeamSkillKey parse(String value) {
        return new TeamSkillKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
