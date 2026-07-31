package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the shared {@link PhysicalObservationSequencer} produces globally
 * monotonic sequences across different listeners, proving that a later
 * observation from one listener can never have a lower sequence than an
 * earlier observation from another listener.
 */
class PhysicalObservationSequencerTest {

    @Test
    void sequencesAreGloballyMonotonicAcrossDifferentListeners() {
        PhysicalObservationSequencer sequencer =
                new PhysicalObservationSequencer("session-1");

        PhysicalObservationCycle c1 = sequencer.nextCycle();
        PhysicalObservationCycle c2 = sequencer.nextCycle();
        PhysicalObservationCycle c3 = sequencer.nextCycle();

        assertEquals(1, c1.observationSequence());
        assertEquals(2, c2.observationSequence());
        assertEquals(3, c3.observationSequence());
        assertEquals("session-1", c1.serverSessionId());
        assertEquals("session-1", c2.serverSessionId());
    }

    @Test
    void laterEntityObservationCannotHaveLowerSequenceThanEarlierWorldDrop() {
        PhysicalObservationSequencer sequencer =
                new PhysicalObservationSequencer("session-1");

        PhysicalObservationCycle worldDropCycle = sequencer.nextCycle();
        PhysicalObservationCycle entityCycle = sequencer.nextCycle();

        assertTrue(entityCycle.observationSequence() > worldDropCycle.observationSequence(),
                "ENTITY observation sequence must be greater than earlier WORLD_DROP sequence");
    }

    @Test
    void equalSequenceValuesCannotBeIndependentlyGeneratedBySeparateListeners() {
        PhysicalObservationSequencer sequencer =
                new PhysicalObservationSequencer("session-1");

        PhysicalObservationCycle c1 = sequencer.nextCycle();
        PhysicalObservationCycle c2 = sequencer.nextCycle();

        assertTrue(c1.observationSequence() != c2.observationSequence(),
                "Two calls to nextCycle() must never produce equal sequences");
    }

    @Test
    void validCrossListenerTransitionsAreNotRejectedAsStaleOrConflict() {
        PhysicalObservationSequencer sequencer =
                new PhysicalObservationSequencer("session-1");
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);

        TrackedItemId itemId = TrackedItemId.random();
        UUID itemEntityUuid = UUID.randomUUID();
        UUID zombieUuid = UUID.randomUUID();

        PhysicalObservationCycle worldDropCycle = sequencer.nextCycle();
        PhysicalUniqueItemObservation worldDropObs = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                worldDropCycle, java.time.Instant.now());
        PhysicalObservationRegistry.RegistrationResult r1 = registry.register(worldDropObs);
        assertTrue(r1.isAccepted());

        PhysicalObservationCycle entityCycle = sequencer.nextCycle();
        PhysicalUniqueItemObservation entityObs = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                entityCycle, java.time.Instant.now());
        PhysicalObservationRegistry.RegistrationResult r2 = registry.register(entityObs);
        assertTrue(r2.isAccepted(),
                "Cross-listener transition with higher sequence must not be rejected as stale or conflict");
    }

    @Test
    void concurrentNextCycleCallsProduceUniqueSequences() throws InterruptedException {
        PhysicalObservationSequencer sequencer =
                new PhysicalObservationSequencer("session-1");

        int threadCount = 8;
        int callsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Long> allSequences = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < callsPerThread; i++) {
                    allSequences.add(sequencer.nextCycle().observationSequence());
                }
            });
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(threadCount * callsPerThread, allSequences.size());

        var unique = new java.util.HashSet<>(allSequences);
        assertEquals(allSequences.size(), unique.size(),
                "All sequences must be unique even under concurrent access");
    }
}
