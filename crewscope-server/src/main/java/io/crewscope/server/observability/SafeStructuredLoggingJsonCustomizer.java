package io.crewscope.server.observability;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Applies the shared Secret/PII filter to every String member emitted by structured logging. */
public final class SafeStructuredLoggingJsonCustomizer
        implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor((path, value) -> {
            if (!(value instanceof String text)) {
                return value;
            }
            return StructuredLogSanitizer.sanitize(path.name(), text);
        });
    }
}
