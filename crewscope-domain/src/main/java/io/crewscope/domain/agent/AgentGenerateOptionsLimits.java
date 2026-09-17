package io.crewscope.domain.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The ranges a member may configure in the provider-neutral GenerateOptions, written down once.
 *
 * <p>Three readers must never disagree about these numbers: {@link SafeModelGenerateOptions} rejects
 * out-of-range values with them, the configuration form renders them as the {@code min}/{@code max}/
 * {@code step} of its inputs, and the boundary tests in this module pin them. The Web copy is
 * generated from this file by {@code scripts/generate-agent-limits.mjs} and re-generated in CI, so a
 * bound changed here that the form does not follow fails the build instead of letting a member type a
 * value the server will refuse.
 *
 * <p>The bounds are held as decimal text rather than as {@link BigDecimal} on purpose. The exact
 * spelling is part of what the form publishes — the temperature step is {@code 0.01} and not
 * {@code 0.010} or {@code 1E-2} — and text is what the generator can read out of this file without
 * evaluating Java. {@link Limit} converts once, on the way in.
 */
public final class AgentGenerateOptionsLimits {

    /** How the value is validated on both sides: a decimal bound or a whole number. */
    public enum Shape {
        DECIMAL,
        INTEGER,
    }

    /**
     * One configurable numeric field.
     *
     * @param field          the name the member's payload uses, and the field the form keys on
     * @param shape          whether values are decimal or whole numbers
     * @param minimum        the lower bound, as decimal text
     * @param maximum        the upper bound, as decimal text
     * @param includeMinimum whether the lower bound itself is inside the range
     * @param step           the increment the form offers
     * @param required       whether the field must carry a number, or may be left out entirely
     */
    public record Limit(
            String field,
            Shape shape,
            String minimum,
            String maximum,
            boolean includeMinimum,
            String step,
            boolean required) {

        public Limit {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            Objects.requireNonNull(step, "step");
        }

        public BigDecimal minimumValue() {
            return new BigDecimal(minimum);
        }

        public BigDecimal maximumValue() {
            return new BigDecimal(maximum);
        }

        public BigDecimal stepValue() {
            return new BigDecimal(step);
        }

        /** Whether a decimal value is inside this range. */
        public boolean accepts(BigDecimal value) {
            Objects.requireNonNull(value, field);
            boolean belowMinimum = includeMinimum
                    ? value.compareTo(minimumValue()) < 0
                    : value.compareTo(minimumValue()) <= 0;
            return !belowMinimum && value.compareTo(maximumValue()) <= 0;
        }

        /** Whether a whole number is inside this range, and shaped like a whole number at all. */
        public boolean accepts(long value) {
            return shape == Shape.INTEGER && accepts(BigDecimal.valueOf(value));
        }
    }

    // `required` restates the parameter's own type: an `Optional<...>` leaves the field to the model
    // default, while a primitive `int` always needs a number, so an emptied form field must not be
    // read as zero and sent as one.
    public static final Limit TEMPERATURE = new Limit("temperature", Shape.DECIMAL, "0", "2", true, "0.01", false);
    public static final Limit TOP_P = new Limit("topP", Shape.DECIMAL, "0", "1", false, "0.01", false);
    // The token ceiling is what a provider will accept for one response and the attempt ceiling is how
    // many times a step may be retried; both are product limits, not provider facts.
    public static final Limit MAXIMUM_OUTPUT_TOKENS =
            new Limit("maximumOutputTokens", Shape.INTEGER, "1", "10000000", true, "1", false);
    public static final Limit MAXIMUM_ATTEMPTS =
            new Limit("maximumAttempts", Shape.INTEGER, "1", "10", true, "1", true);

    /** Declaration order, which is the order the form renders and the generated copy preserves. */
    public static final List<Limit> ALL = List.of(TEMPERATURE, TOP_P, MAXIMUM_OUTPUT_TOKENS, MAXIMUM_ATTEMPTS);

    /** The limit for a field, or {@link IllegalArgumentException} if the field is not configurable. */
    public static Limit require(String field) {
        return ALL.stream()
                .filter(limit -> limit.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a configurable GenerateOptions field: " + field));
    }

    private AgentGenerateOptionsLimits() {
    }
}
