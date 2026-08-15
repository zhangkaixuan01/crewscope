package io.crewscope.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskTokenAuthenticator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Bearer-only route isolation and trusted Reactor context tests. */
class TaskTokenWebFilterTest {

    @Test
    void injectsOnlyTheServerVerifiedContextForWorkerRoutes() {
        TaskTokenSecurityFixture fixture = new TaskTokenSecurityFixture();
        TaskTokenAuthenticator authenticator = org.mockito.Mockito.mock(TaskTokenAuthenticator.class);
        when(authenticator.authenticate("signed-token")).thenReturn(fixture.context);
        TaskTokenWebFilter filter = new TaskTokenWebFilter(authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/internal/v1/worker/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer signed-token"));
        AtomicReference<TaskTokenAuthentication> authentication = new AtomicReference<>();
        WebFilterChain chain = ignored -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(context -> authentication.set(
                        (TaskTokenAuthentication) context.getAuthentication()))
                .then();

        filter.filter(exchange, chain).block();

        assertEquals(fixture.context, exchange.getAttribute(TaskTokenWebFilter.CONTEXT_ATTRIBUTE));
        assertNotNull(authentication.get());
        assertEquals(fixture.context, authentication.get().getPrincipal());
        assertEquals("[REDACTED]", authentication.get().getCredentials());
    }

    @Test
    void rejectsMissingBasicOrInvalidWorkerCredentialsWithoutFallback() {
        TaskTokenAuthenticator authenticator = org.mockito.Mockito.mock(TaskTokenAuthenticator.class);
        TaskTokenWebFilter filter = new TaskTokenWebFilter(authenticator);
        MockServerWebExchange missing = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/internal/v1/worker/heartbeat"));
        MockServerWebExchange basic = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/internal/v1/worker/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46YWRtaW4="));

        filter.filter(missing, ignored -> Mono.empty()).block();
        filter.filter(basic, ignored -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, missing.getResponse().getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, basic.getResponse().getStatusCode());
        verify(authenticator, never()).authenticate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void leavesMemberRoutesUntouched() {
        TaskTokenAuthenticator authenticator = org.mockito.Mockito.mock(TaskTokenAuthenticator.class);
        TaskTokenWebFilter filter = new TaskTokenWebFilter(authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/teams"));
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            called.set(true);
            return Mono.empty();
        }).block();

        assertEquals(true, called.get());
        verify(authenticator, never()).authenticate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doesNotRewriteDownstreamApplicationFailuresAsAuthenticationFailures() {
        TaskTokenSecurityFixture fixture = new TaskTokenSecurityFixture();
        TaskTokenAuthenticator authenticator = org.mockito.Mockito.mock(TaskTokenAuthenticator.class);
        when(authenticator.authenticate("signed-token")).thenReturn(fixture.context);
        TaskTokenWebFilter filter = new TaskTokenWebFilter(authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/internal/v1/worker/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer signed-token"));

        assertThrows(IllegalStateException.class, () -> filter.filter(
                exchange, ignored -> Mono.error(new IllegalStateException("downstream"))).block());
        assertEquals(null, exchange.getResponse().getStatusCode());
    }
}
