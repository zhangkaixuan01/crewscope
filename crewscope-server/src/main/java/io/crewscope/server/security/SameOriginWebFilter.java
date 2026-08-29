package io.crewscope.server.security;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Rejects credential-bearing browser API requests that declare a different Origin. */
public final class SameOriginWebFilter implements WebFilter {

  private static final String API_PREFIX = "/api/";
  private final ApiSecurityResponseWriter responses;

  public SameOriginWebFilter(ApiSecurityResponseWriter responses) {
    this.responses = Objects.requireNonNull(responses, "responses");
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!exchange.getRequest().getPath().value().startsWith(API_PREFIX)) {
      return chain.filter(exchange);
    }
    List<String> values = exchange.getRequest().getHeaders().get(HttpHeaders.ORIGIN);
    if (values == null || values.isEmpty()) {
      return chain.filter(exchange);
    }
    if (values.size() != 1 || !matchesRequestOrigin(values.get(0), exchange)) {
      return responses.crossOriginRejected(exchange);
    }
    return chain.filter(exchange);
  }

  private static boolean matchesRequestOrigin(String value, ServerWebExchange exchange) {
    Origin submitted = Origin.parse(value);
    if (submitted == null) {
      return false;
    }
    // Production proxy coordinates are canonicalized into the request URI by Spring's
    // ForwardedHeaderTransformer. Raw forwarding headers are never trusted a second time here.
    return submitted.equals(Origin.fromRequest(exchange.getRequest().getURI()));
  }

  private record Origin(String scheme, String host, int port) {

    private static Origin fromRequest(URI uri) {
      if (uri == null || uri.getHost() == null) {
        return null;
      }
      return create(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    private static Origin parse(String value) {
      if (value == null || value.isBlank() || "null".equals(value)) {
        return null;
      }
      try {
        URI uri = URI.create(value);
        if (uri.getRawUserInfo() != null
            || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null) {
          return null;
        }
        return create(uri.getScheme(), uri.getHost(), uri.getPort());
      } catch (IllegalArgumentException invalid) {
        return null;
      }
    }

    private static Origin create(String scheme, String host, int port) {
      if (scheme == null || host == null) {
        return null;
      }
      String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
      if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
        return null;
      }
      int normalizedPort = port < 0 ? defaultPort(normalizedScheme) : port;
      if (normalizedPort < 1 || normalizedPort > 65_535) {
        return null;
      }
      return new Origin(normalizedScheme, host.toLowerCase(Locale.ROOT), normalizedPort);
    }

    private static int defaultPort(String scheme) {
      return "https".equals(scheme) ? 443 : 80;
    }
  }
}
