package io.crewscope.application.identity;

/** Fixed non-enumerating failure used when Operator bootstrap facts cannot safely converge. */
public final class BootstrapOperatorProvisioningException extends IllegalStateException {

    public BootstrapOperatorProvisioningException() {
        super("Bootstrap Operator provisioning could not safely converge");
    }
}
