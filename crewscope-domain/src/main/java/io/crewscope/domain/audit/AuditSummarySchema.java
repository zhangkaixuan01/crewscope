package io.crewscope.domain.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact allowlist that converts one known event schema into a redacted Audit summary. */
public final class AuditSummarySchema {

    public static final int MAX_FIELDS = 24;
    public static final int MAX_VALUE_LENGTH = 500;
    public static final int MAX_TOTAL_VALUE_LENGTH = 4_000;

    private static final Pattern FIELD_FORMAT = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i).*(bearer\\s+|authorization\\s*[:=]|api[_-]?key\\s*[:=]|"
                    + "password\\s*[:=]|secret\\s*[:=]|token\\s*[:=]|"
                    + "-----BEGIN [A-Z ]*PRIVATE KEY-----|sk-[a-z0-9]{8,}).*");
    private static final Pattern EMAIL_VALUE = Pattern.compile(
            ".*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*");
    private static final Pattern PHONE_VALUE = Pattern.compile(
            ".*(?<![A-Za-z0-9])\\+?[0-9][0-9 .()_-]{6,}[0-9](?![A-Za-z0-9]).*");
    private static final Pattern URL_VALUE = Pattern.compile("(?i).*https?://.*");
    private static final Set<String> SENSITIVE_FIELD_FRAGMENTS = Set.of(
            "secret",
            "password",
            "token",
            "credential",
            "authorization",
            "cookie",
            "payload",
            "requestbody",
            "responsebody",
            "toolinput",
            "prompt",
            "endpoint",
            "email",
            "phone");

    private final EventType eventType;
    private final SchemaVersion sourceSchemaVersion;
    private final AuditEventCategory category;
    private final Set<String> requiredFields;
    private final Set<String> optionalFields;
    private final Set<String> allowedFields;

    public AuditSummarySchema(
            EventType eventType,
            SchemaVersion sourceSchemaVersion,
            AuditEventCategory category,
            Set<String> requiredFields,
            Set<String> optionalFields) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.sourceSchemaVersion = Objects.requireNonNull(
                sourceSchemaVersion, "sourceSchemaVersion");
        this.category = Objects.requireNonNull(category, "category");
        this.requiredFields = normalizeFields(requiredFields, "requiredFields");
        this.optionalFields = normalizeFields(optionalFields, "optionalFields");
        HashSet<String> overlap = new HashSet<>(this.requiredFields);
        overlap.retainAll(this.optionalFields);
        if (!overlap.isEmpty()) {
            throw new DomainValidationException(
                    "auditSummarySchema.fields",
                    "required and optional fields must be disjoint");
        }
        HashSet<String> allowed = new HashSet<>(this.requiredFields);
        allowed.addAll(this.optionalFields);
        if (allowed.size() > MAX_FIELDS) {
            throw new DomainValidationException(
                    "auditSummarySchema.fields",
                    "must contain at most " + MAX_FIELDS + " safe fields");
        }
        this.allowedFields = Set.copyOf(allowed);
    }

    /** Accepts the complete projected field set; missing and unknown fields fail closed. */
    public AuditRedactedSummary project(Map<String, String> sourceFields) {
        Map<String, String> supplied = Objects.requireNonNull(sourceFields, "sourceFields");
        if (!supplied.keySet().containsAll(requiredFields)
                || !allowedFields.containsAll(supplied.keySet())) {
            throw new DomainValidationException(
                    "auditSummary.values",
                    "must match the registered redacted field schema");
        }
        int totalLength = 0;
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            String field = requireField(entry.getKey(), "auditSummary.values");
            String value = requireSafeValue(entry.getValue());
            totalLength = Math.addExact(totalLength, value.length());
            safe.put(field, value);
        }
        if (totalLength > MAX_TOTAL_VALUE_LENGTH) {
            throw new DomainValidationException(
                    "auditSummary.values", "exceeds the total redacted summary limit");
        }
        return new AuditRedactedSummary(eventType, sourceSchemaVersion, category, safe);
    }

    public EventType eventType() {
        return eventType;
    }

    public SchemaVersion sourceSchemaVersion() {
        return sourceSchemaVersion;
    }

    public AuditEventCategory category() {
        return category;
    }

    public Set<String> requiredFields() {
        return requiredFields;
    }

    public Set<String> optionalFields() {
        return optionalFields;
    }

    private static Set<String> normalizeFields(Set<String> fields, String name) {
        HashSet<String> normalized = new HashSet<>();
        for (String field : Objects.requireNonNull(fields, name)) {
            normalized.add(requireField(field, "auditSummarySchema." + name));
        }
        return Set.copyOf(normalized);
    }

    private static String requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(name, "field name must not be blank");
        }
        String normalized = value.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!FIELD_FORMAT.matcher(normalized).matches()
                || SENSITIVE_FIELD_FRAGMENTS.stream().anyMatch(lower::contains)) {
            throw new DomainValidationException(
                    name, "field is not allowed at the Audit summary boundary");
        }
        return normalized;
    }

    private static String requireSafeValue(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(
                    "auditSummary.values", "values must not be blank");
        }
        String normalized = value.strip();
        boolean unsafeCharacter = normalized.codePoints().anyMatch(character ->
                Character.isISOControl(character) || Character.getType(character) == Character.FORMAT);
        if (normalized.length() > MAX_VALUE_LENGTH
                || unsafeCharacter
                || SECRET_VALUE.matcher(normalized).matches()
                || EMAIL_VALUE.matcher(normalized).matches()
                || PHONE_VALUE.matcher(normalized).matches()
                || URL_VALUE.matcher(normalized).matches()) {
            throw new DomainValidationException(
                    "auditSummary.values", "value is not safe for the Audit query projection");
        }
        return normalized;
    }
}
