package io.crewscope.application.runtime;

/** Persistence Port for a fixed-query Runtime fleet and WAITING_RUNTIME snapshot. */
public interface RuntimeObservationRepository {

    RuntimeObservationSnapshot observe(RuntimeObservationQuery query);
}
