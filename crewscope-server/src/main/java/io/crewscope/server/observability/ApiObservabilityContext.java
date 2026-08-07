package io.crewscope.server.observability;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;

/** Stores safe, bounded request diagnostics for the completion filter. */
public final class ApiObservabilityContext {

    static final String ERROR_CODE_ATTRIBUTE =
            ApiObservabilityContext.class.getName() + ".errorCode";
    static final String FAILURE_TYPE_ATTRIBUTE =
            ApiObservabilityContext.class.getName() + ".failureType";

    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern FAILURE_TYPE =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$]{0,199}");

    private ApiObservabilityContext() {}

    /** Records a stable machine error code without storing rejected request values. */
    public static void errorCode(ServerWebExchange exchange, String code) {
        Objects.requireNonNull(exchange, "exchange");
        if (code != null && ERROR_CODE.matcher(code).matches()) {
            exchange.getAttributes().put(ERROR_CODE_ATTRIBUTE, code);
        }
    }

    /** Records only the exception class name for an internal failure. */
    public static void failureType(ServerWebExchange exchange, Class<?> failureType) {
        Objects.requireNonNull(exchange, "exchange");
        if (failureType != null && FAILURE_TYPE.matcher(failureType.getName()).matches()) {
            exchange.getAttributes().put(FAILURE_TYPE_ATTRIBUTE, failureType.getName());
        }
    }

    static String errorCode(ServerWebExchange exchange) {
        return exchange.getAttribute(ERROR_CODE_ATTRIBUTE);
    }

    static String failureType(ServerWebExchange exchange) {
        return exchange.getAttribute(FAILURE_TYPE_ATTRIBUTE);
    }
}
