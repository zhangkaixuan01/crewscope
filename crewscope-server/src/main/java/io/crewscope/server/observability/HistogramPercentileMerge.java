package io.crewscope.server.observability;

import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Folds same-name Timer histograms into one deployment-wide percentile (M12-I03).
 * Client-side precomputed percentiles cannot be merged across series, so bucket counts
 * are aggregated instead: boundary union, per-series cumulative step evaluation, and
 * linear interpolation inside the bucket where the 95th percentile lands. Timer
 * buckets arrive in nanoseconds; the answer is milliseconds.
 */
public final class HistogramPercentileMerge {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private HistogramPercentileMerge() {}

    /** Empty when no snapshot carries a sample (no series, or series without histograms). */
    public static Optional<Double> percentile95Millis(List<HistogramSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        double total = snapshots.stream().mapToDouble(HistogramSnapshot::count).sum();
        NavigableMap<Double, Double> merged = mergedCumulative(snapshots);
        if (total <= 0 || merged.isEmpty()) {
            return Optional.empty();
        }
        double target = 0.95 * total;
        Map.Entry<Double, Double> previous = null;
        for (Map.Entry<Double, Double> bucket : merged.entrySet()) {
            if (bucket.getValue() >= target) {
                return Optional.of(interpolateMillis(bucket, previous, target));
            }
            previous = bucket;
        }
        return Optional.empty();
    }

    /** Sums every series's cumulative count at the union of all series' finite
     *  boundaries; keys are normalized to milliseconds because Timer bucket
     *  boundaries are nanosecond doubles. A series owning no bucket at a foreign
     *  boundary still contributes its step below it, so a boundary owned by one
     *  series carries the merged cumulative, not just that series' count. */
    private static NavigableMap<Double, Double> mergedCumulative(List<HistogramSnapshot> snapshots) {
        NavigableMap<Double, Double> boundaries = new TreeMap<>();
        boolean infiniteTail = false;
        for (HistogramSnapshot snapshot : snapshots) {
            for (CountAtBucket value : snapshot.histogramCounts()) {
                if (Double.isInfinite(value.bucket())) {
                    infiniteTail = true;
                } else {
                    boundaries.put(value.bucket() / NANOS_PER_MILLI, 0.0);
                }
            }
        }
        NavigableMap<Double, Double> merged = new TreeMap<>();
        for (Map.Entry<Double, Double> boundary : boundaries.entrySet()) {
            double nanos = boundary.getKey() * NANOS_PER_MILLI;
            double sum = 0;
            for (HistogramSnapshot snapshot : snapshots) {
                sum += stepEvaluate(snapshot, nanos);
            }
            merged.put(boundary.getKey(), sum);
        }
        if (infiniteTail) {
            // Samples beyond the largest finite boundary land here; the caller falls
            // back to that boundary as the honest upper bound.
            merged.put(Double.POSITIVE_INFINITY,
                    snapshots.stream().mapToDouble(HistogramSnapshot::count).sum());
        }
        return merged;
    }

    /** The series's cumulative count at {@code boundary} (its last bucket at or below it). */
    private static double stepEvaluate(HistogramSnapshot snapshot, double boundary) {
        double cumulative = 0;
        for (CountAtBucket value : snapshot.histogramCounts()) {
            if (value.bucket() > boundary) {
                break;
            }
            cumulative = value.count();
        }
        return cumulative;
    }

    private static double interpolateMillis(
            Map.Entry<Double, Double> bucket, Map.Entry<Double, Double> previous, double target) {
        double upperMillis = bucket.getKey();
        if (Double.isInfinite(upperMillis)) {
            // The +Inf tail bucket: the last finite boundary is the honest upper bound.
            return previous == null ? 0 : previous.getKey();
        }
        double lowerMillis = previous == null ? 0 : previous.getKey();
        double lowerCount = previous == null ? 0 : previous.getValue();
        double bucketCount = bucket.getValue() - lowerCount;
        if (bucketCount <= 0) {
            return upperMillis;
        }
        double fraction = (target - lowerCount) / bucketCount;
        return lowerMillis + fraction * (upperMillis - lowerMillis);
    }
}
