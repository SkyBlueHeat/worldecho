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

    @Test
    void semanticTransitionKeyGroupsDroppedAndWorldDropObservedForSameItemEntity() {
        TrackedItemId itemId = TrackedItemId.random();
        UUID itemEntityUuid = UUID.randomUUID();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(itemEntityUuid);

        PhysicalUniqueItemObservation dropped = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                worldDrop, PhysicalObservationReason.DROPPED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());

        PhysicalUniqueItemObservation spawned = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                worldDrop, PhysicalObservationReason.WORLD_DROP_OBSERVED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(2, "session-1"), Instant.now());

        assertEquals(dropped.semanticTransitionKey(), spawned.semanticTransitionKey());
        assertTrue(dropped.semanticTransitionKey().startsWith("physical:world-drop:"));
        assertTrue(dropped.semanticTransitionKey().contains(itemEntityUuid.toString()));
    }

    @Test
    void semanticTransitionKeyDiffersForDifferentItemEntities() {
        TrackedItemId itemId = TrackedItemId.random();
        UUID entity1 = UUID.randomUUID();
        UUID entity2 = UUID.randomUUID();

        PhysicalUniqueItemObservation obs1 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.worldDrop(entity1),
                PhysicalObservationReason.DROPPED,
                entity1, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());

        PhysicalUniqueItemObservation obs2 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.worldDrop(entity2),
                PhysicalObservationReason.DROPPED,
                entity2, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(2, "session-1"), Instant.now());

        assertTrue(!obs1.semanticTransitionKey().equals(obs2.semanticTransitionKey()));
    }

    @Test
    void semanticTransitionKeyDiffersForEntityVsWorldDrop() {
        TrackedItemId itemId = TrackedItemId.random();
        UUID itemEntityUuid = UUID.randomUUID();
        UUID zombieUuid = UUID.randomUUID();

        PhysicalUniqueItemObservation worldDrop = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());

        PhysicalUniqueItemObservation entity = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(2, "session-1"), Instant.now());

        assertTrue(!worldDrop.semanticTransitionKey().equals(entity.semanticTransitionKey()));
        assertTrue(entity.semanticTransitionKey().startsWith("physical:entity-pickup:"));
        assertTrue(entity.semanticTransitionKey().contains(itemEntityUuid.toString()));
        assertTrue(entity.semanticTransitionKey().contains(zombieUuid.toString()));
    }

    @Test
    void despawnedUsesOccurrenceSpecificDespawnKey() {
        TrackedItemId itemId = TrackedItemId.random();
        UUID itemEntityUuid = UUID.randomUUID();

        PhysicalUniqueItemObservation despawn = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.system("item-despawned"),
                PhysicalObservationReason.DESPAWNED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());

        assertTrue(despawn.semanticTransitionKey().startsWith("physical:despawn:"));
        assertTrue(despawn.semanticTransitionKey().contains(itemEntityUuid.toString()));
    }

    @Test
    void entityPickupKeyDiffersForSameEntityFromDifferentItemEntities() {
        TrackedItemId itemId = TrackedItemId.random();
        UUID zombieUuid = UUID.randomUUID();
        UUID itemEntity1 = UUID.randomUUID();
        UUID itemEntity2 = UUID.randomUUID();

        PhysicalUniqueItemObservation pickup1 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD,
                itemEntity1, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());

        PhysicalUniqueItemObservation pickup2 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD,
                itemEntity2, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(3, "session-1"), Instant.now());

        assertNotEquals(pickup1.semanticTransitionKey(), pickup2.semanticTransitionKey());
    }
}
