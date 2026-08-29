package io.crewscope.server.security;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Enforces small route-specific body budgets before authentication DTO aggregation. */
public final class AuthenticationRequestBodyLimitWebFilter implements WebFilter {

  static final long LOGIN_BYTES = 8 * 1024;
  static final long REGISTRATION_BYTES = 16 * 1024;
  static final long INVITATION_BYTES = 8 * 1024;

  private final ApiSecurityResponseWriter responses;

  public AuthenticationRequestBodyLimitWebFilter(ApiSecurityResponseWriter responses) {
    this.responses = Objects.requireNonNull(responses, "responses");
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    long limit = limit(exchange);
    if (limit < 0) {
      return chain.filter(exchange);
    }
    long contentLength = exchange.getRequest().getHeaders().getContentLength();
    if (contentLength > limit) {
      return responses.requestTooLarge(exchange);
    }

    ServerHttpRequestDecorator limited = new ServerHttpRequestDecorator(exchange.getRequest()) {
      @Override
      public Flux<DataBuffer> getBody() {
        return Flux.defer(() -> {
          AtomicLong observed = new AtomicLong();
          return super.getBody().handle((buffer, sink) -> {
            if (observed.addAndGet(buffer.readableByteCount()) > limit) {
              DataBufferUtils.release(buffer);
              sink.error(new RequestBodyTooLargeException());
            } else {
              sink.next(buffer);
            }
          });
        });
      }
    };
    return chain.filter(exchange.mutate().request(limited).build())
        .onErrorResume(RequestBodyTooLargeException.class,
            failure -> responses.requestTooLarge(exchange));
  }

  private static long limit(ServerWebExchange exchange) {
    if (exchange.getRequest().getMethod() != HttpMethod.POST) {
      return -1;
    }
    String path = exchange.getRequest().getPath().value();
    if ("/api/v1/auth/login".equals(path)) {
      return LOGIN_BYTES;
    }
    if ("/api/v1/auth/register".equals(path)) {
      return REGISTRATION_BYTES;
    }
    if (path.startsWith("/api/v1/")
        && (path.contains("/invitations/") || path.endsWith("/invitations"))) {
      return INVITATION_BYTES;
    }
    return -1;
  }

  private static final class RequestBodyTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
