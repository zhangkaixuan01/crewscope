package io.crewscope.server.security.session;

import io.crewscope.domain.identity.UserAccount;
import io.crewscope.server.security.PlatformRoleAuthorities;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.ReactiveSessionRegistry;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ConcurrentSessionControlServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.InvalidateLeastUsedServerMaximumSessionsExceededHandler;
import org.springframework.security.web.server.authentication.RegisterSessionServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.SessionLimit;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** Establishes, rotates and invalidates Redis-backed browser Sessions without retaining secrets. */
public final class BrowserSessionLifecycle {

    private static final Pattern AUTHORITY = Pattern.compile("ROLE_[A-Z][A-Z0-9_]{0,62}");
    private static final int MAXIMUM_AUTHORITIES = 16;

    private final WebSessionServerSecurityContextRepository securityContexts;
    private final ReactiveSessionRegistry sessionRegistry;
    private final ConcurrentSessionControlServerAuthenticationSuccessHandler concurrentSessions;
    private final RegisterSessionServerAuthenticationSuccessHandler sessionRegistration;

    BrowserSessionLifecycle(
            WebSessionServerSecurityContextRepository securityContexts,
            ReactiveSessionRegistry sessionRegistry,
            ConcurrentSessionControlServerAuthenticationSuccessHandler concurrentSessions,
            RegisterSessionServerAuthenticationSuccessHandler sessionRegistration) {
        this.securityContexts = Objects.requireNonNull(securityContexts, "securityContexts");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.concurrentSessions = Objects.requireNonNull(concurrentSessions, "concurrentSessions");
        this.sessionRegistration = Objects.requireNonNull(sessionRegistration, "sessionRegistration");
    }

    /** Establishes a Session whose authorities come only from the current persisted Account. */
    public Mono<Void> establish(ServerWebExchange exchange, UserAccount account) {
        UserAccount requiredAccount = Objects.requireNonNull(account, "account");
        BrowserSessionPrincipal principal = new BrowserSessionPrincipal(
                requiredAccount.id().value(), requiredAccount.securityVersion().value());
        return establish(
                exchange, principal, PlatformRoleAuthorities.namesFor(requiredAccount));
    }

    /** Rotates the identifier, applies the Account limit, then saves one credential-free context. */
    Mono<Void> establish(
            ServerWebExchange exchange,
            BrowserSessionPrincipal principal,
            Collection<String> authorityNames) {
        ServerWebExchange requiredExchange = Objects.requireNonNull(exchange, "exchange");
        Authentication authentication = authentication(principal, authorityNames);
        WebFilterExchange filterExchange =
                new WebFilterExchange(requiredExchange, ignored -> Mono.empty());

        return requiredExchange.getSession().flatMap(session -> {
            session.start();
            return session.changeSessionId()
                    // Perform every Redis-dependent admission check before attaching authentication.
                    .then(concurrentSessions.onAuthenticationSuccess(
                            filterExchange, authentication))
                    .then(sessionRegistration.onAuthenticationSuccess(
                            filterExchange, authentication))
                    .then(securityContexts.save(
                            requiredExchange, new SecurityContextImpl(authentication)));
        });
    }

    /** Invalidates only the browser's current server-side Session. */
    public Mono<Void> invalidateCurrent(ServerWebExchange exchange) {
        return Objects.requireNonNull(exchange, "exchange")
                .getSession()
                .flatMap(WebSession::invalidate);
    }

    /** Deletes every indexed Session for one Account without exposing any Session identifier. */
    public Mono<Void> invalidateAll(java.util.UUID accountId) {
        String principalName = Objects.requireNonNull(accountId, "accountId").toString();
        return sessionRegistry
                .getAllSessions(principalName)
                .flatMap(information -> information.invalidate())
                .then();
    }

    private static Authentication authentication(
            BrowserSessionPrincipal principal, Collection<String> authorityNames) {
        BrowserSessionPrincipal requiredPrincipal =
                Objects.requireNonNull(principal, "principal");
        Collection<String> requiredAuthorities =
                Objects.requireNonNull(authorityNames, "authorityNames");
        Set<String> unique = new LinkedHashSet<>();
        for (String value : requiredAuthorities) {
            String authority = Objects.requireNonNull(value, "authorityName");
            if (!AUTHORITY.matcher(authority).matches()) {
                throw new IllegalArgumentException("authorityName has an unsupported shape");
            }
            unique.add(authority);
        }
        if (unique.isEmpty() || unique.size() > MAXIMUM_AUTHORITIES) {
            throw new IllegalArgumentException("authorityNames must contain between 1 and 16 values");
        }
        List<SimpleGrantedAuthority> authorities =
                unique.stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(
                requiredPrincipal, null, authorities);
    }

    static ConcurrentSessionControlServerAuthenticationSuccessHandler concurrentSessions(
            org.springframework.security.core.session.ReactiveSessionRegistry registry,
            org.springframework.web.server.session.WebSessionStore sessionStore,
            int maximumSessions) {
        ConcurrentSessionControlServerAuthenticationSuccessHandler handler =
                new ConcurrentSessionControlServerAuthenticationSuccessHandler(
                        registry,
                        new InvalidateLeastUsedServerMaximumSessionsExceededHandler(sessionStore));
        handler.setSessionLimit(SessionLimit.of(maximumSessions));
        return handler;
    }
}
