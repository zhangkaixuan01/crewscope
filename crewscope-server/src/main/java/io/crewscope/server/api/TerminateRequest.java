package io.crewscope.server.api;

/** Shared shape of the cancel and fail rebuild bodies consumed by the terminate path. */
interface TerminateRequest {
    Long expectedGenerationVersion();

    Long expectedJobVersion();

    String confirmation();
}
