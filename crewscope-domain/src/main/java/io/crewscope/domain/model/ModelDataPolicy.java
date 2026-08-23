package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable provider data retention and model-training policy. */
public record ModelDataPolicy(
        ModelDataRetentionMode retentionMode,
        Optional<Duration> maximumRetention,
        ModelTrainingUsagePolicy trainingUsagePolicy) {

    public static final Duration MAX_TIME_BOUND_RETENTION = Duration.ofDays(3_650);

    public ModelDataPolicy {
        retentionMode = Objects.requireNonNull(retentionMode, "retentionMode");
        maximumRetention = Objects.requireNonNull(maximumRetention, "maximumRetention");
        trainingUsagePolicy = Objects.requireNonNull(
                trainingUsagePolicy, "trainingUsagePolicy");
        if (retentionMode == ModelDataRetentionMode.TIME_BOUND) {
            Duration duration = maximumRetention.orElseThrow(() ->
                    new DomainValidationException(
                            "modelProvider.dataPolicy.maximumRetention",
                            "is required for TIME_BOUND retention"));
            if (duration.isZero()
                    || duration.isNegative()
                    || duration.getNano() != 0
                    || duration.compareTo(MAX_TIME_BOUND_RETENTION) > 0) {
                throw new DomainValidationException(
                        "modelProvider.dataPolicy.maximumRetention",
                        "must use whole seconds, be positive and at most 3650 days");
            }
        } else if (maximumRetention.isPresent()) {
            throw new DomainValidationException(
                    "modelProvider.dataPolicy.maximumRetention",
                    "is only allowed for TIME_BOUND retention");
        }
    }

    public static ModelDataPolicy noRetention() {
        return new ModelDataPolicy(
                ModelDataRetentionMode.NONE,
                Optional.empty(),
                ModelTrainingUsagePolicy.PROHIBITED);
    }

    String canonicalValue() {
        return retentionMode.name()
                + ":"
                + maximumRetention.map(Duration::getSeconds).map(Object::toString)
                        .orElse("none")
                + ":"
                + trainingUsagePolicy.name();
    }
}
