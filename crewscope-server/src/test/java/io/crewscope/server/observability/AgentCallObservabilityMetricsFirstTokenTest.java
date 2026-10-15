package io.crewscope.server.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.agentscope.AgentCallObservationEvent;
import io.crewscope.agentscope.AgentCallObservationRecord;
import io.crewscope.agentscope.AgentModelRole;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.team.TeamMemberId;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M12-I03 evidence for the two latency probes: FIRST_CHUNK and APPLICATION_OVERHEAD are
 * non-terminal events that must record ahead of the terminal gate, each into its own
 * histogram-carrying timer so a process-lifetime P95 exists without an external collector.
 * The buckets are asserted through the Prometheus exposition because the simple registry's
 * step snapshots do not expose fresh histogram counts synchronously.
 */
class AgentCallObservabilityMetricsFirstTokenTest {

    @Test
    void firstChunkAndApplicationOverheadRecordIntoHistogramCarryingTimers() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(key -> null);
        AgentCallObservabilityMetrics metrics = new AgentCallObservabilityMetrics(registry);

        metrics.record(record(AgentCallObservationEvent.FIRST_CHUNK, 320));
        metrics.record(record(AgentCallObservationEvent.FIRST_CHUNK, 640));
        metrics.record(record(AgentCallObservationEvent.APPLICATION_OVERHEAD, 120));

        assertThat(registry.get(AgentCallObservabilityMetrics.FIRST_TOKEN).timer().count())
                .isEqualTo(2);
        assertThat(registry.get(AgentCallObservabilityMetrics.APP_OVERHEAD).timer().count())
                .isEqualTo(1);
        assertThat(registry.get(AgentCallObservabilityMetrics.FIRST_TOKEN).timer().getId()
                        .getTags())
                .as("the probe stays deployment-scoped with no series labels")
                .isEmpty();

        String scrape = registry.scrape();
        assertThat(scrape)
                .as("the model-side first-token timer keeps percentile histogram buckets")
                .contains("crewscope_agent_model_first_token_seconds_bucket");
        assertThat(scrape)
                .as("the application-overhead timer keeps percentile histogram buckets")
                .contains("crewscope_agent_model_app_overhead_seconds_bucket");
        assertThat(scrape)
                .as("a terminal-only metric keeps no buckets of its own")
                .doesNotContain("crewscope_agent_model_retries_bucket");
    }

    private static AgentCallObservationRecord record(
            AgentCallObservationEvent event, long latencyMillis) {
        ConversationId conversationId = ConversationId.generate();
        return new AgentCallObservationRecord(
                Instant.parse("2026-10-15T08:00:00Z"),
                event,
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                conversationId,
                AgentRuntimeSessionId.forPersonalConversation(
                        conversationId, TeamMemberId.generate(), PrincipalId.generate()),
                RuntimeInvocationId.generate(),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.empty(),
                "safe-model",
                AgentModelRole.LOGICAL,
                1,
                1,
                0,
                false,
                0,
                0,
                0,
                0,
                latencyMillis,
                Optional.empty());
    }
}
