package io.crewscope.server.security;

import io.crewscope.server.api.ApiCorrelationIds;
import io.crewscope.server.observability.ApiObservabilityContext;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes the stable API error envelope for failures raised before controller dispatch. */
public final class ApiSecurityResponseWriter {

  public Mono<Void> authenticationRequired(ServerWebExchange exchange) {
    return write(
        exchange,
        HttpStatus.UNAUTHORIZED,
        "authentication_required",
        "Authentication is required");
  }

  public Mono<Void> accessDenied(ServerWebExchange exchange) {
    return write(exchange, HttpStatus.FORBIDDEN, "access_denied", "Access is denied");
  }

  public Mono<Void> csrfRejected(ServerWebExchange exchange) {
    return write(
        exchange,
        HttpStatus.FORBIDDEN,
        "csrf_rejected",
        "The request could not be verified");
  }

  public Mono<Void> crossOriginRejected(ServerWebExchange exchange) {
    return write(
        exchange,
        HttpStatus.FORBIDDEN,
        "cross_origin_rejected",
        "Cross-origin requests are not allowed");
  }

  public Mono<Void> requestTooLarge(ServerWebExchange exchange) {
    return write(
        exchange,
        HttpStatus.PAYLOAD_TOO_LARGE,
        "request_too_large",
        "The request body exceeds the allowed size");
  }

  private static Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String code, String message) {
    if (exchange.getResponse().isCommitted()) {
      return exchange.getResponse().setComplete();
    }
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    ApiObservabilityContext.errorCode(exchange, code);
    byte[] body = String.format(
            Locale.ROOT,
            "{\"code\":\"%s\",\"message\":\"%s\",\"correlationId\":\"%s\","
                + "\"retryable\":false,\"currentVersion\":null,\"details\":{}}",
            code,
            message,
            correlationId)
        .getBytes(StandardCharsets.UTF_8);

    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    exchange.getResponse().getHeaders().set(ApiCorrelationIds.HEADER, correlationId.toString());
    // A business route never asks a browser to open the native Basic credential dialog.
    exchange.getResponse().getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
    exchange.getResponse().getHeaders().setContentLength(body.length);
    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
