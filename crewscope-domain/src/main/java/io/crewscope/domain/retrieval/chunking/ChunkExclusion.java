package io.crewscope.domain.retrieval.chunking;

import java.util.List;

/**
 * Glob matcher for the chunking exclusion list (M10-S01 §3.3). Patterns are gitignore-like
 * but deliberately small: {@code *} matches any run of non-separator characters, a pattern
 * ending in {@code /} matches everything under that directory, and any other pattern
 * matches a path suffix on segment boundaries (so {@code .env*} matches both a root
 * {@code .env.local} and {@code deploy/.env}). Exclusion is an aid, not a secrecy guarantee.
 */
public final class ChunkExclusion {

    private ChunkExclusion() {
    }

    /** Whether any pattern excludes the given repository path. */
    public static boolean matchesAny(List<String> patterns, String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        for (String pattern : patterns) {
            if (matches(pattern, normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String path) {
        if (pattern.endsWith("/")) {
            // Directory pattern: matches that directory at any depth, gitignore-style.
            String directory = pattern.substring(0, pattern.length() - 1);
            if (globMatch(directory, path)) {
                return true;
            }
            return path.startsWith(directory + "/")
                    || path.endsWith("/" + directory)
                    || path.contains("/" + directory + "/");
        }
        if (pattern.contains("/")) {
            // Anchored pattern: match the whole path.
            return globMatch(pattern, path);
        }
        // Bare pattern: match the whole path or any trailing segment sequence.
        if (globMatch(pattern, path)) {
            return true;
        }
        int slash = path.indexOf('/');
        while (slash >= 0) {
            if (globMatch(pattern, path.substring(slash + 1))) {
                return true;
            }
            slash = path.indexOf('/', slash + 1);
        }
        return false;
    }

    /** Classic two-pointer glob match where {@code *} spans any run of non-{@code /} characters. */
    private static boolean globMatch(String pattern, String text) {
        int patternIndex = 0;
        int textIndex = 0;
        int starPatternIndex = -1;
        int starTextIndex = -1;
        while (textIndex < text.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starPatternIndex = patternIndex++;
                starTextIndex = textIndex;
            } else if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == text.charAt(textIndex))) {
                patternIndex++;
                textIndex++;
            } else if (starPatternIndex >= 0) {
                // Extend the star's span; '*' never crosses '/'.
                if (text.charAt(textIndex) == '/') {
                    return false;
                }
                patternIndex = starPatternIndex + 1;
                textIndex = ++starTextIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
