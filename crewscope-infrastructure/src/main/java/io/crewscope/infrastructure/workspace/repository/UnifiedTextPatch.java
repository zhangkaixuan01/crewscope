package io.crewscope.infrastructure.workspace.repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies bounded single-file unified hunks; the target path is always supplied separately. */
final class UnifiedTextPatch {

    private static final Pattern HUNK = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$");

    private UnifiedTextPatch() {}

    static String apply(
            String source, String patch, int maximumPatchBytes, int maximumHunks) {
        CodingFilesystemPathPolicy.requireText(source);
        CodingFilesystemPathPolicy.requireText(patch);
        if (patch.getBytes(StandardCharsets.UTF_8).length > maximumPatchBytes) {
            throw invalid("Unified patch exceeds its parser budget");
        }
        TextDocument document = TextDocument.parse(source);
        List<String> patchLines = patchLines(patch);
        if (patchLines.isEmpty()) {
            throw invalid("Unified patch must contain at least one hunk");
        }

        List<String> output = new ArrayList<>();
        int sourceCursor = 0;
        int line = 0;
        int hunks = 0;
        while (line < patchLines.size()) {
            Matcher header = HUNK.matcher(patchLines.get(line));
            if (!header.matches()) {
                throw invalid("Unified patch accepts hunk headers and hunk lines only");
            }
            if (++hunks > maximumHunks) {
                throw invalid("Unified patch contains too many hunks");
            }
            int oldStart = number(header.group(1));
            int oldCount = count(header.group(2));
            int newStart = number(header.group(3));
            int newCount = count(header.group(4));
            int oldIndex = hunkIndex(oldStart, oldCount);
            int newIndex = hunkIndex(newStart, newCount);
            if (oldIndex < sourceCursor || oldIndex > document.lines().size()) {
                throw invalid("Unified patch hunk is outside the current file");
            }
            output.addAll(document.lines().subList(sourceCursor, oldIndex));
            if (output.size() != newIndex) {
                throw invalid("Unified patch new-file position is inconsistent");
            }
            sourceCursor = oldIndex;
            line++;
            int consumed = 0;
            int produced = 0;
            while (line < patchLines.size() && !HUNK.matcher(patchLines.get(line)).matches()) {
                String patchLine = patchLines.get(line++);
                if ("\\ No newline at end of file".equals(patchLine)) {
                    continue;
                }
                if (patchLine.isEmpty()) {
                    throw invalid("Unified patch hunk line is missing its operation prefix");
                }
                char operation = patchLine.charAt(0);
                String content = patchLine.substring(1);
                switch (operation) {
                    case ' ' -> {
                        requireSourceLine(document.lines(), sourceCursor, content);
                        output.add(content);
                        sourceCursor++;
                        consumed++;
                        produced++;
                    }
                    case '-' -> {
                        requireSourceLine(document.lines(), sourceCursor, content);
                        sourceCursor++;
                        consumed++;
                    }
                    case '+' -> {
                        output.add(content);
                        produced++;
                    }
                    default -> throw invalid("Unified patch contains an unsupported hunk operation");
                }
            }
            if (consumed != oldCount || produced != newCount) {
                throw invalid("Unified patch hunk counts do not match its body");
            }
        }
        output.addAll(document.lines().subList(sourceCursor, document.lines().size()));
        return document.render(output);
    }

    private static void requireSourceLine(
            List<String> source, int index, String expected) {
        if (index >= source.size() || !source.get(index).equals(expected)) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.STALE_CONTENT,
                    "Unified patch context no longer matches the repository file");
        }
    }

    private static int hunkIndex(int start, int count) {
        if (start == 0 && count == 0) {
            return 0;
        }
        if (start < 1) {
            throw invalid("Unified patch hunk position must be positive");
        }
        return start - 1;
    }

    private static int count(String value) {
        return value == null ? 1 : number(value);
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw invalid("Unified patch number is invalid");
        }
    }

    private static List<String> patchLines(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }
        String normalized = patch.replace("\r\n", "\n");
        if (normalized.indexOf('\r') >= 0) {
            throw invalid("Unified patch uses unsupported mixed line endings");
        }
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return List.copyOf(lines);
    }

    private static CodingFilesystemException invalid(String message) {
        return new CodingFilesystemException(CodingFilesystemError.PATCH_INVALID, message);
    }

    private record TextDocument(List<String> lines, String separator, boolean trailingNewline) {

        private static TextDocument parse(String content) {
            String separator = content.contains("\r\n") ? "\r\n" : "\n";
            String normalized = content.replace("\r\n", "\n");
            if (normalized.indexOf('\r') >= 0) {
                throw new CodingFilesystemException(
                        CodingFilesystemError.BINARY_FILE,
                        "Mixed or bare carriage returns are unavailable to unified patch");
            }
            boolean trailing = normalized.endsWith("\n");
            if (normalized.isEmpty()) {
                return new TextDocument(List.of(), separator, false);
            }
            List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
            if (trailing) {
                lines.remove(lines.size() - 1);
            }
            return new TextDocument(List.copyOf(lines), separator, trailing);
        }

        private String render(List<String> nextLines) {
            String rendered = String.join(separator, nextLines);
            return trailingNewline && !nextLines.isEmpty() ? rendered + separator : rendered;
        }
    }
}
