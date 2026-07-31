package dev.worldecho.domain.item;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded queue for physical observations with backpressure.
 *
 * <p>Uses a bounded {@link LinkedBlockingQueue} with a configurable capacity.
 * When the queue is full, new observations are rejected and a warning metric
 * is incremented. This prevents unbounded memory growth during ItemSpawnEvent
 * or EntitiesLoadEvent bursts.
 *
 * <p>Observations are processed by a single writer thread to preserve ordering
 * and avoid concurrent SQLite writes.
 */
public final class BoundedPhysicalObservationQueue {

    private static final Logger LOGGER = Logger.getLogger(BoundedPhysicalObservationQueue.class.getName());

    private final ThreadPoolExecutor executor;
    private final ReconciliationMetrics metrics;
    private final int maxCapacity;
    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();

    public BoundedPhysicalObservationQueue(
            int maxCapacity,
            ReconciliationMetrics metrics
    ) {
        this.maxCapacity = maxCapacity;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(maxCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "worldecho-physical-observer");
                    thread.setDaemon(true);
                    return thread;
                },
                (r, executor) -> {
                    throw new RejectedExecutionException("Physical observation queue is full");
                }
        );
    }

    /**
     * Submits an observation for asynchronous processing.
     *
     * @return {@code true} if accepted, {@code false} if rejected due to full queue
     */
    public boolean submit(PhysicalUniqueItemObservation observation,
                          PhysicalUniqueItemObservationService service) {
        Objects.requireNonNull(service, "service");
        return submit(observation, service::process);
    }

    /**
     * Submits an observation for asynchronous processing using a consumer.
     *
     * @return {@code true} if accepted, {@code false} if rejected due to full queue
     */
    public boolean submit(PhysicalUniqueItemObservation observation,
                          Consumer<PhysicalUniqueItemObservation> processor) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(processor, "processor");

        totalSubmitted.incrementAndGet();
        metrics.incrementPendingPhysical();

        try {
            executor.execute(() -> {
                try {
                    processor.accept(observation);
                } finally {
                    metrics.decrementPendingPhysical();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            metrics.decrementPendingPhysical();
            totalRejected.incrementAndGet();
            metrics.recordRejectedPhysicalObservation();
            metrics.recordPhysicalObservationWarning();
            LOGGER.log(Level.WARNING,
                    "Physical observation queue full (capacity={0}), rejected observation for item {1}",
                    new Object[]{maxCapacity, observation.trackedItemId()});
            return false;
        }
    }

    /**
     * Shuts down the queue and waits for pending observations to drain.
     *
     * @param timeoutSeconds maximum time to wait
     * @return {@code true} if all pending observations drained, {@code false} if timeout
     */
    public boolean shutdown(long timeoutSeconds) {
        executor.shutdown();
        try {
            return executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public int pendingCount() {
        return executor.getQueue().size();
    }

    public int maxCapacity() {
        return maxCapacity;
    }

    public long totalSubmitted() {
        return totalSubmitted.get();
    }

    public long totalRejected() {
        return totalRejected.get();
    }
}
