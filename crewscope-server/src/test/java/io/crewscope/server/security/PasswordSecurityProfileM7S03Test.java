package io.crewscope.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Executable M7-S03 security fixture and profile runner.
 *
 * <p>The unit tests freeze behavior that does not depend on machine speed. The {@link #main(String[])}
 * runner measures candidate encoders on explicitly identified deployment profiles; it is kept out
 * of the normal test lifecycle so CI timing noise cannot silently change the security policy.
 */
class PasswordSecurityProfileM7S03Test {

    private static final String SAMPLE_PASSWORD = "CrewScope-M7-S03-password-2026";
    private static final int MAX_PASSWORD_CODE_POINTS = 128;
    private static final int MAX_PASSWORD_UTF8_BYTES = 512;
    private static final Duration HASH_ADMISSION_WAIT = Duration.ofMillis(100);

    @Test
    void argon2idCandidateCanReadBcryptAndSignalsRehash() {
        PasswordEncoder argon2id = argon2id(32_768, 3, 1);
        PasswordEncoder weakerArgon2id = argon2id(19_456, 2, 1);
        PasswordEncoder bcrypt = new BCryptPasswordEncoder(11);
        Map<String, PasswordEncoder> encoders = new LinkedHashMap<>();
        encoders.put("argon2id", argon2id);
        encoders.put("bcrypt", bcrypt);
        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder("argon2id", encoders);

        String current = delegating.encode(SAMPLE_PASSWORD);
        String weaker = "{argon2id}" + weakerArgon2id.encode(SAMPLE_PASSWORD);
        String legacy = "{bcrypt}" + bcrypt.encode(SAMPLE_PASSWORD);

        assertThat(current).startsWith("{argon2id}$argon2id$v=19$m=32768,t=3,p=1$");
        assertThat(delegating.matches(SAMPLE_PASSWORD, current)).isTrue();
        assertThat(delegating.matches(SAMPLE_PASSWORD, weaker)).isTrue();
        assertThat(delegating.matches(SAMPLE_PASSWORD, legacy)).isTrue();
        assertThat(delegating.upgradeEncoding(current)).isFalse();
        assertThat(delegating.upgradeEncoding(weaker)).isTrue();
        assertThat(delegating.upgradeEncoding(legacy)).isTrue();
    }

    @Test
    void passwordBudgetRejectsWorkBeforeExpensiveHashing() {
        AtomicInteger hashes = new AtomicInteger();
        PasswordEncoder countingEncoder = countingEncoder(hashes);

        assertThat(matchesWithinBudget("correct horse battery staple", "encoded", countingEncoder))
                .isTrue();
        assertThat(hashes).hasValue(1);

        assertThat(matchesWithinBudget("a".repeat(129), "encoded", countingEncoder)).isFalse();
        assertThat(matchesWithinBudget("界".repeat(128), "encoded", countingEncoder)).isTrue();
        assertThat(matchesWithinBudget("😀".repeat(128), "encoded", countingEncoder)).isTrue();
        assertThat(matchesWithinBudget("😀".repeat(129), "encoded", countingEncoder)).isFalse();
        assertThat(hashes).hasValue(3);
    }

    @Test
    void temporaryLockAndUnknownAccountHaveTheSamePublicFailure() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        LoginDefenseFixture known = new LoginDefenseFixture(clock);
        LoginDefenseFixture unknown = new LoginDefenseFixture(clock);

        for (int attempt = 1; attempt <= 9; attempt++) {
            assertThat(known.fail(true)).isEqualTo(PublicFailure.INVALID_CREDENTIALS);
        }
        assertThat(known.fail(true)).isEqualTo(PublicFailure.INVALID_CREDENTIALS);
        assertThat(known.isLocked()).isTrue();
        assertThat(known.authenticateWhileLocked()).isEqualTo(PublicFailure.INVALID_CREDENTIALS);
        assertThat(known.hashOperations()).isEqualTo(11);

        for (int attempt = 1; attempt <= 11; attempt++) {
            assertThat(unknown.fail(false)).isEqualTo(PublicFailure.INVALID_CREDENTIALS);
        }
        assertThat(unknown.hashOperations()).isEqualTo(11);

        clock.advance(Duration.ofMinutes(15));
        assertThat(known.isLocked()).isFalse();
        assertThat(known.succeed()).isNull();
        assertThat(known.failureCount()).isZero();
    }

    @Test
    void identifierAndNetworkRateLimitsDoNotDependOnAccountLookup() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        LoginRateLimitFixture known = new LoginRateLimitFixture(clock);
        LoginRateLimitFixture unknown = new LoginRateLimitFixture(clock);

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(known.admit("known", "network-a")).isEqualTo(Admission.ALLOWED);
            assertThat(unknown.admit("unknown", "network-a")).isEqualTo(Admission.ALLOWED);
        }
        assertThat(known.admit("known", "network-a")).isEqualTo(Admission.TOO_MANY_REQUESTS);
        assertThat(unknown.admit("unknown", "network-a"))
                .isEqualTo(Admission.TOO_MANY_REQUESTS);

        LoginRateLimitFixture sharedNetwork = new LoginRateLimitFixture(clock);
        for (int attempt = 1; attempt <= 60; attempt++) {
            assertThat(sharedNetwork.admit("person-" + attempt, "network-b"))
                    .isEqualTo(Admission.ALLOWED);
        }
        assertThat(sharedNetwork.admit("another-person", "network-b"))
                .isEqualTo(Admission.TOO_MANY_REQUESTS);

        clock.advance(Duration.ofMinutes(15));
        assertThat(known.admit("known", "network-a")).isEqualTo(Admission.ALLOWED);
        assertThat(sharedNetwork.admit("another-person", "network-b"))
                .isEqualTo(Admission.ALLOWED);
    }

    @Test
    void hashAdmissionRejectsOverflowWithoutStartingMoreWork() throws Exception {
        HashAdmission admission = new HashAdmission(2, HASH_ADMISSION_WAIT);
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first =
                    executor.submit(() -> admission.execute(blocking(occupied, release)));
            Future<Boolean> second =
                    executor.submit(() -> admission.execute(blocking(occupied, release)));
            assertThat(occupied.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(admission.execute(() -> true)).isFalse();
            assertThat(admission.started()).isEqualTo(2);
            assertThat(admission.rejected()).isEqualTo(1);

            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** Runs the reproducible candidate matrix used by the M7-S03 evidence record. */
    public static void main(String[] args) throws Exception {
        BenchmarkOptions options = BenchmarkOptions.parse(args);
        List<EncoderCandidate> candidates =
                List.of(
                        new EncoderCandidate("argon2id-m19-t2-p1", argon2id(19_456, 2, 1)),
                        new EncoderCandidate("argon2id-m32-t2-p1", argon2id(32_768, 2, 1)),
                        new EncoderCandidate("argon2id-m32-t3-p1", argon2id(32_768, 3, 1)),
                        new EncoderCandidate("bcrypt-10", new BCryptPasswordEncoder(10)),
                        new EncoderCandidate("bcrypt-11", new BCryptPasswordEncoder(11)),
                        new EncoderCandidate("bcrypt-12", new BCryptPasswordEncoder(12)));

        System.out.printf(
                Locale.ROOT,
                "# profile=%s processors=%d max_heap_mib=%.1f java=%s os=%s/%s warmup=%d samples=%d%n",
                options.profile(),
                Runtime.getRuntime().availableProcessors(),
                bytesToMiB(Runtime.getRuntime().maxMemory()),
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                options.warmup(),
                options.samples());
        System.out.println(
                "profile,algorithm,concurrency,operations,p50_ms,p95_ms,max_ms,throughput_ops_s,heap_peak_delta_mib");
        for (EncoderCandidate candidate : candidates) {
            for (int concurrency : options.concurrency()) {
                BenchmarkResult result = benchmark(candidate.encoder(), options, concurrency);
                System.out.printf(
                        Locale.ROOT,
                        "%s,%s,%d,%d,%.2f,%.2f,%.2f,%.2f,%.1f%n",
                        options.profile(),
                        candidate.name(),
                        concurrency,
                        result.operations(),
                        result.p50Millis(),
                        result.p95Millis(),
                        result.maxMillis(),
                        result.throughput(),
                        result.heapPeakDeltaMiB());
            }
        }
    }

    private static BenchmarkResult benchmark(
            PasswordEncoder encoder, BenchmarkOptions options, int concurrency) throws Exception {
        for (int index = 0; index < options.warmup(); index++) {
            encoder.encode(SAMPLE_PASSWORD);
        }
        System.gc();
        long heapBaseline = currentHeapUsage();
        resetHeapPeaks();

        int operations = Math.max(options.samples(), concurrency * 2);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(Math.min(operations, concurrency));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < operations; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    long startedAt = System.nanoTime();
                                    encoder.encode(SAMPLE_PASSWORD);
                                    return System.nanoTime() - startedAt;
                                }));
            }
            if (!ready.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Benchmark workers did not become ready");
            }
            long wallStartedAt = System.nanoTime();
            start.countDown();
            List<Long> durations = new ArrayList<>();
            for (Future<Long> future : futures) {
                durations.add(future.get(5, TimeUnit.MINUTES));
            }
            long wallNanos = System.nanoTime() - wallStartedAt;
            durations.sort(Comparator.naturalOrder());
            double peakDelta = bytesToMiB(Math.max(0, peakHeapUsage() - heapBaseline));
            return new BenchmarkResult(
                    operations,
                    nanosToMillis(percentile(durations, 0.50)),
                    nanosToMillis(percentile(durations, 0.95)),
                    nanosToMillis(durations.get(durations.size() - 1)),
                    operations / (wallNanos / 1_000_000_000.0),
                    peakDelta);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static Argon2PasswordEncoder argon2id(int memoryKiB, int iterations, int parallelism) {
        return new Argon2PasswordEncoder(16, 32, parallelism, memoryKiB, iterations);
    }

    private static boolean matchesWithinBudget(
            String password, String encoded, PasswordEncoder encoder) {
        if (password == null
                || password.codePointCount(0, password.length()) > MAX_PASSWORD_CODE_POINTS
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_UTF8_BYTES) {
            return false;
        }
        return encoder.matches(password, encoded);
    }

    private static PasswordEncoder countingEncoder(AtomicInteger hashes) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                hashes.incrementAndGet();
                return "encoded";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                hashes.incrementAndGet();
                return "encoded".equals(encodedPassword);
            }
        };
    }

    private static Callable<Boolean> blocking(CountDownLatch occupied, CountDownLatch release) {
        return () -> {
            occupied.countDown();
            return release.await(2, TimeUnit.SECONDS);
        };
    }

    private static long currentHeapUsage() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long peakHeapUsage() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(MemoryPoolMXBean::isValid)
                .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static void resetHeapPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(MemoryPoolMXBean::isValid)
                .forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static double bytesToMiB(long bytes) {
        return bytes / 1024.0 / 1024.0;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private enum PublicFailure {
        INVALID_CREDENTIALS
    }

    private enum Admission {
        ALLOWED,
        TOO_MANY_REQUESTS
    }

    private record EncoderCandidate(String name, PasswordEncoder encoder) {}

    private record BenchmarkResult(
            int operations,
            double p50Millis,
            double p95Millis,
            double maxMillis,
            double throughput,
            double heapPeakDeltaMiB) {}

    private record BenchmarkOptions(
            String profile, int warmup, int samples, List<Integer> concurrency) {

        private static BenchmarkOptions parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String argument : args) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw new IllegalArgumentException("Expected --key=value, got: " + argument);
                }
                String[] parts = argument.substring(2).split("=", 2);
                values.put(parts[0], parts[1]);
            }
            String profile = values.getOrDefault("profile", "development");
            int warmup = Integer.parseInt(values.getOrDefault("warmup", "2"));
            int samples = Integer.parseInt(values.getOrDefault("samples", "7"));
            List<Integer> concurrency =
                    Arrays.stream(values.getOrDefault("concurrency", "1,2,4").split(","))
                            .map(String::strip)
                            .map(Integer::parseInt)
                            .toList();
            if (profile.isBlank()
                    || warmup < 1
                    || samples < 3
                    || concurrency.stream().anyMatch(value -> value < 1 || value > 32)) {
                throw new IllegalArgumentException("Invalid benchmark options");
            }
            return new BenchmarkOptions(profile, warmup, samples, concurrency);
        }
    }

    private static final class MutableClock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private Instant now() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class LoginDefenseFixture {

        private static final int FAILURE_LIMIT = 10;
        private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
        private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

        private final MutableClock clock;
        private final List<Instant> failures = new ArrayList<>();
        private Instant lockedUntil;
        private int hashOperations;

        private LoginDefenseFixture(MutableClock clock) {
            this.clock = clock;
        }

        private PublicFailure fail(boolean accountExists) {
            // Both paths consume one hash: the stored hash or the deployment-wide dummy hash.
            hashOperations++;
            pruneFailures();
            failures.add(clock.now());
            if (accountExists && failures.size() >= FAILURE_LIMIT) {
                lockedUntil = clock.now().plus(LOCK_DURATION);
            }
            return PublicFailure.INVALID_CREDENTIALS;
        }

        private PublicFailure authenticateWhileLocked() {
            // Locked accounts still execute a dummy match to preserve the public timing class.
            hashOperations++;
            return PublicFailure.INVALID_CREDENTIALS;
        }

        private PublicFailure succeed() {
            if (isLocked()) {
                hashOperations++;
                return PublicFailure.INVALID_CREDENTIALS;
            }
            hashOperations++;
            failures.clear();
            return null;
        }

        private boolean isLocked() {
            if (lockedUntil != null && !clock.now().isBefore(lockedUntil)) {
                lockedUntil = null;
                failures.clear();
            }
            return lockedUntil != null;
        }

        private int failureCount() {
            pruneFailures();
            return failures.size();
        }

        private int hashOperations() {
            return hashOperations;
        }

        private void pruneFailures() {
            Instant floor = clock.now().minus(FAILURE_WINDOW);
            failures.removeIf(failure -> !failure.isAfter(floor));
        }
    }

    private static final class LoginRateLimitFixture {

        private static final int IDENTIFIER_LIMIT = 10;
        private static final Duration IDENTIFIER_WINDOW = Duration.ofMinutes(15);
        private static final int NETWORK_LIMIT = 60;
        private static final Duration NETWORK_WINDOW = Duration.ofMinutes(5);

        private final MutableClock clock;
        private final Map<String, List<Instant>> identifierAttempts = new LinkedHashMap<>();
        private final Map<String, List<Instant>> networkAttempts = new LinkedHashMap<>();

        private LoginRateLimitFixture(MutableClock clock) {
            this.clock = clock;
        }

        private Admission admit(String normalizedIdentifierDigest, String controlledNetworkDigest) {
            List<Instant> identifier =
                    current(identifierAttempts, normalizedIdentifierDigest, IDENTIFIER_WINDOW);
            List<Instant> network = current(networkAttempts, controlledNetworkDigest, NETWORK_WINDOW);
            if (identifier.size() >= IDENTIFIER_LIMIT || network.size() >= NETWORK_LIMIT) {
                return Admission.TOO_MANY_REQUESTS;
            }
            identifier.add(clock.now());
            network.add(clock.now());
            return Admission.ALLOWED;
        }

        private List<Instant> current(
                Map<String, List<Instant>> buckets, String digest, Duration window) {
            List<Instant> attempts = buckets.computeIfAbsent(digest, ignored -> new ArrayList<>());
            Instant floor = clock.now().minus(window);
            attempts.removeIf(attempt -> !attempt.isAfter(floor));
            return attempts;
        }
    }

    private static final class HashAdmission {

        private final Semaphore permits;
        private final Duration wait;
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();

        private HashAdmission(int concurrency, Duration wait) {
            this.permits = new Semaphore(concurrency, true);
            this.wait = wait;
        }

        private boolean execute(Callable<Boolean> operation) throws Exception {
            if (!permits.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                rejected.incrementAndGet();
                return false;
            }
            try {
                started.incrementAndGet();
                return operation.call();
            } finally {
                permits.release();
            }
        }

        private int started() {
            return started.get();
        }

        private int rejected() {
            return rejected.get();
        }
    }
}
