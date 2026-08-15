package io.crewscope.server.security;

import io.crewscope.application.task.TaskTokenAuthenticator;
import io.crewscope.application.task.TaskTokenExecutionContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Bearer-only authentication middleware for internal Worker command routes. */
public final class TaskTokenWebFilter implements WebFilter {

    public static final String CONTEXT_ATTRIBUTE = TaskTokenExecutionContext.class.getName();
    private static final String ROUTE_PREFIX = "/api/internal/v1/worker/";
    private static final byte[] UNAUTHORIZED =
            "{\"code\":\"task_token_invalid\",\"message\":\"Task Token authentication required\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final TaskTokenAuthenticator authenticator;

    public TaskTokenWebFilter(TaskTokenAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(ROUTE_PREFIX)) {
            return chain.filter(exchange);
        }
        String token = bearer(exchange.getRequest().getHeaders());
        if (token == null) {
            return unauthorized(exchange);
        }
        return Mono.fromCallable(() -> authenticator.authenticate(token))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(failure -> new TaskTokenRejectedException())
                .flatMap(context -> {
                    exchange.getAttributes().put(CONTEXT_ATTRIBUTE, context);
                    return chain.filter(exchange).contextWrite(
                            ReactiveSecurityContextHolder.withAuthentication(
                                    new TaskTokenAuthentication(context)));
                })
                .onErrorResume(TaskTokenRejectedException.class, failure -> unauthorized(exchange));
    }

    private static String bearer(HttpHeaders headers) {
        List<String> values = headers.get(HttpHeaders.AUTHORIZATION);
        if (values == null || values.size() != 1) {
            return null;
        }
        String value = values.get(0);
        if (value == null || !value.startsWith("Bearer ")) {
            return null;
        }
        String token = value.substring(7);
        return token.isBlank() || token.length() > 16384 ? null : token;
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(UNAUTHORIZED);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Marker separating authentication failures from downstream application failures. */
    private static final class TaskTokenRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
