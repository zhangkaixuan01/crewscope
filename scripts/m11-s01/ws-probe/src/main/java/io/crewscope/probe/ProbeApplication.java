package io.crewscope.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the M11-S01 WebSocket collaboration probe.
 *
 * <p>An isolated SPIKE artifact (see docs/spikes/M11-S01-实时协作与WorkGraph合同冻结.md
 * section 6): it validates the mechanism fidelity of the contracts frozen in
 * ADR-032/ADR-038 section 3.1 — handshake reusing the session security chain,
 * Redis-only presence with TTL, three-layer revocation, per-tab presence
 * dedup, bounded outbound buffers — and is not an I01/D01 product
 * implementation.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbeApplication.class, args);
    }
}
