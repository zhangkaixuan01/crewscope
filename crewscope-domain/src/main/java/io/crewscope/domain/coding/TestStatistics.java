package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Parser-observed test report counts with an exact total invariant. */
public record TestStatistics(long total, long passed, long failed, long errors, long skipped) {

    public TestStatistics {
        if (total < 0 || passed < 0 || failed < 0 || errors < 0 || skipped < 0) {
            throw new DomainValidationException("testStatistics", "counts must not be negative");
        }
        try {
            long calculated = Math.addExact(
                    Math.addExact(passed, failed), Math.addExact(errors, skipped));
            if (calculated != total) {
                throw new DomainValidationException(
                        "testStatistics.total", "must equal passed + failed + errors + skipped");
            }
        } catch (ArithmeticException exception) {
            throw new DomainValidationException("testStatistics.total", "exceeds the supported range");
        }
    }

    public boolean hasFailures() {
        return failed > 0 || errors > 0;
    }
}
