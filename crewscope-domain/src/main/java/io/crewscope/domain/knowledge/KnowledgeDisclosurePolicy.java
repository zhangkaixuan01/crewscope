package io.crewscope.domain.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Command-level pre-publish disclosure check for distilled entries (S01 §3.5): content
 * distilled from a Task execution may carry material the wider audience must not see, so
 * publishing is refused when a high-confidence secret pattern family matches.
 *
 * <p>Deliberately a curated allow-list of high-confidence families (provider API keys,
 * cloud access keys, SCM tokens, PEM private keys). Low-confidence shapes such as generic
 * base64 blobs or {@code password=} assignments are excluded on purpose: a text scan is an
 * auxiliary guard rail, never a guarantee that no secret slipped through (S01 §8.6), and a
 * false positive would block legitimate publications of runbooks about credential rotation.
 */
public final class KnowledgeDisclosurePolicy {

    /** One named high-confidence secret shape; the family name is the only safe detail. */
    private record Family(String name, Pattern pattern) {}

    private static final List<Family> FAMILIES = List.of(
            new Family("aws_access_key_id", Pattern.compile("AKIA[0-9A-Z]{16}")),
            // The proj form is listed first and much longer: real project keys are 40+ mixed
            // characters, while the bare form must exclude '-'/'_' entirely so ordinary
            // hyphenated phrases ("task-management-guidelines") never match.
            new Family("openai_api_key", Pattern.compile("sk-proj-[A-Za-z0-9_-]{40,}|\\bsk-[A-Za-z0-9]{20,}")),
            new Family("github_token", Pattern.compile("(?:ghp|gho|ghs|ghr)_[A-Za-z0-9]{30,}")),
            new Family("github_pat", Pattern.compile("github_pat_[A-Za-z0-9_]{22,}")),
            new Family("slack_token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}")),
            new Family("google_api_key", Pattern.compile("AIza[0-9A-Za-z_-]{35}")),
            new Family("pem_private_key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")));

    private KnowledgeDisclosurePolicy() {}

    /** Returns the first matching family name, without ever exposing the matched text. */
    public static Optional<String> scan(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        for (Family family : FAMILIES) {
            if (family.pattern().matcher(text).find()) {
                return Optional.of(family.name());
            }
        }
        return Optional.empty();
    }

    /**
     * Publish gate for distilled entries: refuses when the title or content matches a
     * protected pattern family. Callers check only the draft being published — attribution
     * metadata (origin) never contains free text.
     */
    public static void requireDisclosable(String title, String content) {
        scan(title).or(() -> scan(content)).ifPresent(family -> {
            throw new KnowledgeDisclosureViolationException(family);
        });
    }
}
