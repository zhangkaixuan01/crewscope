package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Exact Lark open_id accepted by member lookup and message delivery. */
public record LarkOpenId(String value) {

    private static final Pattern FORMAT = Pattern.compile("ou_[A-Za-z0-9_-]{1,120}");

    public LarkOpenId {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException("larkOpenId", "has an invalid open_id shape");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return "LarkOpenId[REDACTED]";
    }
}
