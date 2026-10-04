package io.crewscope.domain.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Frozen deterministic token estimate for prompt budget accounting (M10-S01 §3.4):
 * max(ceil(utf8Bytes / 4), ceil(codePoints / 3)), floored at 1. The formula is an
 * over-estimate by design — ASCII leans on the code-point half (one token per three
 * characters), CJK on the byte half (three bytes per token, i.e. one token per
 * character), and the floor keeps blank fragments billable.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static long estimate(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            return 1;
        }
        long utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        long byBytes = (utf8Bytes + 3) / 4;
        long byCodePoints = (text.codePointCount(0, text.length()) + 2) / 3;
        return Math.max(1, Math.max(byBytes, byCodePoints));
    }

    public static long estimateAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        long total = 0;
        for (String text : texts) {
            total = Math.addExact(total, estimate(text));
        }
        return total;
    }
}
