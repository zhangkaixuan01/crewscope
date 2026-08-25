package io.crewscope.domain.activity;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit whitelist for fields allowed to cross the public Activity boundary. */
public final class ActivityPayloadSchema {

    public static final int MAX_FIELDS = 32;
    public static final int MAX_VALUE_LENGTH = 1_000;
    public static final int MAX_TOTAL_VALUE_LENGTH = 16_000;
    private static final Pattern FIELD_FORMAT = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "secret",
            "password",
            "token",
            "credential",
            "authorization",
            "cookie",
            "rawpayload",
            "requestbody",
            "responsebody",
            "toolinput",
            "systemprompt");

    private final ActivityPayloadSchemaRef reference;
    private final Set<String> requiredFields;
    private final Set<String> optionalFields;
    private final Set<String> allowedFields;

    public ActivityPayloadSchema(
            String name,
            SchemaVersion version,
            Set<String> requiredFields,
            Set<String> optionalFields) {
        this.reference = new ActivityPayloadSchemaRef(name, version);
        this.requiredFields = normalizeFields(requiredFields, "requiredFields");
        this.optionalFields = normalizeFields(optionalFields, "optionalFields");
        HashSet<String> overlap = new HashSet<>(this.requiredFields);
        overlap.retainAll(this.optionalFields);
        if (!overlap.isEmpty()) {
            throw new DomainValidationException(
                    "activityPayloadSchema.fields", "required and optional fields must be disjoint");
        }
        HashSet<String> allowed = new HashSet<>(this.requiredFields);
        allowed.addAll(this.optionalFields);
        if (allowed.size() > MAX_FIELDS) {
            throw new DomainValidationException(
                    "activityPayloadSchema.fields", "field count exceeds the public schema limit");
        }
        this.allowedFields = Set.copyOf(allowed);
    }

    public ActivityPayloadSchemaRef reference() {
        return reference;
    }

    public Set<String> requiredFields() {
        return requiredFields;
    }

    public Set<String> optionalFields() {
        return optionalFields;
    }

    public Set<String> allowedFields() {
        return allowedFields;
    }

    /** Validates and freezes values before they enter an Activity projection row. */
    public ActivityPublicPayload createPayload(Map<String, String> values) {
        Map<String, String> suppliedValues = Objects.requireNonNull(values, "values");
        if (!suppliedValues.keySet().containsAll(requiredFields)) {
            throw new DomainValidationException(
                    "activityPayload.values", "required public payload fields are missing");
        }
        if (!allowedFields.containsAll(suppliedValues.keySet())) {
            throw new DomainValidationException(
                    "activityPayload.values", "payload contains a field outside the public schema");
        }
        int totalLength = 0;
        Map<String, String> normalizedValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : suppliedValues.entrySet()) {
            String field = requireField(entry.getKey(), "activityPayload.values");
            String value = requireSafeValue(entry.getValue());
            totalLength = Math.addExact(totalLength, value.length());
            normalizedValues.put(field, value);
        }
        if (totalLength > MAX_TOTAL_VALUE_LENGTH) {
            throw new DomainValidationException(
                    "activityPayload.values", "public payload exceeds the total length limit");
        }
        return new ActivityPublicPayload(reference, normalizedValues);
    }

    /**
     * Checks additive compatibility. Existing required fields stay required and existing optional
     * fields cannot become required, so historical rows remain valid under a newer reader.
     */
    public boolean isCompatibleSuccessorOf(ActivityPayloadSchema previous) {
        ActivityPayloadSchema required = Objects.requireNonNull(previous, "previous");
        return reference.name().equals(required.reference.name())
                && reference.version().compareTo(required.reference.version()) > 0
                && allowedFields.containsAll(required.allowedFields)
                && requiredFields.equals(required.requiredFields);
    }

    private static Set<String> normalizeFields(Set<String> fields, String name) {
        Set<String> required = Objects.requireNonNull(fields, name);
        HashSet<String> normalized = new HashSet<>();
        for (String field : required) {
            normalized.add(requireField(field, "activityPayloadSchema." + name));
        }
        return Set.copyOf(normalized);
    }

    private static String requireField(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(fieldName, "field name must not be blank");
        }
        String normalized = value.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!FIELD_FORMAT.matcher(normalized).matches()
                || SENSITIVE_FRAGMENTS.stream().anyMatch(lower::contains)) {
            throw new DomainValidationException(
                    fieldName, "field name is not allowed at the public Activity boundary");
        }
        return normalized;
    }

    private static String requireSafeValue(String value) {
        if (value == null) {
            throw new DomainValidationException(
                    "activityPayload.values", "public payload values must not be null");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_VALUE_LENGTH) {
            throw new DomainValidationException(
                    "activityPayload.values", "public payload value exceeds the length limit");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= normalized.length()
                        || !Character.isLowSurrogate(normalized.charAt(index + 1))) {
                    throw new DomainValidationException(
                            "activityPayload.values", "public payload value contains unsafe characters");
                }
                index++;
                continue;
            }
            if (Character.isISOControl(character)
                    || Character.isLowSurrogate(character)
                    || character == '\u061c'
                    || character == '\u200e'
                    || character == '\u200f'
                    || character >= '\u202a' && character <= '\u202e'
                    || character >= '\u2066' && character <= '\u2069') {
                throw new DomainValidationException(
                        "activityPayload.values", "public payload value contains unsafe characters");
            }
        }
        return normalized;
    }
}
