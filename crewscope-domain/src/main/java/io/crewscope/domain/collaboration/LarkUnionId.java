package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Evidence-only Lark union_id; it is never used as a fuzzy lookup key. */
public record LarkUnionId(String value) {

    private static final Pattern FORMAT = Pattern.compile("on_[A-Za-z0-9_-]{1,120}");

    public LarkUnionId {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException("larkUnionId", "has an invalid union_id shape");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return "LarkUnionId[REDACTED]";
    }
}
