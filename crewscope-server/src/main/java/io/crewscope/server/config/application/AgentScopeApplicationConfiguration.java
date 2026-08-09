package io.crewscope.server.config.application;

import io.crewscope.agentscope.AgentCallObservationSink;
import io.crewscope.agentscope.AgentCallTraceContext;
import io.crewscope.agentscope.AgentCallTraceContextProvider;
import io.crewscope.agentscope.AgentExecutionAuditSink;
import io.crewscope.agentscope.AgentStatePreflightMiddleware;
import io.crewscope.agentscope.PlatformAgentMiddlewareSet;
import io.crewscope.agentscope.PlatformAuditMiddleware;
import io.crewscope.agentscope.PlatformRuntimeContextMiddleware;
import io.crewscope.agentscope.ProviderBindingSecurityMiddleware;
import io.crewscope.agentscope.agui.ControlledAguiBridge;
import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.execution.PlatformExecutionContextResolver;
import io.crewscope.application.execution.AgentStatePreflight;
import io.crewscope.application.execution.ConversationExecutionEventMapper;
import io.crewscope.application.execution.RealtimeDomainEventProjector;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.observability.AgentCallObservabilityMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Validator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires trusted execution-context resolution and ordered AgentScope security Middleware. */
@Configuration(proxyBeanMethods = false)
public class AgentScopeApplicationConfiguration {

  @Bean
  ConversationVisibilityPolicy conversationVisibilityPolicy() {
    return new ConversationVisibilityPolicy();
  }

  @Bean
  ConversationExecutionEventMapper conversationExecutionEventMapper(Validator validator) {
    return new ConversationExecutionEventMapper(validator);
  }

  @Bean
  RealtimeDomainEventProjector realtimeDomainEventProjector() {
    return new RealtimeDomainEventProjector();
  }

  @Bean
  ControlledAguiBridge controlledAguiBridge() {
    return new ControlledAguiBridge();
  }

  @Bean
  PlatformExecutionContextResolver platformExecutionContextResolver(
      AgentRuntimeSessionRepository runtimeSessionRepository,
      ConversationRepository conversationRepository,
      ConversationParticipantRepository participantRepository,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMemberRepository teamMemberRepository,
      PrincipalRepository principalRepository,
      AgentProfileRepository agentProfileRepository,
      MemberRoleRepository memberRoleRepository,
      TeamRoleRepository teamRoleRepository,
      ProviderBindingResolver providerBindingResolver,
      TimeProvider timeProvider,
      ConversationVisibilityPolicy visibilityPolicy) {
    return new PlatformExecutionContextResolver(
        runtimeSessionRepository,
        conversationRepository,
        participantRepository,
        teamRepository,
        workspaceRepository,
        teamMemberRepository,
        principalRepository,
        agentProfileRepository,
        memberRoleRepository,
        teamRoleRepository,
        providerBindingResolver,
        timeProvider,
        visibilityPolicy);
  }

  @Bean
  AgentExecutionAuditSink agentExecutionAuditSink() {
    return new StructuredLoggingAgentExecutionAuditSink();
  }

  @Bean
  AgentCallObservabilityMetrics agentCallObservabilityMetrics(MeterRegistry meterRegistry) {
    return new AgentCallObservabilityMetrics(meterRegistry);
  }

  @Bean
  AgentCallObservationSink agentCallObservationSink(AgentCallObservabilityMetrics metrics) {
    return new StructuredLoggingAgentCallObservationSink(metrics);
  }

  @Bean
  AgentCallTraceContextProvider agentCallTraceContextProvider(Tracer tracer) {
    return () -> {
      Span current = tracer.currentSpan();
      if (current == null || current.isNoop()) {
        return AgentCallTraceContext.empty();
      }
      return new AgentCallTraceContext(
          java.util.Optional.of(current.context().traceId()),
          java.util.Optional.of(current.context().spanId()));
    };
  }

  @Bean
  PlatformRuntimeContextMiddleware platformRuntimeContextMiddleware() {
    return new PlatformRuntimeContextMiddleware();
  }

  @Bean
  ProviderBindingSecurityMiddleware providerBindingSecurityMiddleware() {
    return new ProviderBindingSecurityMiddleware();
  }

  @Bean
  PlatformAuditMiddleware platformAuditMiddleware(
      AgentExecutionAuditSink auditSink,
      AgentCallObservationSink observationSink,
      AgentCallTraceContextProvider traceContextProvider) {
    return new PlatformAuditMiddleware(
        auditSink, observationSink, traceContextProvider, Clock.systemUTC());
  }

  @Bean
  AgentStatePreflightMiddleware agentStatePreflightMiddleware(
      AgentStatePreflight statePreflight) {
    return new AgentStatePreflightMiddleware(statePreflight);
  }

  @Bean
  PlatformAgentMiddlewareSet platformAgentMiddlewareSet(
      PlatformRuntimeContextMiddleware runtimeContextMiddleware,
      ProviderBindingSecurityMiddleware providerBindingSecurityMiddleware,
      PlatformAuditMiddleware auditMiddleware,
      AgentStatePreflightMiddleware statePreflightMiddleware) {
    return new PlatformAgentMiddlewareSet(
        runtimeContextMiddleware,
        providerBindingSecurityMiddleware,
        auditMiddleware,
        statePreflightMiddleware);
  }
}
