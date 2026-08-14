package io.crewscope.domain.runtime;

/** Explicit Worker lifecycle; heartbeat expiry is derived and never overwrites this fact. */
public enum RuntimeWorkerStatus {
    REGISTERED,
    ACTIVE,
    DRAINING,
    DISABLED
}
