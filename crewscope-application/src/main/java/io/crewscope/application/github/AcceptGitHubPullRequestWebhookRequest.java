package io.crewscope.application.github;

import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Arrays;
import java.util.Objects;

/** Authenticated-route coordinates and bounded raw payload for one Pull Request Webhook. */
public record AcceptGitHubPullRequestWebhookRequest(
        OrganizationId organizationId,
        PlannedActionId actionId,
        ActionDigest actionDigest,
        ExternalResultIdentity expectedIdentity,
        ExternalRepositoryId expectedRepositoryId,
        String deliveryId,
        String eventName,
        String signature,
        byte[] payload,
        UtcTimestamp receivedAt) {

    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    public AcceptGitHubPullRequestWebhookRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        actionId = Objects.requireNonNull(actionId, "actionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        expectedIdentity = Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        expectedRepositoryId = Objects.requireNonNull(
                expectedRepositoryId, "expectedRepositoryId");
        if (expectedIdentity.objectType() != ExternalObjectType.PULL_REQUEST) {
            throw new IllegalArgumentException("GitHub Webhook identity must be a Pull Request");
        }
        deliveryId = requireText(deliveryId, "deliveryId", 500);
        eventName = requireText(eventName, "eventName", 100);
        signature = requireText(signature, "signature", 100);
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("GitHub Webhook payload size is invalid");
        }
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static String requireText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.strip().length() > maximum
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("GitHub Webhook " + field + " is invalid");
        }
        return value.strip();
    }
}
