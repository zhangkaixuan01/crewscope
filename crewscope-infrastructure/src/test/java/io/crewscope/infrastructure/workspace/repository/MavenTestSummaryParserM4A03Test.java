package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** M4-A03 proof that TestStatistics come from Maven-observed result summaries. */
class MavenTestSummaryParserM4A03Test {

    @Test
    void sumsOnlySurefireAggregateResultsAcrossModules() {
        String output = """
                [INFO] Running sample.FirstTest
                [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in sample.FirstTest
                [INFO] Results:
                [INFO]
                [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
                [INFO] Running sample.SecondTest
                [INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 1 -- in sample.SecondTest
                [INFO] Results:
                [INFO]
                [INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 1
                """;

        var statistics = MavenTestSummaryParser.parse(output, "").orElseThrow();

        assertEquals(5, statistics.total());
        assertEquals(3, statistics.passed());
        assertEquals(1, statistics.failed());
        assertEquals(0, statistics.errors());
        assertEquals(1, statistics.skipped());
    }

    @Test
    void supportsOneConciseSummaryAndRejectsUnparsedOutput() {
        var statistics = MavenTestSummaryParser.parse(
                        "Tests run: 4, Failures: 0, Errors: 1, Skipped: 1", "")
                .orElseThrow();

        assertEquals(4, statistics.total());
        assertEquals(2, statistics.passed());
        assertTrue(MavenTestSummaryParser.parse("BUILD SUCCESS", "").isEmpty());
        assertTrue(MavenTestSummaryParser.parse(
                        "Tests run: 999999999999999999999, Failures: 0, Errors: 0, Skipped: 0",
                        "")
                .isEmpty());
    }
}
