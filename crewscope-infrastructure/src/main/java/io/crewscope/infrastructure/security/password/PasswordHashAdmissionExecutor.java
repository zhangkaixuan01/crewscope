package io.crewscope.infrastructure.security.password;

import io.crewscope.application.identity.PasswordHashCapacityException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Fair, bounded admission and dedicated worker pool for memory-hard password operations. */
public final class PasswordHashAdmissionExecutor implements AutoCloseable {

    private final Duration admissionWait;
    private final Semaphore permits;
    private final ThreadPoolExecutor workers;
    private final ExecutorService expiry;

    public PasswordHashAdmissionExecutor(int permitCount, Duration admissionWait) {
        if (permitCount < 1 || permitCount > 8) {
            throw new IllegalArgumentException("Password Hash permits must be between 1 and 8");
        }
        this.admissionWait = requirePositive(admissionWait);
        this.permits = new Semaphore(permitCount, true);
        AtomicInteger workerSequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                permitCount,
                permitCount,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(permitCount, true),
                runnable -> daemon(
                        runnable,
                        "crewscope-password-hash-" + workerSequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.prestartAllCoreThreads();
        this.expiry = Executors.newSingleThreadExecutor(
                runnable -> daemon(runnable, "crewscope-password-admission"));
    }

    /** Returns immediately; queued callers expire without starting Hash work after the fixed wait. */
    public <T> CompletionStage<T> submit(Supplier<T> operation) {
        HashTask<T> task = new HashTask<>(Objects.requireNonNull(operation, "operation"));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException full) {
            task.reject();
            return task.result;
        }
        CompletableFuture.runAsync(
                task::expire,
                CompletableFuture.delayedExecutor(
                        admissionWait.toNanos(), TimeUnit.NANOSECONDS, expiry));
        return task.result;
    }

    @Override
    public void close() {
        // shutdownNow returns work that never started. Complete those stages explicitly so
        // graceful shutdown cannot leave Web requests waiting on an orphaned password operation.
        for (Runnable queued : workers.shutdownNow()) {
            if (queued instanceof RejectableTask rejectable) {
                rejectable.reject();
            }
        }
        expiry.shutdownNow();
    }

    private static Duration requirePositive(Duration value) {
        Duration required = Objects.requireNonNull(value, "admissionWait");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException("Password Hash admission wait must be positive");
        }
        return required;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private interface RejectableTask extends Runnable {

        void reject();
    }

    private final class HashTask<T> implements RejectableTask {

        private final Supplier<T> operation;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();

        private HashTask(Supplier<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            boolean acquired = false;
            try {
                acquired = permits.tryAcquire(admissionWait.toNanos(), TimeUnit.NANOSECONDS);
                if (!acquired || !claimed.compareAndSet(false, true)) {
                    if (!acquired) {
                        reject();
                    }
                    return;
                }
                result.complete(operation.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reject();
            } catch (Throwable failure) {
                // CompletableFuture-style async boundaries must settle even for provider Errors.
                result.completeExceptionally(failure);
            } finally {
                if (acquired) {
                    permits.release();
                }
            }
        }

        private void expire() {
            if (claimed.compareAndSet(false, true)) {
                workers.remove(this);
                result.completeExceptionally(new PasswordHashCapacityException());
            }
        }

        @Override
        public void reject() {
            claimed.set(true);
            result.completeExceptionally(new PasswordHashCapacityException());
        }
    }
}
