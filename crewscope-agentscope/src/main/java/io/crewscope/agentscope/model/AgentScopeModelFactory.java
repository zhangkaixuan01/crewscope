package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import io.crewscope.agentscope.AgentModelRole;
import io.crewscope.agentscope.ObservableAgentScopeModel;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds and briefly caches trusted connection-scoped AgentScope models. */
public final class AgentScopeModelFactory {

    private final AgentScopeModelAdapterRegistry registry;
    private final Duration cacheTimeToLive;
    private final int maximumCacheEntries;
    private final Duration requestTimeout;
    private final Duration retryInitialBackoff;
    private final Duration retryMaximumBackoff;
    private final Clock clock;
    private final Object cacheLock = new Object();
    private final Map<AgentScopeModelCacheKey, CacheEntry> cache =
            new LinkedHashMap<>(16, 0.75f, true);

    public AgentScopeModelFactory(
            AgentScopeModelAdapterRegistry registry,
            Duration cacheTimeToLive,
            int maximumCacheEntries) {
        this(
                registry,
                cacheTimeToLive,
                maximumCacheEntries,
                Duration.ofMinutes(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30));
    }

    public AgentScopeModelFactory(
            AgentScopeModelAdapterRegistry registry,
            Duration cacheTimeToLive,
            int maximumCacheEntries,
            Duration requestTimeout,
            Duration retryInitialBackoff,
            Duration retryMaximumBackoff) {
        this(
                registry,
                cacheTimeToLive,
                maximumCacheEntries,
                requestTimeout,
                retryInitialBackoff,
                retryMaximumBackoff,
                Clock.systemUTC());
    }

    AgentScopeModelFactory(
            AgentScopeModelAdapterRegistry registry,
            Duration cacheTimeToLive,
            int maximumCacheEntries,
            Duration requestTimeout,
            Duration retryInitialBackoff,
            Duration retryMaximumBackoff,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cacheTimeToLive = requirePositive(cacheTimeToLive, "cacheTimeToLive");
        if (maximumCacheEntries < 1) {
            throw new IllegalArgumentException("maximumCacheEntries must be positive");
        }
        this.maximumCacheEntries = maximumCacheEntries;
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.retryInitialBackoff = requirePositive(retryInitialBackoff, "retryInitialBackoff");
        this.retryMaximumBackoff = requirePositive(retryMaximumBackoff, "retryMaximumBackoff");
        if (this.retryMaximumBackoff.compareTo(this.retryInitialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "retryMaximumBackoff must be greater than or equal to retryInitialBackoff");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Consumes and always closes the credential capability in the synchronous build window. */
    public Model build(
            TrustedModelBuildRequest request, ProviderCredentialHandle credentialHandle) {
        TrustedModelBuildRequest trusted = Objects.requireNonNull(request, "request");
        ProviderCredentialHandle handle = Objects.requireNonNull(credentialHandle, "credentialHandle");
        requireCredentialCoordinate(trusted, handle);
        try (handle) {
            AgentScopeModelProviderAdapter adapter = registry.require(trusted.adapterKey());
            AgentScopeModelCacheKey key = cacheKey(trusted, adapter);
            Instant now = clock.instant();
            CacheEntry existing = cached(key, now);
            if (existing != null) {
                // Resolving once more makes revocation and rotation effective even on a cache hit.
                return handle.useSecret(ignored -> existing.model());
            }
            Model created = adapter.build(trusted, handle);
            var safeOptions = SafeAgentScopeGenerateOptionsMapper.map(
                    trusted.generateOptions(),
                    requestTimeout,
                    retryInitialBackoff,
                    retryMaximumBackoff);
            Model observed = new ObservableAgentScopeModel(
                    created,
                    AgentModelRole.valueOf(trusted.role().name()),
                    safeOptions.getExecutionConfig());
            Model bound = new ConnectionBoundAgentScopeModel(observed, safeOptions);
            synchronized (cacheLock) {
                evictExpired(now);
                CacheEntry raced = cache.get(key);
                if (raced != null) {
                    return raced.model();
                }
                cache.put(key, new CacheEntry(bound, now.plus(cacheTimeToLive)));
                evictOverflow();
                return bound;
            }
        } catch (AgentScopeModelBuildException failure) {
            throw failure;
        } catch (IllegalStateException failure) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.CREDENTIAL_UNAVAILABLE);
        }
    }

    public int cacheSize() {
        synchronized (cacheLock) {
            evictExpired(clock.instant());
            return cache.size();
        }
    }

    private CacheEntry cached(AgentScopeModelCacheKey key, Instant now) {
        synchronized (cacheLock) {
            evictExpired(now);
            return cache.get(key);
        }
    }

    private static void requireCredentialCoordinate(
            TrustedModelBuildRequest request, ProviderCredentialHandle handle) {
        if (!request.connectionId().equals(handle.connectionId())
                || !request.credentialVersion().equals(handle.credentialVersion())) {
            handle.close();
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.CREDENTIAL_COORDINATE_MISMATCH);
        }
    }

    private static AgentScopeModelCacheKey cacheKey(
            TrustedModelBuildRequest request, AgentScopeModelProviderAdapter adapter) {
        return new AgentScopeModelCacheKey(
                request.organizationId(),
                request.connectionId(),
                request.connectionVersion(),
                request.credentialVersion(),
                request.providerDefinitionHash(),
                request.catalogCoordinate(),
                request.catalogContentHash(),
                request.modelRevision(),
                request.adapterKey(),
                adapter.adapterVersion(),
                request.formatterPolicy(),
                request.structuredOutputCompatibility(),
                sha256(request.endpoint().value() + '|' + request.endpointPath()),
                request.compatibilityHash(),
                safeOptionsHash(request.generateOptions()));
    }

    private static String safeOptionsHash(SafeModelGenerateOptions options) {
        String canonical = options.temperature().map(Object::toString).orElse("") + '|'
                + options.topP().map(Object::toString).orElse("") + '|'
                + options.maximumOutputTokens().map(Object::toString).orElse("") + '|'
                + options.reasoningMode() + '|'
                + options.cacheEnabled() + '|'
                + options.parallelToolCalls() + '|'
                + options.seed().map(Object::toString).orElse("") + '|'
                + options.maximumAttempts();
        return sha256(canonical);
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void evictExpired(Instant now) {
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void evictOverflow() {
        while (cache.size() > maximumCacheEntries) {
            AgentScopeModelCacheKey oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return required;
    }

    private record CacheEntry(Model model, Instant expiresAt) {}
}
