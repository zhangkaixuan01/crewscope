package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.TestStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Maven Surefire/Failsafe aggregate result lines from bounded command output. */
final class MavenTestSummaryParser {

    private static final Pattern SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private MavenTestSummaryParser() {}

    static Optional<TestStatistics> parse(String stdout, String stderr) {
        String output = (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
        try {
            List<long[]> aggregate = aggregateSummaries(output);
            if (aggregate.isEmpty()) {
                aggregate = allSummaries(output);
            }
            if (aggregate.isEmpty()) {
                return Optional.empty();
            }
            long total = 0;
            long failed = 0;
            long errors = 0;
            long skipped = 0;
            for (long[] value : aggregate) {
                total = Math.addExact(total, value[0]);
                failed = Math.addExact(failed, value[1]);
                errors = Math.addExact(errors, value[2]);
                skipped = Math.addExact(skipped, value[3]);
            }
            long passed = Math.subtractExact(
                    Math.subtractExact(Math.subtractExact(total, failed), errors), skipped);
            if (passed < 0) {
                return Optional.empty();
            }
            return Optional.of(new TestStatistics(total, passed, failed, errors, skipped));
        } catch (ArithmeticException | NumberFormatException invalidSummary) {
            return Optional.empty();
        }
    }

    /** Surefire emits one aggregate summary after each Results marker. */
    private static List<long[]> aggregateSummaries(String output) {
        List<long[]> values = new ArrayList<>();
        boolean awaitingAggregate = false;
        for (String line : output.split("\\R")) {
            String plain = line.replaceFirst("^\\[[A-Z]+]\\s*", "").strip();
            if ("Results:".equals(plain)) {
                awaitingAggregate = true;
                continue;
            }
            if (!awaitingAggregate) {
                continue;
            }
            Matcher matcher = SUMMARY.matcher(plain);
            if (matcher.find()) {
                values.add(counts(matcher));
                awaitingAggregate = false;
            }
        }
        return values;
    }

    /** Supports concise project scripts and Maven configurations that omit the Results marker. */
    private static List<long[]> allSummaries(String output) {
        List<long[]> values = new ArrayList<>();
        Matcher matcher = SUMMARY.matcher(output);
        while (matcher.find()) {
            values.add(counts(matcher));
        }
        return values.size() <= 1 ? values : List.of(values.get(values.size() - 1));
    }

    private static long[] counts(Matcher matcher) {
        return new long[] {
            Long.parseLong(matcher.group(1)),
            Long.parseLong(matcher.group(2)),
            Long.parseLong(matcher.group(3)),
            Long.parseLong(matcher.group(4))
        };
    }
}
