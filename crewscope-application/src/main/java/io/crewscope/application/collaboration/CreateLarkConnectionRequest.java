package io.crewscope.application.collaboration;

import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** One-way input used to create a Team-owned Lark authorization graph. */
public record CreateLarkConnectionRequest(
        TeamId teamId,
        String tenantKey,
        String appId,
        String appSecret,
        Optional<UtcTimestamp> expiresAt) {

    public CreateLarkConnectionRequest {
        teamId = Objects.requireNonNull(teamId, "teamId");
        tenantKey = text(tenantKey, "tenantKey", 200);
        appId = text(appId, "appId", 200);
        appSecret = text(appSecret, "appSecret", 1000);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static String text(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.strip();
    }
}
