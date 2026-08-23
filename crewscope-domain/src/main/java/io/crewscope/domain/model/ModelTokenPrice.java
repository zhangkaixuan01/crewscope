package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/** Normalized input, output and optional cached-input prices per one million tokens. */
public record ModelTokenPrice(
        BigDecimal inputPerMillionTokens,
        BigDecimal outputPerMillionTokens,
        Optional<BigDecimal> cachedInputPerMillionTokens,
        String currencyCode) {

    public static final int MAX_PRECISION = 24;
    public static final int MAX_SCALE = 12;

    public ModelTokenPrice {
        inputPerMillionTokens = normalizeAmount(
                inputPerMillionTokens, "modelPrice.inputPerMillionTokens");
        outputPerMillionTokens = normalizeAmount(
                outputPerMillionTokens, "modelPrice.outputPerMillionTokens");
        cachedInputPerMillionTokens = Objects.requireNonNull(
                        cachedInputPerMillionTokens, "cachedInputPerMillionTokens")
                .map(value -> normalizeAmount(value, "modelPrice.cachedInputPerMillionTokens"));
        currencyCode = requireCurrency(currencyCode);
    }

    private static BigDecimal normalizeAmount(BigDecimal value, String field) {
        BigDecimal required = Objects.requireNonNull(value, field).stripTrailingZeros();
        if (required.scale() < 0) {
            required = required.setScale(0);
        }
        if (required.signum() < 0
                || required.precision() > MAX_PRECISION
                || required.scale() > MAX_SCALE) {
            throw new DomainValidationException(
                    field,
                    "must be non-negative with at most 24 digits and 12 decimal places");
        }
        return required;
    }

    private static String requireCurrency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new DomainValidationException(
                    "modelPrice.currencyCode", "must be an ISO 4217 currency code");
        }
        try {
            Currency.getInstance(value);
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new DomainValidationException(
                    "modelPrice.currencyCode", "must be an ISO 4217 currency code");
        }
    }

    String canonicalValue() {
        return inputPerMillionTokens.toPlainString()
                + ":"
                + outputPerMillionTokens.toPlainString()
                + ":"
                + cachedInputPerMillionTokens.map(BigDecimal::toPlainString).orElse("none")
                + ":"
                + currencyCode;
    }
}
