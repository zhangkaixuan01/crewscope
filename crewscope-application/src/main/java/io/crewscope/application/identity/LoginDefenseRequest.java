package io.crewscope.application.identity;

import java.util.Objects;

/** Secret-free request wrapper whose resource preimages remain redacted from diagnostics. */
public record LoginDefenseRequest(
        AuthenticationFlow flow,
        LoginIdentifierResource identifier,
        ControlledNetworkResource controlledNetwork) {

    public LoginDefenseRequest {
        flow = Objects.requireNonNull(flow, "flow");
        identifier = Objects.requireNonNull(identifier, "identifier");
        controlledNetwork = Objects.requireNonNull(controlledNetwork, "controlledNetwork");
    }

    @Override
    public String toString() {
        return "LoginDefenseRequest[flow=" + flow + ", resources=REDACTED]";
    }
}
