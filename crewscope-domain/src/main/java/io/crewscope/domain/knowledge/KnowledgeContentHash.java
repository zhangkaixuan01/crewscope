package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical SHA-256 digest over the content-bearing fields of a Knowledge entry version
 * (title and content; the revision number is deliberately excluded so identical content
 * stays addressable and re-publishing unchanged content remains a detectable conflict).
 */
public record KnowledgeContentHash(String value) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public KnowledgeContentHash {
        if (value == null) {
            throw new DomainValidationException("knowledgeContentHash", "must not be null");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(value).matches()) {
            throw new DomainValidationException(
                    "knowledgeContentHash", "must be a 64-character lowercase SHA-256 value");
        }
    }

    public static KnowledgeContentHash of(String title, String content) {
        if (title == null || content == null) {
            throw new DomainValidationException(
                    "knowledgeContent", "title and content must not be null");
        }
        String canonical = "knowledge-entry-version\u0000" + title + "\u0000" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new KnowledgeContentHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
