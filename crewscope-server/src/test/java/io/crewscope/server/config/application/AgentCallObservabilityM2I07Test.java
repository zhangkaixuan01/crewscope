package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.crewscope.server.observability.AgentCallObservabilityMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Server-side M2-I07 evidence for safe structured logs and bounded metric dimensions. */
class AgentCallObservabilityM2I07Test {

  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String SPAN_ID = "0123456789abcdef";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Fixture fixture = Fixture.create();
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private StructuredLoggingAgentCallObservationSink sink;

  @BeforeEach
  void setUp() {
    AgentCallObservabilityMetrics metrics = new AgentCallObservabilityMetrics(registry);
    sink = new StructuredLoggingAgentCallObservationSink(metrics);
    logger = (Logger) LoggerFactory.getLogger(StructuredLoggingAgentCallObservationSink.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
    registry.close();
  }

  @Test
  void logsEveryLifecycleRecordWithCorrelationAndTraceButNoProviderContent() {
    sink.record(fixture.record(AgentCallObservationEvent.STARTED));
    sink.record(fixture.record(AgentCallObservationEvent.RETRYING));
    sink.record(fixture.record(AgentCallObservationEvent.FALLBACK_SELECTED));
    sink.record(fixture.record(AgentCallObservationEvent.COMPLETED));
    sink.record(fixture.record(AgentCallObservationEvent.FAILED));
    sink.record(fixture.record(AgentCallObservationEvent.CANCELED));

    assertThat(appender.list).hasSize(6).allMatch(event -> event.getLevel() == Level.INFO);
    assertThat(appender.list).allSatisfy(event -> {
      String fields = event.getKeyValuePairs().toString();
      assertThat(fields)
          .contains("correlationId=\"" + fixture.correlationId + "\"")
          .contains("traceId=\"" + TRACE_ID + "\"")
          .contains("conversationId=\"" + fixture.conversationId + "\"")
          .doesNotContain("provider-secret")
          .doesNotContain("prompt-content");
    });
  }

  @Test
  void metricTagsContainOnlyBoundedOutcomeRoleCodeAndTokenDimensions() {
    sink.record(fixture.record(AgentCallObservationEvent.RETRYING));
    sink.record(fixture.record(AgentCallObservationEvent.FALLBACK_SELECTED));
    sink.record(fixture.record(AgentCallObservationEvent.COMPLETED));
    sink.record(fixture.record(AgentCallObservationEvent.FAILED));
    sink.record(fixture.record(AgentCallObservationEvent.CANCELED));

    assertThat(registry.get(AgentCallObservabilityMetrics.RETRIES).counter().count()).isEqualTo(1);
    assertThat(registry.get(AgentCallObservabilityMetrics.FALLBACKS).counter().count()).isEqualTo(1);
    assertThat(registry.get(AgentCallObservabilityMetrics.CALLS).timers())
        .extracting(timer -> timer.count())
        .containsExactlyInAnyOrder(1L, 1L, 1L);
    assertThat(registry.get(AgentCallObservabilityMetrics.TOKENS).tag("type", "input")
        .counter().count()).isEqualTo(20);
    assertThat(registry.get(AgentCallObservabilityMetrics.ERRORS)
        .tag("code", "MODEL_RATE_LIMITED").counter().count()).isEqualTo(1);

    List<String> forbiddenValues = List.of(
        fixture.organizationId.toString(),
        fixture.teamId.toString(),
        fixture.workspaceId.toString(),
        fixture.conversationId.toString(),
        fixture.runtimeSessionId.toString(),
        fixture.invocationId.toString(),
        fixture.correlationId.toString(),
        TRACE_ID,
        SPAN_ID,
        "safe-model_");
    List<String> tagValues = registry.getMeters().stream()
        .map(Meter::getId)
        .flatMap(id -> id.getTags().stream())
        .map(Tag::getValue)
        .toList();
    assertThat(tagValues).doesNotContainAnyElementsOf(forbiddenValues);
    assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
        .extracting(Tag::getKey)
        .allMatch(key -> List.of("outcome", "fallback", "role", "code", "type")
            .contains(key)));
  }

  private record Fixture(
      OrganizationId organizationId,
      TeamId teamId,
      WorkspaceId workspaceId,
      ConversationId conversationId,
      AgentRuntimeSessionId runtimeSessionId,
      RuntimeInvocationId invocationId,
      UUID correlationId) {

    private static Fixture create() {
      ConversationId conversationId = ConversationId.generate();
      return new Fixture(
          OrganizationId.generate(),
          TeamId.generate(),
          WorkspaceId.generate(),
          conversationId,
          AgentRuntimeSessionId.forPersonalConversation(
              conversationId, TeamMemberId.generate(), PrincipalId.generate()),
          RuntimeInvocationId.generate(),
          UUID.randomUUID());
    }

    private AgentCallObservationRecord record(AgentCallObservationEvent event) {
      boolean completed = event == AgentCallObservationEvent.COMPLETED;
      boolean failed = event == AgentCallObservationEvent.FAILED;
      boolean fallback = event == AgentCallObservationEvent.FALLBACK_SELECTED || completed;
      return new AgentCallObservationRecord(
          Instant.parse("2026-08-09T08:00:00Z"),
          event,
          organizationId,
          teamId,
          workspaceId,
          conversationId,
          runtimeSessionId,
          invocationId,
          correlationId,
          Optional.of(TRACE_ID),
          Optional.of(SPAN_ID),
          "safe-model\n",
          event == AgentCallObservationEvent.RETRYING || failed
              ? AgentModelRole.PRIMARY
              : AgentModelRole.LOGICAL,
          event == AgentCallObservationEvent.RETRYING ? 2 : 0,
          2,
          event == AgentCallObservationEvent.STARTED ? 0 : 1,
          fallback,
          completed ? 20 : 0,
          completed ? 8 : 0,
          completed ? 5 : 0,
          completed ? 28 : 0,
          event == AgentCallObservationEvent.STARTED ? 0 : 125,
          failed ? Optional.of("MODEL_RATE_LIMITED") : Optional.empty());
    }
  }
}
