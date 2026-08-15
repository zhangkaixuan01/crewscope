package io.crewscope.application.runtime;

/** Member-safe capacity totals for fresh ACTIVE Workers in one environment. */
public record RuntimeCapacitySummary(int maximum, int active, int available) {

    public RuntimeCapacitySummary {
        if (maximum < 0 || active < 0 || available < 0 || active + available != maximum) {
            throw new IllegalArgumentException("runtime capacity totals must be non-negative and closed");
        }
    }
}
