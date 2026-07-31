package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BoundedPhysicalObservationQueue} proving bounded capacity,
 * rejection behavior, metric tracking, and shutdown drain.
 */
class BoundedPhysicalObservationQueueTest {

    @Test
    void rejectsExcessObservationsWhenCapacityReached() throws Exception {
        ReconciliationMetrics metrics = new ReconciliationMetrics();
        int capacity = 4;
        BoundedPhysicalObservationQueue queue = new BoundedPhysicalObservationQueue(capacity, metrics);

        CountDownLatch blockFirst = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);

        Consumer<PhysicalUniqueItemObservation> blockingProcessor = obs -> {
            firstStarted.countDown();
            try {
                blockFirst.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        PhysicalUniqueItemObservation obs = buildObs();
        assertTrue(queue.submit(obs, blockingProcessor));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        for (int i = 0; i < capacity; i++) {
            assertTrue(queue.submit(buildObs(), blockingProcessor),
                    "Observation " + i + " should be accepted (queue not yet full)");
        }

        assertFalse(queue.submit(buildObs(), blockingProcessor),
                "Observation beyond capacity should be rejected");

        assertEquals(1, queue.totalRejected());
        assertTrue(metrics.physicalObservationWarnings() >= 1,
                "Rejected observation should increment physical observation warnings");
        assertEquals(1, metrics.rejectedPhysicalObservations());

        blockFirst.countDown();
        assertTrue(queue.shutdown(2));
    }

    @Test
    void pendingCountReflectsQueueDepth() throws Exception {
        ReconciliationMetrics metrics = new ReconciliationMetrics();
        BoundedPhysicalObservationQueue queue = new BoundedPhysicalObservationQueue(16, metrics);

        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        Consumer<PhysicalUniqueItemObservation> blockingProcessor = obs -> {
            started.countDown();
            try {
                block.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        queue.submit(buildObs(), blockingProcessor);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        for (int i = 0; i < 5; i++) {
            queue.submit(buildObs(), blockingProcessor);
        }

        assertEquals(5, queue.pendingCount());

        block.countDown();
        assertTrue(queue.shutdown(2));
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void shutdownDrainsAllPendingObservations() throws Exception {
        ReconciliationMetrics metrics = new ReconciliationMetrics();
        BoundedPhysicalObservationQueue queue = new BoundedPhysicalObservationQueue(32, metrics);

        AtomicInteger processed = new AtomicInteger(0);
        Consumer<PhysicalUniqueItemObservation> countingProcessor = obs -> processed.incrementAndGet();

        for (int i = 0; i < 10; i++) {
            queue.submit(buildObs(), countingProcessor);
        }

        assertTrue(queue.shutdown(5));
        assertEquals(10, processed.get());
        assertEquals(0, queue.totalRejected());
    }

    @Test
    void maxCapacityReturnsConfiguredValue() {
        ReconciliationMetrics metrics = new ReconciliationMetrics();
        BoundedPhysicalObservationQueue queue = new BoundedPhysicalObservationQueue(128, metrics);
        assertEquals(128, queue.maxCapacity());
    }

    private PhysicalUniqueItemObservation buildObs() {
        return new PhysicalUniqueItemObservation(
                TrackedItemId.random(),
                ContentKey.parse("minecraft:stone"),
                "stone",
                OwnershipSubject.worldDrop(UUID.randomUUID()),
                PhysicalObservationReason.DROPPED,
                UUID.randomUUID(), UUID.randomUUID(), "world",
                0, 64, 0, "stone:1",
                PhysicalObservationCycle.create(1, "session-1"),
                Instant.now()
        );
    }
}
