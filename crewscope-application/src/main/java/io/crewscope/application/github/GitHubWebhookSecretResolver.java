package io.crewscope.application.github;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.function.Function;

/** Resolves one inbound Webhook secret only inside a bounded verification callback. */
public interface GitHubWebhookSecretResolver {

    <T> T useSecret(
            OrganizationId organizationId,
            ConnectionId connectionId,
            Function<byte[], T> operation);
}
