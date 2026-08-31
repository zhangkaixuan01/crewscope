package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded model preflight cache and catalog-query limits. */
@ConfigurationProperties(prefix = "crewscope.model.preflight")
public class ModelPreflightProperties {

    private Duration healthCacheTtl = Duration.ofSeconds(30);
    private int maximumHealthCacheEntries = 1_024;
    // The persistence adapter intentionally caps one catalog page at 200 rows.
    private int maximumCatalogEntriesPerProvider = 200;

    public Duration getHealthCacheTtl() {
        return healthCacheTtl;
    }

    public void setHealthCacheTtl(Duration healthCacheTtl) {
        this.healthCacheTtl = healthCacheTtl;
    }

    public int getMaximumHealthCacheEntries() {
        return maximumHealthCacheEntries;
    }

    public void setMaximumHealthCacheEntries(int maximumHealthCacheEntries) {
        this.maximumHealthCacheEntries = maximumHealthCacheEntries;
    }

    public int getMaximumCatalogEntriesPerProvider() {
        return maximumCatalogEntriesPerProvider;
    }

    public void setMaximumCatalogEntriesPerProvider(int maximumCatalogEntriesPerProvider) {
        this.maximumCatalogEntriesPerProvider = maximumCatalogEntriesPerProvider;
    }

    public Duration validatedHealthCacheTtl() {
        Duration value = Objects.requireNonNull(
                healthCacheTtl, "crewscope.model.preflight.health-cache-ttl");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException(
                    "crewscope.model.preflight.health-cache-ttl must be positive and at most 5m");
        }
        return value;
    }

    public int validatedMaximumHealthCacheEntries() {
        return bounded(
                maximumHealthCacheEntries,
                "crewscope.model.preflight.maximum-health-cache-entries", 10_000);
    }

    public int validatedMaximumCatalogEntriesPerProvider() {
        return bounded(
                maximumCatalogEntriesPerProvider,
                "crewscope.model.preflight.maximum-catalog-entries-per-provider", 200);
    }

    private static int bounded(int value, String field, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalStateException(field + " must be between 1 and " + maximum);
        }
        return value;
    }
}
