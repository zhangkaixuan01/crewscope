package io.crewscope.infrastructure.workspace.repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Narrow typed Docker control surface; arbitrary Docker arguments never cross this boundary. */
interface DockerSandboxControl {

    Optional<DockerContainerSnapshot> inspect(String exactContainerName);

    /** Lists only containers carrying this deployment Organization and environment labels. */
    List<DockerContainerSnapshot> listManaged(String organizationId, String environment);

    void stop(String exactContainerName, Duration gracefulTimeout);

    void remove(String exactContainerName);

    /**
     * Idempotently ensures an isolated user-defined bridge network exists for restricted egress.
     * A network of the same name with a different driver is rejected, never reused.
     */
    void ensureBridgeNetwork(String exactNetworkName);
}
