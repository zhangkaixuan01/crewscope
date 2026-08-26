package io.crewscope.integration.provider.collaboration;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, authorization-keyed tenant-token cache with per-key single flight. */
public final class LarkTenantTokenCache implements AutoCloseable {

    @FunctionalInterface
    interface Loader {
        LarkTenantToken load();
    }

    private final int maximumEntries;
    private final Duration expirySafetyMargin;
    private final ConcurrentHashMap<LarkTokenCacheKey, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LarkTenantTokenCache(int maximumEntries, Duration expirySafetyMargin) {
        if (maximumEntries < 1 || maximumEntries > 10_000) {
            throw new IllegalArgumentException("Lark token cache entries must be between 1 and 10000");
        }
        this.maximumEntries = maximumEntries;
        this.expirySafetyMargin = Objects.requireNonNull(
                expirySafetyMargin, "expirySafetyMargin");
        if (expirySafetyMargin.compareTo(Duration.ofSeconds(60)) < 0
                || expirySafetyMargin.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Lark token expiry margin must be within [60s, 10m]");
        }
    }

    LarkTenantToken getOrLoad(
            LarkTokenCacheKey key, UtcTimestamp now, Loader loader) {
        requireOpen();
        LarkTokenCacheKey requiredKey = Objects.requireNonNull(key, "key");
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        Slot slot = slots.computeIfAbsent(requiredKey, ignored -> new Slot());
        LarkTenantToken result;
        synchronized (slot) {
            requireOpen();
            slot.lastAccess = requiredNow;
            if (slot.token != null
                    && slot.token.usableAt(requiredNow, expirySafetyMargin)) {
                return slot.token;
            }
            LarkTenantToken loaded;
            try {
                loaded = Objects.requireNonNull(loader, "loader").load();
            } catch (RuntimeException failure) {
                slots.remove(requiredKey, slot);
                throw failure;
            }
            requireOpen();
            if (!loaded.usableAt(requiredNow, expirySafetyMargin)) {
                throw LarkProviderException.of(
                        LarkProviderErrorCode.INVALID_RESPONSE,
                        "Lark tenant token expires inside the configured safety margin",
                        "LARK_TOKEN_EXPIRY_UNSAFE");
            }
            slot.token = loaded;
            slot.lastAccess = requiredNow;
            result = loaded;
        }
        enforceBound(requiredKey, requiredNow);
        return result;
    }

    void invalidate(LarkTokenCacheKey key) {
        Slot slot = slots.remove(Objects.requireNonNull(key, "key"));
        if (slot != null) {
            synchronized (slot) {
                slot.token = null;
            }
        }
    }

    public int size() {
        return slots.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            slots.values().forEach(slot -> {
                synchronized (slot) {
                    slot.token = null;
                }
            });
            slots.clear();
        }
    }

    @Override
    public String toString() {
        return "LarkTenantTokenCache[entries=" + size() + ", tokens=REDACTED]";
    }

    private void enforceBound(LarkTokenCacheKey protectedKey, UtcTimestamp now) {
        slots.entrySet().removeIf(entry -> !entry.getKey().equals(protectedKey)
                && expired(entry.getValue(), now));
        while (slots.size() > maximumEntries) {
            Map.Entry<LarkTokenCacheKey, Slot> oldest = slots.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(protectedKey))
                    .min(Comparator.comparing(entry -> entry.getValue().lastAccess))
                    .orElse(null);
            if (oldest == null || !slots.remove(oldest.getKey(), oldest.getValue())) {
                break;
            }
        }
    }

    private boolean expired(Slot slot, UtcTimestamp now) {
        synchronized (slot) {
            return slot.token == null || !slot.token.usableAt(now, expirySafetyMargin);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Lark tenant token cache is closed");
        }
    }

    private static final class Slot {
        private LarkTenantToken token;
        private UtcTimestamp lastAccess = UtcTimestamp.from(java.time.Instant.EPOCH);
    }
}

/** In-memory tenant token whose string representation is permanently redacted. */
record LarkTenantToken(String value, UtcTimestamp expiresAt) {

    LarkTenantToken {
        if (value == null || value.isBlank() || value.length() > 4_096
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Lark tenant token has an invalid shape");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    boolean usableAt(UtcTimestamp now, Duration safetyMargin) {
        return expiresAt.value().minus(safetyMargin)
                .isAfter(Objects.requireNonNull(now, "now").value());
    }

    @Override
    public String toString() {
        return "LarkTenantToken[REDACTED]";
    }
}
