package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalUniqueItemObservationTest {

    private PhysicalUniqueItemObservation build(
            TrackedItemId itemId, OwnershipSubject subject,
            PhysicalObservationReason reason, long sequence, String sessionId
    ) {
        return new PhysicalUniqueItemObservation(
                itemId,
                ContentKey.parse("minecraft:diamond_sword"),
                "diamond_sword",
                subject,
                reason,
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
    void idempotencyKeyIsDeterministicForSameInputs() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject = OwnershipSubject.worldDrop(UUID.randomUUID());
        PhysicalObservationCycle cycle = PhysicalObservationCycle.create(5, "session-1");

        PhysicalUniqueItemObservation obs1 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                subject, PhysicalObservationReason.DROPPED,
                UUID.randomUUID(), UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1", cycle, Instant.now());

        PhysicalUniqueItemObservation obs2 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                subject, PhysicalObservationReason.DROPPED,
                UUID.randomUUID(), UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1", cycle, Instant.now());

        assertEquals(obs1.idempotencyKey(), obs2.idempotencyKey());
    }

    @Test
    void idempotencyKeyDiffersForDifferentReasons() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalUniqueItemObservation dropped = build(itemId, subject,
                PhysicalObservationReason.DROPPED, 1, "session-1");
        PhysicalUniqueItemObservation despawned = build(itemId, subject,
                PhysicalObservationReason.DESPAWNED, 1, "session-1");

        assertNotEquals(dropped.idempotencyKey(), despawned.idempotencyKey());
    }

    @Test
    void idempotencyKeyDiffersForDifferentSubjects() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject1 = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject subject2 = OwnershipSubject.entity(UUID.randomUUID());

        PhysicalUniqueItemObservation obs1 = build(itemId, subject1,
                PhysicalObservationReason.DROPPED, 1, "session-1");
        PhysicalUniqueItemObservation obs2 = build(itemId, subject2,
                PhysicalObservationReason.DROPPED, 1, "session-1");

        assertNotEquals(obs1.idempotencyKey(), obs2.idempotencyKey());
    }

    @Test
    void idempotencyKeyDiffersForDifferentSequences() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalUniqueItemObservation obs1 = build(itemId, subject,
                PhysicalObservationReason.DROPPED, 1, "session-1");
        PhysicalUniqueItemObservation obs2 = build(itemId, subject,
                PhysicalObservationReason.DROPPED, 2, "session-1");

        assertNotEquals(obs1.idempotencyKey(), obs2.idempotencyKey());
    }

    @Test
    void idempotencyKeyContainsSessionAndSequenceAndItem() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject subject = OwnershipSubject.worldDrop(UUID.randomUUID());
        PhysicalUniqueItemObservation obs = build(itemId, subject,
                PhysicalObservationReason.DROPPED, 42, "my-session");

        String key = obs.idempotencyKey();
        assertTrue(key.contains("my-session"));
        assertTrue(key.contains("42"));
        assertTrue(key.contains(itemId.toString()));
    }

    @Test
    void optionalEntityItemUuidPresentWhenProvided() {
        UUID entityUuid = UUID.randomUUID();
        PhysicalUniqueItemObservation obs = new PhysicalUniqueItemObservation(
                TrackedItemId.random(), ContentKey.parse("minecraft:stone"), "stone",
                OwnershipSubject.worldDrop(entityUuid),
                PhysicalObservationReason.WORLD_DROP_OBSERVED,
                entityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "stone:1",
                PhysicalObservationCycle.create(1, "session"), Instant.now());

        assertTrue(obs.optionalEntityItemUuid().isPresent());
        assertEquals(entityUuid, obs.optionalEntityItemUuid().get());
    }

    @Test
    void optionalEntityItemUuidEmptyWhenNull() {
        PhysicalUniqueItemObservation obs = new PhysicalUniqueItemObservation(
                TrackedItemId.random(), ContentKey.parse("minecraft:stone"), "stone",
                OwnershipSubject.entity(UUID.randomUUID()),
                PhysicalObservationReason.LOADED_ENTITY_EQUIPMENT,
                null, UUID.randomUUID(), "world",
                0, 64, 0, "stone:1",
                PhysicalObservationCycle.create(1, "session"), Instant.now());

        assertTrue(obs.optionalEntityItemUuid().isEmpty());
    }
}
