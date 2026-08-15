package io.crewscope.application.execution;

import io.crewscope.domain.runtime.RuntimeCapabilities;

/** Common stable identity and routable capability snapshot for an execution Runtime Port. */
public interface ExecutionRuntimeProfile {

    RuntimeDescriptor descriptor();

    RuntimeCapabilities capabilities();
}
