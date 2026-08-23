package io.crewscope.application.review;

import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed parser for the bounded UTF-8 unified patch emitted by the M4 Git adapter. */
final class ReviewPatchHunkParser {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$");

    List<ReviewDiffHunk> parse(String patch, Set<DiffPath> changedPaths) {
        String source = Objects.requireNonNull(patch, "patch");
        Set<DiffPath> allowed = Set.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        if (source.isBlank() || source.indexOf('\0') >= 0 || source.indexOf('\r') >= 0) {
            throw invalid("Patch must be non-empty canonical UTF-8 text");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > ContextPackage.MAX_PATCH_BYTES) {
            throw invalid("Patch exceeds the Reviewer context byte budget");
        }

        List<Line> lines = lines(source);
        List<ReviewDiffHunk> result = new ArrayList<>();
        DiffPath oldPath = null;
        DiffPath newPath = null;
        for (int index = 0; index < lines.size(); index++) {
            String text = lines.get(index).text();
            if (text.startsWith("diff --git ")) {
                // A new file section must establish its own headers before any hunk is accepted.
                oldPath = null;
                newPath = null;
                continue;
            }
            if (text.startsWith("--- ")) {
                oldPath = parseHeaderPath(text.substring(4));
                continue;
            }
            if (text.startsWith("+++ ")) {
                newPath = parseHeaderPath(text.substring(4));
                continue;
            }
            Matcher header = HUNK_HEADER.matcher(text);
            if (!header.matches()) {
                continue;
            }
            DiffPath path = newPath != null ? newPath : oldPath;
            if (path == null || !allowed.contains(path)) {
                throw invalid("Hunk path is outside the exact Diff manifest");
            }
            int oldCount = count(header.group(2));
            int newStart = integer(header.group(3), "newStart");
            int newCount = count(header.group(4));
            int bodyStart = index + 1;
            int bodyEnd = bodyStart;
            int observedOld = 0;
            int observedNew = 0;
            while (bodyEnd < lines.size()) {
                String body = lines.get(bodyEnd).text();
                if (body.startsWith("diff --git ")
                        || HUNK_HEADER.matcher(body).matches()) {
                    break;
                }
                if (body.startsWith("\\ No newline at end of file")) {
                    bodyEnd++;
                    continue;
                }
                if (body.isEmpty()) {
                    throw invalid("Hunk body line is missing an operation prefix");
                }
                switch (body.charAt(0)) {
                    case ' ' -> {
                        observedOld++;
                        observedNew++;
                    }
                    case '-' -> observedOld++;
                    case '+' -> observedNew++;
                    default -> throw invalid("Hunk contains an unsupported body line");
                }
                bodyEnd++;
            }
            if (observedOld != oldCount || observedNew != newCount) {
                throw invalid("Hunk header counts do not match its body");
            }
            int startLine = Math.max(1, newStart);
            int endLine = newCount == 0 ? startLine : Math.addExact(startLine, newCount - 1);
            String hunkPatch = source.substring(
                    lines.get(index).startOffset(),
                    bodyEnd == lines.size() ? source.length() : lines.get(bodyEnd).startOffset());
            result.add(ReviewDiffHunk.captured(path.value(), startLine, endLine, hunkPatch));
            if (result.size() > ContextPackage.MAX_DIFF_HUNKS) {
                throw invalid("Patch exceeds the Reviewer hunk budget");
            }
            index = bodyEnd - 1;
        }
        if (result.isEmpty()) {
            throw invalid("Patch does not contain a reviewable text hunk");
        }
        return List.copyOf(result);
    }

    private static DiffPath parseHeaderPath(String raw) {
        String value = raw.strip();
        if ("/dev/null".equals(value)) {
            return null;
        }
        if (value.startsWith("\"") || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid("Quoted or decorated patch paths are not accepted");
        }
        if (value.startsWith("a/") || value.startsWith("b/")) {
            value = value.substring(2);
        }
        return new DiffPath(value);
    }

    private static int count(String raw) {
        return raw == null ? 1 : integer(raw, "count");
    }

    private static int integer(String raw, String field) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) {
                throw invalid(field + " must not be negative");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalid(field + " is outside the supported range");
        }
    }

    private static List<Line> lines(String value) {
        List<Line> result = new ArrayList<>();
        int offset = 0;
        while (offset < value.length()) {
            int newline = value.indexOf('\n', offset);
            int end = newline < 0 ? value.length() : newline;
            result.add(new Line(offset, value.substring(offset, end)));
            offset = newline < 0 ? value.length() : newline + 1;
        }
        return result;
    }

    private static DomainValidationException invalid(String message) {
        return new DomainValidationException("contextPackage.patch", message);
    }

    private record Line(int startOffset, String text) {}
}
