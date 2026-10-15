package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The bucket-folding arithmetic behind deployment-wide percentiles (M12-I03):
 * cross-series boundary union with step evaluation, linear interpolation inside the
 * landing bucket, the +Inf tail falling back to the last finite boundary, and empty
 * staying empty. Hand-built snapshots keep the bucket geometry exactly known.
 */
class HistogramPercentileMergeTest {

    @Test
    void mergesSeriesAndInterpolatesInsideTheLandingBucket() {
        // Cumulative counts: A (1,2,3) + B (2,2,3) merge to (3,4,6); the 95th of 6
        // samples is 5.7, landing in the 0.016s bucket 1.7 samples past 0.008s.
        Optional<Double> p95 = HistogramPercentileMerge.percentile95Millis(List.of(
                snapshot(3, buckets(0.004, 1, 0.008, 2, 0.016, 3)),
                snapshot(3, buckets(0.004, 2, 0.008, 2, 0.016, 3))));

        assertEquals(14.8, p95.orElseThrow(), 1e-9);
    }

    @Test
    void theInfiniteTailBucketFallsBackToTheLastFiniteBoundary() {
        // Everything sits in the +Inf tail: the honest answer is the last finite boundary.
        Optional<Double> p95 = HistogramPercentileMerge.percentile95Millis(List.of(
                snapshot(2, new CountAtBucket(4_000_000.0, 0),
                        new CountAtBucket(Double.POSITIVE_INFINITY, 2))));

        assertEquals(4.0, p95.orElseThrow(), 1e-9);
    }

    @Test
    void seriesWithoutSharedBoundariesAreStepEvaluatedAtTheUnion() {
        // A holds (0.01s -> 3); B holds (0.004s -> 1, 0.02s -> 2). The union resolves
        // A at 0.004s and 0.02s by its step, B at 0.01s by its step below it.
        Optional<Double> p95 = HistogramPercentileMerge.percentile95Millis(List.of(
                snapshot(3, buckets(0.01, 3)),
                snapshot(2, buckets(0.004, 1, 0.02, 2))));

        // Merged: 0.004 -> 1, 0.01 -> 4, 0.02 -> 5; target 4.75 lands 0.75 into
        // the last bucket's single sample.
        assertEquals(17.5, p95.orElseThrow(), 1e-9);
    }

    @Test
    void withoutSamplesThereIsNoAnswer() {
        assertTrue(HistogramPercentileMerge.percentile95Millis(List.of()).isEmpty());
        assertTrue(HistogramPercentileMerge.percentile95Millis(
                List.of(snapshot(0, buckets(0.004, 0)))).isEmpty());
    }

    private static HistogramSnapshot snapshot(long count, CountAtBucket... histogramCounts) {
        return new HistogramSnapshot(count, count, 0, null, histogramCounts, null);
    }

    /** Alternating boundary-seconds and cumulative-count pairs; Timer buckets are
     *  nanosecond doubles, so second-valued boundaries are scaled up here. */
    private static CountAtBucket[] buckets(double... boundaryThenCount) {
        CountAtBucket[] buckets = new CountAtBucket[boundaryThenCount.length / 2];
        for (int index = 0; index < buckets.length; index++) {
            buckets[index] = new CountAtBucket(
                    boundaryThenCount[index * 2] * 1_000_000_000.0,
                    boundaryThenCount[index * 2 + 1]);
        }
        return buckets;
    }
}
