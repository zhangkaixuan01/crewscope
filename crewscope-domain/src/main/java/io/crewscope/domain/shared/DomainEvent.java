package io.crewscope.domain.shared;

/**
 * Marker for an immutable business fact carried by a {@code DomainEventEnvelope}.
 *
 * <p>Identity, type, schema version, actor and correlation metadata belong to the envelope so an
 * event payload contains business data only and can evolve independently.
 */
public interface DomainEvent {}
