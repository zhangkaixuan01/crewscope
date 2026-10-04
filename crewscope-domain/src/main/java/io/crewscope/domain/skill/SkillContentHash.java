package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical SHA-256 digest over the full SKILL.md document of one Team Skill version.
 * The revision number is deliberately excluded: a rollback re-publishes historical
 * content as a new revision and the digest must stay identical, proving the content
 * really is the historical bytes (ADR-031 §4 "rollback activates history as a new
 * revision"). No unique constraint sits on this digest — identical content across
 * revisions is legal by design.
 */
public record SkillContentHash(String value) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public SkillContentHash {
        if (value == null) {
            throw new DomainValidationException("skillContentHash", "must not be null");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(value).matches()) {
            throw new DomainValidationException(
                    "skillContentHash", "must be a 64-character lowercase SHA-256 value");
        }
    }

    public static SkillContentHash of(String content) {
        if (content == null) {
            throw new DomainValidationException("skillContent", "must not be null");
        }
        String canonical = "team-skill-version\u0000" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new SkillContentHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
