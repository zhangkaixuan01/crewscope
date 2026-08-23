package io.crewscope.application.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical SHA-256 helper for non-secret GitHub authority facts. */
public final class GitHubHash {

    private GitHubHash() {}

    public static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String canonicalCollection(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        return sha256(values.stream()
                .map(GitHubHash::requireText)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    static String requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    static String requireText(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("GitHub text must be non-blank");
        }
        return value.strip();
    }
}
