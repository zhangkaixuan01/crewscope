package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentGenerateOptionsLimits.Limit;
import io.crewscope.domain.agent.AgentGenerateOptionsLimits.Shape;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the GenerateOptions ranges and proves the aggregate enforces exactly the table it publishes.
 *
 * <p>The second half is the point: the acceptance assertions are computed from the table and run
 * through {@link SafeModelGenerateOptions}, so the table and the validator are two code paths that
 * this test holds together. Re-hardcoding a bound in either place would leave the other behind and
 * fail here — which is the failure the Web form would otherwise only discover in production.
 */
class AgentGenerateOptionsLimitsTest {

    @Test
    void everyLimitIsAWellFormedRangeThatItsOwnShapeCanReach() {
        assertEquals(
                List.of("temperature", "topP", "maximumOutputTokens", "maximumAttempts"),
                AgentGenerateOptionsLimits.ALL.stream().map(Limit::field).toList());

        for (Limit limit : AgentGenerateOptionsLimits.ALL) {
            BigDecimal minimum = limit.minimumValue();
            BigDecimal maximum = limit.maximumValue();
            BigDecimal step = limit.stepValue();
            String name = limit.field();

            assertTrue(step.signum() > 0, name + ": step must advance the value");
            assertTrue(minimum.compareTo(maximum) < 0, name + ": minimum must be below maximum");
            // A step that overshoots the range would leave the form unable to offer a second value.
            assertTrue(
                    minimum.add(step).compareTo(maximum) <= 0,
                    name + ": one step from the minimum must stay inside the range");
            if (limit.shape() == Shape.INTEGER) {
                // A whole-number field whose bound or step has a fraction cannot be offered by a
                // number input without the browser rounding it, which would publish a range the
                // server does not have.
                assertTrue(isWhole(limit.minimum()), name + ": a whole-number field must not publish a fractional bound");
                assertTrue(isWhole(limit.maximum()), name + ": a whole-number field must not publish a fractional bound");
                assertTrue(isWhole(limit.step()), name + ": a whole-number field must not publish a fractional step");
            }
            assertEquals(limit.shape() == Shape.INTEGER ? "1" : "0.01", limit.step(),
                    name + ": the shape decides the increment");
        }

        assertThrows(IllegalArgumentException.class, () -> AgentGenerateOptionsLimits.require("maxTokens"));
        assertEquals(AgentGenerateOptionsLimits.TOP_P, AgentGenerateOptionsLimits.require("topP"));
    }

    /**
     * Whether the published text denotes a whole number. Written on the text rather than on the parsed
     * value because the text is what the form prints and what a member re-types.
     */
    private static boolean isWhole(String bound) {
        return new BigDecimal(bound).stripTrailingZeros().scale() <= 0;
    }

    @Test
    void theAggregateAcceptsExactlyTheValuesThePublishedRangeAccepts() {
        for (Limit limit : AgentGenerateOptionsLimits.ALL) {
            BigDecimal minimum = limit.minimumValue();
            BigDecimal maximum = limit.maximumValue();
            BigDecimal step = limit.stepValue();
            String name = limit.field();

            // The lower bound is inside the range only when the table says so; the open case has to
            // start one step up, which is what the form's own min attribute offers.
            assertEquals(limit.includeMinimum(), enforced(limit, minimum), name + ": lower bound");
            assertTrue(enforced(limit, minimum.add(step)), name + ": one step inside the lower bound");
            assertTrue(enforced(limit, maximum), name + ": upper bound");
            assertFalse(enforced(limit, maximum.add(step)), name + ": one step above the upper bound");
            assertFalse(enforced(limit, minimum.subtract(step)), name + ": one step below the lower bound");
            assertFalse(enforced(limit, maximum.add(maximum)), name + ": far above the upper bound");
        }
    }

    @Test
    void theTableDescribesTheParameterTheAggregateActuallyDeclares() {
        for (Limit limit : AgentGenerateOptionsLimits.ALL) {
            String type = Arrays.stream(SafeModelGenerateOptions.class.getRecordComponents())
                    .filter(candidate -> candidate.getName().equals(limit.field()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no such component: " + limit.field()))
                    .getGenericType()
                    .getTypeName();

            // Shape and optionality are facts about the parameter's own type, so reading them back off
            // the signature is what keeps the published table honest about the aggregate it describes.
            if (limit.shape() == Shape.DECIMAL) {
                assertTrue(type.contains("BigDecimal"), limit.field() + ": a decimal limit needs a BigDecimal");
            } else {
                assertTrue(
                        type.contains("Long") || type.equals("int"),
                        limit.field() + ": a whole-number limit needs a long or an int, not " + type);
            }
            assertEquals(
                    !type.startsWith("java.util.Optional"),
                    limit.required(),
                    limit.field() + ": an Optional parameter may be left out; a primitive one may not");
        }
    }

    @Test
    void theShapeOfALimitDecidesWhatKindOfValueItTakes() {
        // A decimal limit must not be reachable by the whole-number path, or the form would accept a
        // value the aggregate reads as a different field's shape.
        assertFalse(AgentGenerateOptionsLimits.TEMPERATURE.accepts(1L));
        assertFalse(AgentGenerateOptionsLimits.TOP_P.accepts(1L));
        assertTrue(AgentGenerateOptionsLimits.MAXIMUM_ATTEMPTS.accepts(10L));
        assertFalse(AgentGenerateOptionsLimits.MAXIMUM_ATTEMPTS.accepts(11L));
        assertTrue(AgentGenerateOptionsLimits.MAXIMUM_OUTPUT_TOKENS.accepts(10_000_000L));
        assertFalse(AgentGenerateOptionsLimits.MAXIMUM_OUTPUT_TOKENS.accepts(10_000_001L));
    }

    @Test
    void rejectionNamesTheFieldAndBothBoundsItPublishes() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> withTemperature(new BigDecimal("2.5")));

        String message = failure.getMessage();
        assertTrue(message.contains("agentConfiguration.generateOptions.temperature"), message);
        assertTrue(message.contains(AgentGenerateOptionsLimits.TEMPERATURE.minimum()), message);
        assertTrue(message.contains(AgentGenerateOptionsLimits.TEMPERATURE.maximum()), message);

        DomainValidationException tokens = assertThrows(
                DomainValidationException.class,
                () -> withTokens(10_000_001L));
        assertTrue(tokens.getMessage().contains("agentConfiguration.generateOptions.maximumOutputTokens"));
        assertTrue(tokens.getMessage().contains(AgentGenerateOptionsLimits.MAXIMUM_OUTPUT_TOKENS.maximum()));
    }

    @Test
    void anUnsetFieldIsNotARangeViolation() {
        // Every field is optional: an absent value means "use the model default", not "zero".
        SafeModelGenerateOptions defaults = SafeModelGenerateOptions.defaults();
        assertTrue(defaults.temperature().isEmpty());
        assertTrue(defaults.topP().isEmpty());
        assertTrue(defaults.maximumOutputTokens().isEmpty());
        assertEquals(1, defaults.maximumAttempts());
    }

    /** Runs a value through the real aggregate and reports whether the domain let it in. */
    private static boolean enforced(Limit limit, BigDecimal value) {
        try {
            BigDecimal stepped = value.stripTrailingZeros();
            switch (limit.field()) {
                case "temperature" -> withTemperature(stepped);
                case "topP" -> withTopP(stepped);
                case "maximumOutputTokens" -> withTokens(stepped.longValueExact());
                case "maximumAttempts" -> withAttempts(stepped.intValueExact());
                default -> throw new IllegalArgumentException("not a configurable field: " + limit.field());
            }
            return true;
        } catch (DomainValidationException rejected) {
            return false;
        } catch (ArithmeticException notWhole) {
            // A fractional value cannot reach a whole-number field at all; the form refuses it the
            // same way, so it is out of range rather than an error in this test.
            return false;
        }
    }

    private static SafeModelGenerateOptions withTemperature(BigDecimal value) {
        return new SafeModelGenerateOptions(
                Optional.of(value),
                Optional.empty(),
                Optional.empty(),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.empty(),
                1);
    }

    private static SafeModelGenerateOptions withTopP(BigDecimal value) {
        return new SafeModelGenerateOptions(
                Optional.empty(),
                Optional.of(value),
                Optional.empty(),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.empty(),
                1);
    }

    private static SafeModelGenerateOptions withTokens(long value) {
        return new SafeModelGenerateOptions(
                Optional.empty(),
                Optional.empty(),
                Optional.of(value),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.empty(),
                1);
    }

    private static SafeModelGenerateOptions withAttempts(int value) {
        return new SafeModelGenerateOptions(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.empty(),
                value);
    }
}
