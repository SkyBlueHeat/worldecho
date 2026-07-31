package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalObservationRegistryTest {

    private PhysicalUniqueItemObservation observation(
            TrackedItemId itemId, OwnershipSubject subject, long sequence, String sessionId
    ) {
        return new PhysicalUniqueItemObservation(
                itemId,
                ContentKey.parse("minecraft:diamond_sword"),
                "diamond_sword",
                subject,
                PhysicalObservationReason.DROPPED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "world",
                0, 64, 0,
                "diamond_sword:1",
                PhysicalObservationCycle.create(sequence, sessionId),
                Instant.now()
        );
    }

    @Test
    void firstObservationIsAccepted() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        PhysicalUniqueItemObservation obs = observation(itemId,
                OwnershipSubject.worldDrop(UUID.randomUUID()), 1, "session-1");

        PhysicalObservationRegistry.RegistrationResult result = registry.register(obs);

        assertTrue(result.isAccepted());
    }

    @Test
    void duplicateSameSequenceSameSubjectIsDuplicate() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject = OwnershipSubject.worldDrop(UUID.randomUUID());
        PhysicalUniqueItemObservation obs = observation(itemId, subject, 1, "session-1");

        registry.register(obs);
        PhysicalObservationRegistry.RegistrationResult result = registry.register(obs);

        assertTrue(result.isDuplicate());
    }

    @Test
    void staleLowerSequenceIsRejected() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject1 = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject subject2 = OwnershipSubject.entity(UUID.randomUUID());

        registry.register(observation(itemId, subject1, 5, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, subject2, 3, "session-1"));

        assertTrue(result.isStale());
    }

    @Test
    void newerSequenceWithDifferentSubjectIsAccepted() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject1 = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject subject2 = OwnershipSubject.entity(UUID.randomUUID());

        registry.register(observation(itemId, subject1, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, subject2, 5, "session-1"));

        assertTrue(result.isAccepted());
    }

    @Test
    void conflictDetectedWhenNonTerminalSubjectsDifferSameSequence() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject1 = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject subject2 = OwnershipSubject.entity(UUID.randomUUID());

        registry.register(observation(itemId, subject1, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, subject2, 1, "session-1"));

        assertTrue(result.isConflict());
    }

    @Test
    void terminalSystemSubjectDoesNotConflict() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject system = OwnershipSubject.system("item-despawned");

        registry.register(observation(itemId, worldDrop, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, system, 1, "session-1"));

        assertTrue(result.isAccepted());
    }

    @Test
    void differentSessionIsAlwaysAccepted() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject = OwnershipSubject.worldDrop(UUID.randomUUID());

        registry.register(observation(itemId, subject, 5, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, subject, 1, "session-2"));

        assertTrue(result.isAccepted());
    }

    @Test
    void clearResetsRegistry() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        registry.register(observation(itemId,
                OwnershipSubject.worldDrop(UUID.randomUUID()), 1, "session-1"));

        assertEquals(1, registry.size());
        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    void conflictPlayerVersusWorldDrop() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject player = OwnershipSubject.player(UUID.randomUUID());
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        registry.register(observation(itemId, player, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, worldDrop, 1, "session-1"));

        assertTrue(result.isConflict());
    }

    @Test
    void conflictPlayerVersusEntity() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject player = OwnershipSubject.player(UUID.randomUUID());
        OwnershipSubject entity = OwnershipSubject.entity(UUID.randomUUID());

        registry.register(observation(itemId, player, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, entity, 1, "session-1"));

        assertTrue(result.isConflict());
    }

    @Test
    void conflictEntityVersusEntity() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject entity1 = OwnershipSubject.entity(UUID.randomUUID());
        OwnershipSubject entity2 = OwnershipSubject.entity(UUID.randomUUID());

        registry.register(observation(itemId, entity1, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, entity2, 1, "session-1"));

        assertTrue(result.isConflict());
    }

    @Test
    void conflictWorldDropVersusWorldDrop() {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(60_000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject drop1 = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject drop2 = OwnershipSubject.worldDrop(UUID.randomUUID());

        registry.register(observation(itemId, drop1, 1, "session-1"));
        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId, drop2, 1, "session-1"));

        assertTrue(result.isConflict());
    }

    @Test
    void staleObservationExpiresAfterTimeout() throws InterruptedException {
        PhysicalObservationRegistry registry = new PhysicalObservationRegistry(1000L);
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        registry.register(observation(itemId, worldDrop, 5, "session-1"));
        assertEquals(1, registry.size());

        Thread.sleep(1200);

        PhysicalObservationRegistry.RegistrationResult result =
                registry.register(observation(itemId,
                        OwnershipSubject.entity(UUID.randomUUID()), 3, "session-1"));

        assertTrue(result.isAccepted());
        assertEquals(1, registry.size());
    }
}
