package io.crewscope.server.security.session;

import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.server.security.PlatformRoleAuthorities;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
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
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Establishes, rotates and invalidates Redis-backed browser Sessions without retaining secrets. */
public final class BrowserSessionLifecycle {

    private static final Pattern AUTHORITY = Pattern.compile("ROLE_[A-Z][A-Z0-9_]{0,62}");
    private static final int MAXIMUM_AUTHORITIES = 16;

    /**
     * Session attribute holding the derived {@code "browser · OS"} device summary (M13b-A02).
     * Absent on Sessions established before the field existed; readers treat that as unknown.
     */
    public static final String DEVICE_ATTRIBUTE = "crewscope.device";

    private final WebSessionServerSecurityContextRepository securityContexts;
    private final ReactiveSessionRegistry sessionRegistry;
    private final ConcurrentSessionControlServerAuthenticationSuccessHandler concurrentSessions;
    private final RegisterSessionServerAuthenticationSuccessHandler sessionRegistration;
    private final ReactiveSessionRepository<? extends Session> sessionRepository;

    BrowserSessionLifecycle(
            WebSessionServerSecurityContextRepository securityContexts,
            ReactiveSessionRegistry sessionRegistry,
            ConcurrentSessionControlServerAuthenticationSuccessHandler concurrentSessions,
            RegisterSessionServerAuthenticationSuccessHandler sessionRegistration,
            ReactiveSessionRepository<? extends Session> sessionRepository) {
        this.securityContexts = Objects.requireNonNull(securityContexts, "securityContexts");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.concurrentSessions = Objects.requireNonNull(concurrentSessions, "concurrentSessions");
        this.sessionRegistration = Objects.requireNonNull(sessionRegistration, "sessionRegistration");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
    }

    /** Establishes a Session whose authorities come only from the current persisted Account. */
    public Mono<Void> establish(ServerWebExchange exchange, UserAccount account) {
        return establish(exchange, account, IdentityProviderKey.local());
    }

    /** Establishes a Session credentialed by one provider; local stays the legacy default. */
    public Mono<Void> establish(
            ServerWebExchange exchange,
            UserAccount account,
            IdentityProviderKey identityProvider) {
        UserAccount requiredAccount = Objects.requireNonNull(account, "account");
        Objects.requireNonNull(identityProvider, "identityProvider");
        BrowserSessionPrincipal principal = new BrowserSessionPrincipal(
                requiredAccount.id().value(),
                requiredAccount.securityVersion().value(),
                identityProvider.toString());
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
                    // Store only the derived device summary — the raw User-Agent never persists.
                    .then(Mono.fromRunnable(() -> storeDeviceSummary(
                            session, requiredExchange.getRequest()
                                    .getHeaders().getFirst(HttpHeaders.USER_AGENT))))
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

    /** Lists every indexed Session of one Account, most recently accessed first (M12-I01). */
    public Flux<BrowserSessionSnapshot> listSessions(java.util.UUID accountId) {
        String principalName = Objects.requireNonNull(accountId, "accountId").toString();
        return sessionRegistry
                .getAllSessions(principalName)
                .flatMap(information -> sessionRepository
                        .findById(information.getSessionId())
                        .map(session -> new BrowserSessionSnapshot(
                                session.getId(),
                                session.getCreationTime(),
                                session.getLastAccessedTime(),
                                deviceSummary(session))))
                .sort(BrowserSessionSnapshot.byMostRecentAccess());
    }

    /**
     * Deletes one indexed Session of one Account. The caller's own current Session goes through
     * {@link WebSession#invalidate()} so this request's commit phase cannot write a partial
     * remnant of the just-deleted Session back into the store.
     */
    public Mono<Boolean> invalidateSession(
            ServerWebExchange exchange, java.util.UUID accountId, String sessionId) {
        String requiredSessionId = Objects.requireNonNull(sessionId, "sessionId");
        return Objects.requireNonNull(exchange, "exchange")
                .getSession()
                .flatMap(current ->
                        requiredSessionId.equals(current.getId())
                                ? current.invalidate().thenReturn(true)
                                : invalidateSession(accountId, requiredSessionId));
    }

    /**
     * Deletes one indexed Session of one Account. {@code false} means the identifier is gone or
     * belongs to another account, which callers surface as a plain not-found.
     */
    public Mono<Boolean> invalidateSession(java.util.UUID accountId, String sessionId) {
        String principalName = Objects.requireNonNull(accountId, "accountId").toString();
        String requiredSessionId = Objects.requireNonNull(sessionId, "sessionId");
        return sessionRegistry
                .getAllSessions(principalName)
                .filter(information -> information.getSessionId().equals(requiredSessionId))
                .next()
                .flatMap(information -> information.invalidate().thenReturn(true))
                .defaultIfEmpty(false);
    }

    /** Deletes every indexed Session for one Account without exposing any Session identifier. */
    public Mono<Void> invalidateAll(java.util.UUID accountId) {
        String principalName = Objects.requireNonNull(accountId, "accountId").toString();
        return sessionRegistry
                .getAllSessions(principalName)
                .flatMap(information -> information.invalidate())
                .then();
    }

    /** Derives and stores the device summary; an unrecognizable header stores nothing. */
    private static void storeDeviceSummary(WebSession session, String userAgent) {
        String summary = UserAgentDeviceSummary.summarize(userAgent);
        if (summary != null) {
            session.getAttributes().put(DEVICE_ATTRIBUTE, summary);
        }
    }

    /** Reads the stored device summary; {@code null} marks Sessions without one (M13b-A02). */
    private static String deviceSummary(Session session) {
        Object value = session.getAttribute(DEVICE_ATTRIBUTE);
        return value instanceof String summary ? summary : null;
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
