package io.crewscope.agentscope.model;

/** Stable, non-sensitive failure raised at the trusted dynamic model boundary. */
public final class AgentScopeModelBuildException extends RuntimeException {

    public enum Code {
        UNKNOWN_ADAPTER,
        CREDENTIAL_COORDINATE_MISMATCH,
        UNSUPPORTED_FORMATTER_POLICY,
        INVALID_TRUSTED_REQUEST,
        CREDENTIAL_UNAVAILABLE
    }

    private final Code code;

    public AgentScopeModelBuildException(Code code) {
        super(message(code));
        this.code = code;
    }

    public Code code() {
        return code;
    }

    private static String message(Code code) {
        return switch (code) {
            case UNKNOWN_ADAPTER -> "No trusted model adapter is registered";
            case CREDENTIAL_COORDINATE_MISMATCH ->
                    "Provider credential does not match the trusted model coordinate";
            case UNSUPPORTED_FORMATTER_POLICY ->
                    "The trusted formatter policy is unsupported by this adapter";
            case INVALID_TRUSTED_REQUEST -> "The trusted model build request is invalid";
            case CREDENTIAL_UNAVAILABLE -> "Provider credential is unavailable";
        };
    }
}
